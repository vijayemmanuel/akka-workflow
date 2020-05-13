package com.avs.workflow.utilities

import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, MessageEntity}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.avs.workflow.bootstrap.WorkflowRoutes
import com.avs.workflow.domain.{AccountsActor, TasksActor}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import com.avs.workflow.utilities.Simulation.Trigger

import scala.concurrent.{Future}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Random


object Simulation {

  sealed trait Trigger
  case object Start extends Trigger
  case object Stop extends Trigger
  case class Summary(accountId: String) extends Trigger


  def apply(targetPorts: Seq[Int]) : Behavior[Trigger] = {
    Behaviors.setup ( context =>
      new Simulation(context,targetPorts)
    )
    }
  }
class Simulation(context: ActorContext[Trigger],targetPorts: Seq[Int]) extends
  AbstractBehavior[Trigger](context)
  {
    import Simulation._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import com.avs.workflow.bootstrap.JsonFormats._

    implicit val ec = context.system.executionContext
    implicit val sys = context.system

    require(targetPorts.nonEmpty)

    private def port: Int = Random.shuffle(targetPorts).head
    private val http = Http(context.system)

    private var startTime: Long = System.currentTimeMillis()

    override def onMessage(message: Trigger): Behavior[Trigger] = {
      message match {
        case Start =>
          context.system.log.info(s"Load test start")
          startTime = System.currentTimeMillis()
          run().map(x => context.self ! Summary(x.accountId))
        case Stop =>
          context.stop(context.self)
        case order: Summary =>
          context.system.log.info(s"Order completed in ${System.currentTimeMillis() - startTime} ms")
          context.self ! Start
        //case Status.Failure(ex) =>
         // context.system.log.info(s"Order Failed: ${ex.getMessage}")
      }
      Behaviors.same
    }

  private def run(): Future[Summary] = {
    for {
      accountId <- openAccount().map(_.accountId)
      _ <- getAccount(accountId)
      //_ <- addTask(accountId, "Steak")
      _ <- getAccount(accountId)
      //_ <- addTask(accountId, "Salad")
      _ <- getAccount(accountId)
      //_ <- addTask(accountId, "Milk")
      account <- getAccount(accountId)
    } yield {
      Summary(account.accountId)
    }
  }

  private def openAccount(): Future[Summary] = {
    context.system.log.info(s"Opening account http://localhost:${port}/accounts}")
    for {
      requestEntity <- Marshal(WorkflowRoutes.AddAccount("Account1","Musubs")).to[MessageEntity]
      request = HttpRequest(HttpMethods.POST, s"http://localhost:$port/accounts", entity = requestEntity)
      response <- http.singleRequest(request)
      responseEntity <- response.entity.toStrict(5.seconds)
      account <- Unmarshal(responseEntity).to[AccountsActor.Summary]
    } yield {
      Summary(account.account.accountId)
    }
  }

  private def getAccount(id: String): Future[Summary] = {

    context.system.log.info(s"Getting account http://localhost:$port/accounts/${id}")
    val request = HttpRequest(HttpMethods.GET, s"http://localhost:$port/accounts/${id}")

    for {
      response <- http.singleRequest(request)
      responseEntity <- response.entity.toStrict(5.seconds)
      account <- Unmarshal(responseEntity).to[AccountsActor.Summary]
    } yield {
      Summary(account.account.accountId)
    }
  }

  private def addTask(accountId: String, itemName: String): Future[Summary] = {
    context.system.log.info(s"Adding task http://localhost:$port/accounts/${accountId}/tasks")
    for {
      requestEntity <- Marshal(WorkflowRoutes.AddTask(itemName, "None","vv","ss","ss",new Date("2020-10-27"))).to[MessageEntity]
      request = HttpRequest(HttpMethods.POST, s"http://localhost:$port/accounts/${accountId}/tasks", entity = requestEntity)
      response <- http.singleRequest(request)
      responseEntity <- response.entity.toStrict(5.seconds)
      task <- Unmarshal(responseEntity).to[TasksActor.Summary]
    } yield {
      Summary(task.task.taskId)
    }
  }
}


object LoadTest  extends App {
  // For using classic Scheduler
  import akka.actor.typed.scaladsl.adapter._

  val log = LoggerFactory.getLogger(this.getClass)

  val Opt = """-D(\S+)=(\S+)""".r
  args.toList.foreach {
    case Opt(key, value) =>
      log.info(s"Config Override: $key = $value")
      System.setProperty(key, value)
  }

  val config = ConfigFactory.load("loadtest.conf")

  val ports = config.getIntList("workflow.ports")
    .asScala
    .map(_.intValue())
    .toList

  val testDuration = config.getDuration("load-test.duration", TimeUnit.MILLISECONDS).millis
  val parallelism = config.getInt("load-test.parallelism")
  val rampUpTime = config.getDuration("load-test.ramp-up-time", TimeUnit.MILLISECONDS).millis


  val system = ActorSystem(Guardian(),"guardian")

  system ! Guardian.StartTest

  implicit val ec = system.executionContext
  system.toClassic.scheduler.scheduleOnce(testDuration + 15.seconds) {
    system.terminate()
  }

  object Guardian {

    trait Command
    case object StartTest extends Command

    def apply(): Behavior[Command] = {

      Behaviors.setup { context =>
        context.system.log.info(s"Creating $parallelism simulations")
        val sim = context.spawn(Simulation(ports),"g")

        Behaviors.receiveMessage { message =>
          message match {
            case StartTest => (1 to parallelism).map { _ =>
              Thread.sleep((rampUpTime / parallelism).toMillis)
              sim ! Simulation.Start

              system.toClassic.scheduler.scheduleOnce(testDuration, sim.toClassic, Simulation.Stop)
            }

          }
          Behaviors.same
        }

      }


    }
  }
}

