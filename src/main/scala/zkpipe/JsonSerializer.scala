package zkpipe

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util

import JsonConverters._
import com.google.common.io.BaseEncoding
import com.typesafe.scalalogging.LazyLogging
import io.prometheus.client.{Counter, Summary}
import org.apache.jute.BinaryInputArchive
import org.apache.kafka.common.serialization.Serializer
import org.apache.zookeeper.ZooDefs.OpCode
import org.apache.zookeeper.txn._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

object JsonConverters {
    implicit def toJson(implicit txn: CreateTxn): JValue =
        "create" ->
            ("path" -> txn.getPath) ~
                ("data" -> BaseEncoding.base64().encode(txn.getData)) ~
                ("acl" -> txn.getAcl.asScala.map({ acl =>
                    ("scheme" -> acl.getId.getScheme) ~ ("id" -> acl.getId.getId) ~ ("perms" -> acl.getPerms)
                })) ~
                ("ephemeral" -> txn.getEphemeral) ~
                ("parentCVersion" -> txn.getParentCVersion)

    implicit def toJson(implicit txn: CreateContainerTxn): JValue =
        "create-container" ->
            ("path" -> txn.getPath) ~
                ("data" -> BaseEncoding.base64().encode(txn.getData)) ~
                ("acl" -> txn.getAcl.asScala.map({ acl =>
                    ("scheme" -> acl.getId.getScheme) ~ ("id" -> acl.getId.getId) ~ ("perms" -> acl.getPerms)
                })) ~
                ("parentCVersion" -> txn.getParentCVersion)

    implicit def toJson(implicit txn: DeleteTxn): JValue =
        "delete" -> ("path" -> txn.getPath)

    implicit def toJson(implicit txn: SetDataTxn): JValue =
        "set-data" ->
            ("path" -> txn.getPath) ~
                ("data" -> BaseEncoding.base64().encode(txn.getData)) ~
                ("version" -> txn.getVersion)

    implicit def toJson(implicit txn: CheckVersionTxn): JValue =
        "check-version" ->
            ("path" -> txn.getPath) ~
                ("version" -> txn.getVersion)

    implicit def toJson(implicit txn: SetACLTxn): JValue =
        "set-acl" ->
            ("path" -> txn.getPath) ~
                ("acl" -> txn.getAcl.asScala.map({ acl =>
                    ("scheme" -> acl.getId.getScheme) ~ ("id" -> acl.getId.getId) ~ ("perms" -> acl.getPerms)
                })) ~
                ("version" -> txn.getVersion)

    implicit def toJson(implicit txn: CreateSessionTxn): JValue =
        "create-session" -> ("timeout" -> txn.getTimeOut)

    implicit def toJson(implicit txn: ErrorTxn): JValue =
        "error" -> ("errno" -> txn.getErr)

    implicit def toJson(implicit txn: MultiTxn): JValue = {
        val records = txn.getTxns.asScala map { txn =>
            val record = txn.getType match {
                case OpCode.create => new CreateTxn
                case OpCode.createContainer => new CreateContainerTxn
                case OpCode.delete => new DeleteTxn
                case OpCode.setData => new SetDataTxn
                case OpCode.check => new CheckVersionTxn
            }

            record.deserialize(
                BinaryInputArchive.getArchive(
                    new ByteArrayInputStream(txn.getData)), "txn")

            record match {
                case r: CreateTxn => toJson(r)
                case r: CreateContainerTxn => toJson(r)
                case r: DeleteTxn => toJson(r)
                case r: SetDataTxn => toJson(r)
                case r: CheckVersionTxn => toJson(r)
            }
        } toList

        "multi" -> records
    }
}

object JsonSerializerMetrics {
    val SUBSYSTEM: String = "json"
    val records: Counter = Counter.build().subsystem(SUBSYSTEM).name("records").labelNames("type").help("encoded JSON messages").register()
    val size: Summary = Summary.build().subsystem(SUBSYSTEM).name("size").help("size of encoded JSON messages").register()
}

class JsonSerializer(var props: mutable.Map[String, Any] = mutable.Map[String, Any]())
    extends Serializer[LogRecord] with LazyLogging
{
    import JsonSerializerMetrics._

    val PROPERTY_PRETTY = "pretty"

    lazy val printPretty: Boolean = Try(props.get(PROPERTY_PRETTY).toString.toBoolean).getOrElse(false)

    override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {
        props ++= configs.asScala.toMap
    }

    override def serialize(topic: String, log: LogRecord): Array[Byte] = {
        val record: JValue = log.record match {
            case r: CreateTxn => records.labels("create").inc(); toJson(r)
            case r: CreateContainerTxn => records.labels("createContainer").inc(); toJson(r)
            case r: DeleteTxn => records.labels("delete").inc(); toJson(r)
            case r: SetDataTxn => records.labels("setData").inc(); toJson(r)
            case r: CheckVersionTxn => records.labels("checkVersion").inc(); toJson(r)
            case r: SetACLTxn => records.labels("setACL").inc(); toJson(r)
            case r: CreateSessionTxn => records.labels("createSession").inc(); toJson(r)
            case r: ErrorTxn => records.labels("error").inc(); toJson(r)
            case r: MultiTxn => records.labels("multi").inc(); toJson(r)
        }

        val json: JValue = render(
            ("session" -> log.session) ~
                ("cxid" -> log.cxid) ~
                ("zxid" -> log.zxid) ~
                ("time" -> log.time.getMillis) ~
                ("path" -> log.path) ~
                ("type" -> log.opcode.toString) ~
                ("record" -> record)
        )

        val bytes = (if (printPretty) { pretty(json) } else { compact(json) }).getBytes(UTF_8)

        size.observe(bytes.length)

        bytes
    }

    override def close(): Unit = {}
}
