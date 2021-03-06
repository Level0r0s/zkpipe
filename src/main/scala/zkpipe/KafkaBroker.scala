package zkpipe

import java.io.{Closeable, File}
import java.{lang, util}
import java.util.Properties
import java.util.concurrent.{Semaphore, TimeUnit}

import com.netaporter.uri.Uri
import com.typesafe.scalalogging.LazyLogging
import nl.grons.metrics.scala._
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization._
import com.github.nscala_time.time.StaticDateTime.now
import com.github.nscala_time.time.Imports._

import scala.beans.BeanProperty
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

trait SendResult {
    val record: LogRecord
}

case class KafkaResult(record: LogRecord, metadata: RecordMetadata) extends SendResult

trait Broker extends Closeable {
    def send(log: LogRecord): Future[SendResult]
}

object KafkaBroker extends DefaultInstrumented {
    val SUBSYSTEM: String = "kafka"

    val sendingMessages: Meter = metrics.meter("sending-messages", SUBSYSTEM)
    val sentMessages: Meter = metrics.meter("sent-messages", SUBSYSTEM)
    val sendErrors: Meter = metrics.meter("send-errors", SUBSYSTEM)
    val sendLatency: Timer = metrics.timer("send-latency", SUBSYSTEM)
    val pendingTime: Timer = metrics.timer("pending-time", SUBSYSTEM)
}

class KafkaBroker(val uri: Uri,
                  dryRun: Boolean,
                  valueSerializer: Serializer[LogRecord],
                  sendQueueSize: Option[Int])
    extends JMXExport with KafkaBrokerMBean with Broker with LazyLogging
{
    import KafkaBroker._

    mbean(this)

    val DEFAULT_KAFKA_HOST: String = "localhost"
    val DEFAULT_KAFKA_PORT: Int = 9092
    val DEFAULT_KAFKA_TOPIC: String = "zkpipe"

    require(uri.scheme.contains("kafka"), "Have to starts with kafka://")

    private lazy val props = {
        val props = new Properties()

        val host = uri.host.getOrElse(DEFAULT_KAFKA_HOST)
        val port = uri.port.getOrElse(DEFAULT_KAFKA_PORT)

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, s"$host:$port")

        uri.query.params.foreach { case (key, value) => props.put(key, value.get) }

        props
    }

    @BeanProperty
    lazy val topic: String = (uri.path split File.separator drop 1 headOption) getOrElse DEFAULT_KAFKA_TOPIC

    val sendQueueLock: Option[Semaphore] = sendQueueSize.map(new Semaphore(_))

    private var producerInitialized = false

    lazy val producer: KafkaProducer[lang.Long, LogRecord] = {
        producerInitialized = true

        new KafkaProducer(props, new LongSerializer(), valueSerializer)
    }

    override def close(): Unit = {
        if (producerInitialized) {
            producer.flush()
            producer.close()
        }
    }

    override def send(log: LogRecord): Future[SendResult] = {
        if (dryRun) {
            logger.info(s"skip send transaction in the dry run mode, zxid=${log.zxid}")

            Future.successful(KafkaResult(log, null))
        } else {
            sendQueueLock foreach { lock => pendingTime.time {
                lock.acquire()
            }
            }

            sendingMessages.mark()

            val promise = Promise[SendResult]()
            val startTime = now()

            producer.send(new ProducerRecord(topic, log.zxid, log),
                (metadata: RecordMetadata, exception: Exception) => {
                    sendQueueLock foreach {
                        _.release()
                    }

                    sendLatency.update((startTime to now()).toDurationMillis, TimeUnit.MILLISECONDS)

                    if (exception == null) {
                        sentMessages.mark()

                        promise success KafkaResult(log, metadata)
                    } else {
                        sendErrors.mark()

                        promise failure exception
                    }
                }
            )

            promise.future
        }
    }

    def latestZxid(): Option[Long] = {
        logger.debug(s"try to fetch latest zxid from Kafka topic `$topic`")

        val consumer = new KafkaConsumer[lang.Long, Array[Byte]](props, new LongDeserializer(), new ByteArrayDeserializer())

        val partitions = consumer.partitionsFor(topic).asScala map {
            partitionInfo => new TopicPartition(topic, partitionInfo.partition())
        }

        val endOffsets = consumer.endOffsets(partitions asJava)

        logger.debug(s"found ${partitions.length} partitions: $endOffsets")

        val (partition, offset) = endOffsets.asScala.toSeq.sortBy(_._2).last

        consumer.assign(util.Arrays.asList(partition))
        // offset could be zero for the topic / a partition of the topic
        if (offset >= 1) {
            consumer.seek(partition, offset - 1)
        }

        val records = consumer.poll(1000).records(partition)

        if (records.isEmpty) None else Some(records.asScala.head.key())
    }

    override def getUri: String = uri.toString()
}
