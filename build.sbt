import sbt._

lazy val commonSettings = Seq(
    organization := "com.nexusguard",
    version := "0.1.0",
    scalaVersion := "2.12.1"
)

lazy val root = (project in file("."))
    .settings(commonSettings: _*)
    .settings(
        name := "zkpipe",
        libraryDependencies ++= Seq(
            // async
            "org.scala-lang.modules" %% "scala-async" % "0.9.6",
            // logging
            "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.7",
            "org.apache.logging.log4j" % "log4j-api" % "2.7",
            "org.apache.logging.log4j" % "log4j-core" % "2.7",
            "com.fasterxml.jackson.core" % "jackson-databind" % "2.8.5",
            "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.8.5",
            "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
            // common
            "com.google.guava" % "guava" % "20.0",
            // command line options
            "com.github.scopt" %% "scopt" % "3.5.0",
            // kafka client
            "org.apache.kafka" % "kafka-clients" % "0.10.1.1",
            // zookeeper
            "org.apache.zookeeper" % "zookeeper" % "3.4.9" exclude("org.slf4j", "slf4j-log4j12"),
            // scala glue
            "com.netaporter" %% "scala-uri" % "0.4.16",
            // datetime
            "joda-time" % "joda-time" % "2.9.7",
            "com.github.nscala-time" %% "nscala-time" % "2.16.0"
        ),
        transitiveClassifiers ++= Seq("sources")
    )