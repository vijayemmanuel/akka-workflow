package com.avs.workflow.domain

import java.util.Date

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}

import scala.concurrent.duration._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, onSuccess}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import akka.util.Timeout
import com.avs.workflow.bootstrap.{CborSerializable, EventProcessorSettings}

import scala.concurrent.Future

object AccountsActor {

  /**
   * Summary of the account state, used in reply messages.
   */
  final case class Summary(account : Account, tasks: Seq[String]) extends CborSerializable

  sealed trait Confirmation extends CborSerializable
  final case class Accepted(summary: Summary) extends Confirmation
  final case class Rejected(reason: String) extends Confirmation


  //State
  final case class State(account: Account, tasks : Seq[String]) extends CborSerializable {

    def hasAccount(accountId: String): Boolean =
      if(account != null)
      account.accountId == accountId
      else false

    def isEmpty: Boolean = account == null

    def addAccount(accountId: String, name: String, subscription: String): State = {
      val updated = Account(accountId,name, subscription)
      copy(account = updated)
    }

    def addTasktoAccount(taskId: String): State = {
      val updated = tasks :+ taskId
      copy(tasks = updated)
    }

    def updateAccount(name: String, subscription: String): State = {
      //val pos = accounts.indexWhere(x => x.accountId == accountId)
      val updated = Account(account.accountId,name, subscription)
      copy(account = updated)
    }

    def removeAccount(accountId: String): State = {
      //val updated = accounts.filterNot(x => x.accountId == accountId)
      copy(account = null)
    }
    def removeTaskFromAccount(taskId: String): State = {
      val updated = tasks.filterNot(x => x == taskId)
      copy(tasks = updated)
    }

    def toSummary: Summary =
      Summary(account,tasks)
  }
  object State {
    val empty = State(account = null,tasks = Seq.empty)
  }

  //case class WorkflowTask(task : Map[String,String])
  case class Account(accountId: String, name: String, subscription: String)

  //Command
  sealed trait Command extends CborSerializable
  final case class AddAccount(name: String, subscription: String, replyTo: ActorRef[Confirmation]) extends Command
  final case class UpdateAccount(name: String, subscription: String, replyTo: ActorRef[Confirmation]) extends Command
  final case class RemoveAccount(name: String, replyTo: ActorRef[Confirmation]) extends Command
  final case class Get(replyTo: ActorRef[Summary]) extends Command
  final case class AddTaskToAccount(taskId: String,
                                    name: String,
                                    taskType: String,
                                    priority: String,
                                    assignee: String,
                                    reporter: String,
                                    dueDate: Date,
                                    replyTo: ActorRef[Confirmation]) extends Command
  final case class RemoveTaskFromAccount(taskId: String,
                                         replyTo: ActorRef[Confirmation]) extends Command


  //Event
  sealed trait Event extends CborSerializable { def accountId: String }
  final case class AccountAdded(accountId: String, name: String, subscription: String) extends Event
  final case class AccountUpdated(accountId: String, name: String, subscription: String) extends Event
  final case class AccountRemoved(accountId: String, name: String) extends Event
  final case class TaskAddedToAccount(accountId: String, taskId: String) extends  Event
  final case class TaskRemovedFromAccount(accountId: String, taskId: String) extends  Event


  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("accounts")

  //Initialization
  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      //val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      AccountsActor(entityContext.entityId)
    }.withRole("write-model"))
  }

  def apply(accountId: String): Behavior[Command] = {
    Behaviors.setup { context =>

      val settings = EventProcessorSettings(context.system)

      //Initialise Task actor
      TasksActor.init(context.system, settings)

      EventSourcedBehavior
        .withEnforcedReplies[Command, Event, State](
          PersistenceId(EntityKey.name, accountId),
          State.empty,
          (state, command) => handleCommands(accountId, state, command, context),
          (state, event) => handleEvent(state, event))
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
    }
  }

  private def handleCommands(accountId: String, state: State, command: Command, context : ActorContext[Command]): ReplyEffect[Event, State] = {

    implicit  val timeout: Timeout = Timeout.create(context.system.settings.config.getDuration("workflow.askTimeout"))

    command match {
      case AddAccount(name, subscription, replyTo) =>
        if (state.hasAccount(accountId))
          Effect.reply(replyTo)(Rejected(s"Account '$accountId' was already added"))
        else
          Effect
            .persist(AccountAdded(accountId, name, subscription))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))

      case UpdateAccount(name, subscription, replyTo) =>
        if (state.hasAccount(accountId))
          Effect
            .persist(AccountUpdated(accountId, name, subscription))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Rejected(s"Account '$accountId' does not exists"))

      case RemoveAccount(name, replyTo) =>
        if (state.hasAccount(accountId))
          Effect.persist(AccountRemoved(accountId, name))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Accepted(state.toSummary)) // removing an item is idempotent

      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)

      case AddTaskToAccount(taskId, name, taskType, priority, assignee, reporter, dueDate, replyTo) =>
        //implicit val ctx = context.system.executionContext
        if (state.hasAccount(accountId)) {
          val entityRef = ClusterSharding(context.system).entityRefFor(TasksActor.EntityKey, taskId)
          val reply:Future[TasksActor.Confirmation] = entityRef.ask(TasksActor.AddTask(name, taskType, priority, assignee, reporter, dueDate,_))
          //reply.map {
           // case TasksActor.Accepted(summary) =>
              Effect.persist(TaskAddedToAccount(accountId,taskId))
                .thenReply(replyTo) (state => Accepted(state.toSummary))
          //  case TasksActor.Rejected(reason) => Effect.reply(replyTo)(Rejected(reason))
         // }
        }
        else
          Effect.reply(replyTo)(Rejected(s"Account '$accountId' does not exists"))

      case RemoveTaskFromAccount(taskId, replyTo) =>
        if (state.hasAccount(accountId)) {
          Effect.persist(TaskRemovedFromAccount(accountId,taskId))
            .thenReply(replyTo) (state => Accepted(state.toSummary))
        }
        else
          Effect.reply(replyTo)(Rejected(s"Account '$accountId' does not exists for task removal"))
    }
  }

  private def handleEvent(state: State, event: Event) = {
    event match {
      case AccountAdded(accountId, name, subscription)    => state.addAccount(accountId, name, subscription)
      case AccountUpdated(_, name, subscription)    => state.updateAccount(name, subscription)
      case AccountRemoved(accountId, _)  => state.removeAccount(accountId)
      case TaskAddedToAccount(_, taskId) => state.addTasktoAccount(taskId)
      case TaskRemovedFromAccount(_, taskId) => state.removeTaskFromAccount(taskId)
    }
  }

}
