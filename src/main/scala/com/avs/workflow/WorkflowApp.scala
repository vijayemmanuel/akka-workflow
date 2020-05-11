package com.avs.workflow

import java.io.File
import java.util.concurrent.CountDownLatch

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.avs.workflow.bootstrap.GuardianActor
import com.typesafe.config.{Config, ConfigFactory}

/**
 * Main entry point for the application.
 * See the README.md for starting each node with sbt.
 */
object WorkflowApp {

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        // Port on which Akka Cluster runs
        val port = portString.toInt
        // Port on which Http end point runs
        val httpPort = ("80" + portString.takeRight(2)).toInt

        ActorSystem[Nothing](GuardianActor(httpPort), "Workflow", config(port,httpPort))

      case Some("cassandra") =>
        startCassandraDatabase()
        println("Started Cassandra, press Ctrl + C to kill")
        new CountDownLatch(1).await()

      case None =>
        throw new IllegalArgumentException("port number, or cassandra required argument")
    }

    def config(port: Int, httpPort: Int): Config =
      ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $port
      workflow.http.port = $httpPort
       """).withFallback(ConfigFactory.load())

    /**
     * To make the sample easier to run we kickstart a Cassandra instance to
     * act as the journal. Cassandra is a great choice of backend for Akka Persistence but
     * in a real application a pre-existing Cassandra cluster should be used.
     */ 
    def startCassandraDatabase(): Unit = {
      val databaseDirectory = new File("target/cassandra-db")
      CassandraLauncher.start(databaseDirectory, CassandraLauncher.DefaultTestConfigResource, clean = false, port = 9042)
    }
  }



}

