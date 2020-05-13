enablePlugins(Cinnamon)

val AkkaVersion = "2.6.4"
val AkkaHttpVersion = "10.1.11"
val AkkaPersistenceCassandraVersion = "1.0.0-RC1"
val LogbackVersion = "1.2.3"
val AkkaManagementVersion = "1.0.6"

lazy val buildSettings = Seq(
  organization := "com.avs.workflow",
  scalaVersion := "2.13.2",
  version := "0.1"
)

parallelExecution in ThisBuild := false

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Ywarn-unused:imports",
  "-encoding", "UTF-8"
)

lazy val commonJavacOptions = Seq(
  "-Xlint:unchecked",
  "-Xlint:deprecation"
)

lazy val commonSettings = Seq(
  Compile / scalacOptions ++= commonScalacOptions,
  Compile / javacOptions ++= commonJavacOptions,
  run / javaOptions ++= Seq("-Xms128m", "-Xmx1024m")
  )


lazy val `workflow-app` = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    mainClass in (Compile, run) := Some("com.avs.workflow.WorkflowApp"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.typesafe.akka" %% "akka-distributed-data" % AkkaVersion,

      //Persistence
      "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,

      // Http
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

      // Management
      "com.lightbend.akka.management" %% "akka-management" % AkkaManagementVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % AkkaManagementVersion,

      //Logback
      "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,

      // Telemetry
      Cinnamon.library.cinnamonAkka,
      Cinnamon.library.cinnamonAkkaHttp,
      Cinnamon.library.cinnamonJvmMetricsProducer,
      Cinnamon.library.cinnamonPrometheus,
      Cinnamon.library.cinnamonPrometheusHttpServer

    )
  )


cinnamon in run := true
cinnamon in test := true
