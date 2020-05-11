package com.avs.workflow.domain

import java.util.Date

import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.avs.workflow.bootstrap.{CborSerializable, EventProcessorSettings}

object TasksActor {

  /**
   * Summary of the account state, used in reply messages.
   */
  final case class Summary(task : Task) extends CborSerializable


  sealed trait Confirmation extends CborSerializable
  final case class Accepted(summary: Summary) extends Confirmation
  final case class Rejected(reason: String) extends Confirmation


  //State
  private final case class State(task: Task) extends CborSerializable {

    def hasTasks(taskId: String): Boolean =
    if  (task != null) task.taskId == taskId else false

    def isEmpty: Boolean = task == null

    def addTask(taskId: String,
                name: String,
                taskType: String,
                priority: String,
                assignee: String,
                reporter: String,
                dueDate: Date): State = {
      val updated = Task(taskId, name, taskType,priority,assignee,reporter,dueDate)
      copy(task = updated)
    }

    def updateTask(taskId: String,
                   name: String,
                   taskType: String,
                   priority: String,
                   assignee: String,
                   reporter: String,
                   dueDate: Date): State = {
      //val pos = tasks.indexWhere(x => x.taskId == taskId)
      val updated = Task(taskId, name, taskType,priority,assignee,reporter,dueDate)
      copy(task = updated)
    }

    def removeTask(taskId: String): State = {
      val updated = null
      copy(task = updated)
    }

    def toSummary: Summary =
      Summary(task)
  }
  private object State {
    val empty = State(task = null)
  }

  case class Task(taskId: String,
                  name: String,
                  taskType: String,
                  priority: String,
                  assignee: String,
                  reporter: String,
                  dueDate: Date)

  //Command
  sealed trait Command extends CborSerializable
  final case class AddTask(name: String,
                           taskType: String,
                           priority: String,
                           assignee: String,
                           reporter: String,
                           dueDate: Date,
                           replyTo: ActorRef[Confirmation]) extends Command
  final case class UpdateTask(   name: String,
                                 taskType: String,
                                 priority: String,
                                 assignee: String,
                                 reporter: String,
                                 dueDate: Date,
                                 replyTo: ActorRef[Confirmation]) extends Command
  final case class RemoveTask(name: String,
                                 replyTo: ActorRef[Confirmation]) extends Command
  final case class Get(replyTo: ActorRef[Summary]) extends Command


  //Event
  sealed trait Event extends CborSerializable { def taskId: String }
  final case class TaskAdded(taskId: String,
                             name: String,
                             taskType: String,
                             priority: String,
                             assignee: String,
                             reporter: String,
                             dueDate: Date) extends Event
  final case class TaskUpdated(taskId: String,
                               name: String,
                               taskType: String,
                               priority: String,
                               assignee: String,
                               reporter: String,
                               dueDate: Date) extends Event
  final case class TaskRemoved(taskId: String) extends Event


  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("tasks")

  //Initialization
  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      //val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      TasksActor(entityContext.entityId)
    }.withRole("write-model"))
  }

  def apply(taskId: String): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior
        .withEnforcedReplies[Command, Event, State](
          PersistenceId(EntityKey.name, taskId),
          State.empty,
          (state, command) => handleCommands(taskId, state, command),
          (state, event) => handleEvent(state, event))
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
    }
  }

  private def handleCommands(taskId: String, state: State, command: Command): ReplyEffect[Event, State] =
    command match {
      case AddTask(name,taskType,priority,assignee,reporter,dueDate,replyTo) =>
        if (state.hasTasks(taskId))
          Effect.reply(replyTo)(Rejected(s"Task '$taskId' was already added"))
        else
          Effect
            .persist(TaskAdded(taskId, name,taskType,priority,assignee,reporter,dueDate))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))

      case UpdateTask(name,taskType,priority,assignee,reporter,dueDate,replyTo) =>
        if (state.hasTasks(taskId))
          Effect
            .persist(TaskUpdated(taskId, name,taskType,priority,assignee,reporter,dueDate))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Rejected(s"Task '$taskId' does not exists"))

      case RemoveTask(name,replyTo) =>
        if (state.hasTasks(taskId))
          Effect.persist(TaskRemoved(taskId))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Accepted(state.toSummary)) // removing an item is idempotent

      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }

  private def handleEvent(state: State, event: Event) = {
    event match {
      case TaskAdded(taskId, name,taskType,priority,assignee,reporter,dueDate) => state.addTask(
        taskId, name,taskType,priority,assignee,reporter,dueDate
      )
      case TaskUpdated(taskId, name,taskType,priority,assignee,reporter,dueDate) => state.updateTask(
        taskId, name,taskType,priority,assignee,reporter,dueDate
      )
      case TaskRemoved(taskId)  => state.removeTask(taskId)
    }
  }

}
