package com.avs.workflow.bootstrap


import java.util.Date

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.util.Timeout
import com.avs.workflow.bootstrap.WorkflowRoutes.{AddAccount, AddTask, DeleteTask, UpdateAccount, UpdateTask}
import com.avs.workflow.domain.{AccountsActor, TasksActor}
import spray.json.DeserializationException

import scala.concurrent.Future
import scala.util.Try

object WorkflowRoutes {
  final case class AddAccount(name: String, subscription: String)
  final case class UpdateAccount(accountId: String, name: String, subscription: String)
  final case class AddTask(name: String,
                           taskType: String,
                           priority: String,
                           assignee: String,
                           reporter: String,
                           dueDate: Date)
  final case class UpdateTask(taskId: String,
                               name: String,
                               taskType: String,
                               priority: String,
                               assignee: String,
                               reporter: String,
                               dueDate: Date)
  final case class DeleteTask(taskId: String)
}

/**
 * HTTP API for
 * 1. Receiving requests from front end
 */

final class WorkflowRoutes (system: ActorSystem[_]) {

  implicit private val timeout: Timeout = Timeout.create(system.settings.config.getDuration("workflow.askTimeout"))

  private val sharding = ClusterSharding(system)

  //import WorkflowRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  import JsonFormats._

  private def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case ex: DeserializationException =>
      complete(HttpResponse(StatusCodes.InternalServerError,entity = ex.getMessage))
    case ex =>
      complete(HttpResponse(StatusCodes.InternalServerError, entity = ex.getMessage))
  }

  def workflow: Route =
    handleExceptions(exceptionHandler) {
      concat(
        pathPrefix("accounts") {
          concat(
            post {
              val newAccountId = java.util.UUID.randomUUID.toString

              entity(as[AddAccount]) { data =>
                val entityRef = sharding.entityRefFor(AccountsActor.EntityKey, newAccountId)
                val reply: Future[AccountsActor.Confirmation] =
                  entityRef.ask(AccountsActor.AddAccount(data.name, data.subscription, _))
                onSuccess(reply) {
                  case AccountsActor.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case AccountsActor.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
              }
            },
            put {
              entity(as[UpdateAccount]) { data =>
                val entityRef = sharding.entityRefFor(AccountsActor.EntityKey, data.accountId)
                val reply: Future[AccountsActor.Confirmation] =
                  entityRef.ask(AccountsActor.UpdateAccount(data.name, data.subscription, _))
                onSuccess(reply) {
                  case AccountsActor.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case AccountsActor.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
              }
            },
            path(JavaUUID) { accountId =>
              get {
                val entityRef = sharding.entityRefFor(AccountsActor.EntityKey, accountId.toString)
                // Explore Why Get does not take the replyTo Actor Ref?
                onSuccess(entityRef.ask(AccountsActor.Get)) { summary =>
                  if (summary.account == null) complete(StatusCodes.NotFound)
                  else complete(summary)
                }
              }
            },
            pathPrefix(JavaUUID / "tasks") { accountId =>
              concat(
                post {
                  val newTaskId = java.util.UUID.randomUUID.toString

                  entity(as[AddTask]) { data =>
                    val entityRef = sharding.entityRefFor(AccountsActor.EntityKey, accountId.toString)
                    val reply: Future[AccountsActor.Confirmation] =
                      entityRef.ask(AccountsActor.AddTaskToAccount(newTaskId, data.name, data.taskType, data.priority, data.assignee, data.reporter, data.dueDate, _))
                    onSuccess(reply) {
                      case AccountsActor.Accepted(summary) =>
                        complete(StatusCodes.OK -> summary)
                      case AccountsActor.Rejected(reason) =>
                        complete(StatusCodes.BadRequest, reason)
                    }
                  }
                },
                put {
                  entity(as[UpdateTask]) { data =>
                    val entityRef = sharding.entityRefFor(TasksActor.EntityKey, data.taskId)
                    val reply: Future[TasksActor.Confirmation] =
                      entityRef.ask(TasksActor.UpdateTask(data.name, data.taskType, data.priority, data.assignee, data.reporter, data.dueDate, _))
                    onSuccess(reply) {
                      case TasksActor.Accepted(summary) =>
                        complete(StatusCodes.OK -> summary)
                      case TasksActor.Rejected(reason) =>
                        complete(StatusCodes.BadRequest, reason)
                    }
                  }
                },
                delete {
                  entity(as[DeleteTask]) { data =>
                    val entityRef = sharding.entityRefFor(AccountsActor.EntityKey, accountId.toString)
                    val reply: Future[AccountsActor.Confirmation] =
                      entityRef.ask(AccountsActor.RemoveTaskFromAccount(data.taskId, _))
                    onSuccess(reply) {
                      case AccountsActor.Accepted(summary) =>
                        complete(StatusCodes.OK -> summary)
                      case AccountsActor.Rejected(reason) =>
                        complete(StatusCodes.BadRequest, reason)
                    }
                  }
                },
                path(JavaUUID) { taskId =>
                  get {
                    val entityRef = sharding.entityRefFor(TasksActor.EntityKey, taskId.toString)
                    // Explore Why Get does not take the replyTo Actor Ref?
                    onSuccess(entityRef.ask(TasksActor.Get)) { summary =>
                      if (summary.task == null) complete(StatusCodes.NotFound)
                      else complete(summary)
                    }
                  }
                }
              )
            }
          )
        }
      )
    }

}

object JsonFormats {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._
  import spray.json.deserializationError
  import java.text.SimpleDateFormat
  import spray.json.{JsString, JsValue, JsonFormat}


  implicit object DateFormat extends JsonFormat[Date] {
    def write(date: Date) = JsString(dateToIsoString(date))
    def read(json: JsValue) = json match {
      case JsString(rawDate) =>
        parseIsoDateString(rawDate).fold(deserializationError(s"Expected ISO Date format, got $rawDate"))(identity)
      case error => deserializationError(s"Expected JsString, got $error")
    }
  }

  private val localIsoDateFormatter = new ThreadLocal[SimpleDateFormat] {
    override def initialValue() = new SimpleDateFormat("yyyy-MM-dd")
  }

  private def dateToIsoString(date: Date) =
    localIsoDateFormatter.get().format(date)

  private def parseIsoDateString(date: String): Option[Date] =
    Try{ localIsoDateFormatter.get().parse(date) }.toOption

  implicit val accountFormat: RootJsonFormat[AccountsActor.Account] = jsonFormat3(AccountsActor.Account)
  implicit val taskFormat: RootJsonFormat[TasksActor.Task] = jsonFormat7(TasksActor.Task)

  implicit val accountSummaryFormat: RootJsonFormat[AccountsActor.Summary] = jsonFormat2(AccountsActor.Summary)
  implicit val taskSummaryFormat: RootJsonFormat[TasksActor.Summary] = jsonFormat1(TasksActor.Summary)

  implicit val addAccountFormat: RootJsonFormat[WorkflowRoutes.AddAccount] = jsonFormat2(WorkflowRoutes.AddAccount)
  implicit val updateAccountFormat: RootJsonFormat[WorkflowRoutes.UpdateAccount] = jsonFormat3(WorkflowRoutes.UpdateAccount)
  implicit val addTaskFormat: RootJsonFormat[WorkflowRoutes.AddTask] = jsonFormat6(WorkflowRoutes.AddTask)
  implicit val updateTaskFormat: RootJsonFormat[WorkflowRoutes.UpdateTask] = jsonFormat7(WorkflowRoutes.UpdateTask)
  implicit val deleteTaskFormat: RootJsonFormat[WorkflowRoutes.DeleteTask] = jsonFormat1(WorkflowRoutes.DeleteTask)


}
