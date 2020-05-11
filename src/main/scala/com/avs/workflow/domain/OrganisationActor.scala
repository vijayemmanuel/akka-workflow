package com.avs.workflow.domain

import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.cluster.typed.Cluster
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.avs.workflow.bootstrap.{CborSerializable, EventProcessorSettings}

object OrganisationActor {

  /**
   * Summary of the shopping cart state, used in reply messages.
   */
  final case class Summary(projects : Seq[WorkflowProject]) extends CborSerializable

  sealed trait Confirmation extends CborSerializable
  final case class Accepted(summary: Summary) extends Confirmation
  final case class Rejected(reason: String) extends Confirmation


  //State
  final case class State(projects: Seq[WorkflowProject]) extends CborSerializable {

    def hasProject(projectId: String): Boolean =
      projects.map(x => x.projectId).contains(projectId)

    def isEmpty: Boolean =
      projects.isEmpty

    def addProject(projectId: String, task: WorkflowTask): State = {
      val updated = projects :+ WorkflowProject(projectId,task)
      copy(projects = updated)
    }

    def updateProject(projectId: String, task: WorkflowTask): State = {
      val pos = projects.indexWhere(x => x.projectId == projectId)
      val updated = projects.updated(pos,WorkflowProject(projectId,task))
      copy(projects = updated)
    }

    def removeProject(projectId: String): State = {
      val updated = projects.filterNot(x => x.projectId == projectId)
      copy(projects = updated)
    }

    def toSummary: Summary =
      Summary(projects)
  }
  object State {
    val empty = State(projects = Seq.empty)
  }

  case class WorkflowTask(task : Map[String,String])
  case class WorkflowProject(projectId: String, tasks: WorkflowTask)

  //Command
  sealed trait Command extends CborSerializable
  final case class AddProject(projectId: String, tasks: WorkflowTask, replyTo: ActorRef[Confirmation]) extends Command
  final case class UpdateProject(projectId: String, tasks: WorkflowTask, replyTo: ActorRef[Confirmation]) extends Command
  final case class RemoveProject(projectId: String, replyTo: ActorRef[Confirmation]) extends Command
  final case class Get(replyTo: ActorRef[Summary]) extends Command


  //Event
  sealed trait Event extends CborSerializable { def orgId: String }
  final case class ProjectAdded(orgId: String, projectId: String, tasks: WorkflowTask) extends Event
  final case class ProjectUpdated(orgId: String, projectId: String, tasks: WorkflowTask) extends Event
  final case class ProjectRemoved(orgId: String, projectId: String) extends Event


  val EntityKey: EntityTypeKey[Command] = EntityTypeKey[Command]("organisation")

  //Initialization
  def init(system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    ClusterSharding(system).init(Entity(EntityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      //val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      OrganisationActor(entityContext.entityId)
    }.withRole("write-model"))
  }

  def apply(orgId: String): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior
        .withEnforcedReplies[Command, Event, State](
          PersistenceId(EntityKey.name, orgId),
          State.empty,
          (state, command) => openOrganisation(orgId, state, command),
          (state, event) => handleEvent(state, event))
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
        .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
    }
  }

  private def openOrganisation(orgId: String, state: State, command: Command): ReplyEffect[Event, State] =
    command match {
      case AddProject(projectId, tasks, replyTo) =>
        if (state.hasProject(projectId))
          Effect.reply(replyTo)(Rejected(s"Item '$projectId' was already added to this organisation"))
        else
          Effect
            .persist(ProjectAdded(orgId, projectId, tasks))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))

      case UpdateProject(projectId, tasks, replyTo) =>
        if (state.hasProject(projectId))
          Effect
            .persist(ProjectUpdated(orgId, projectId, tasks))
            .thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Rejected(s"Item '$projectId' does not exists in this organisation"))

      case RemoveProject(projectId, replyTo) =>
        if (state.hasProject(projectId))
          Effect.persist(ProjectRemoved(orgId, projectId)).thenReply(replyTo)(updatedOrg => Accepted(updatedOrg.toSummary))
        else
          Effect.reply(replyTo)(Accepted(state.toSummary)) // removing an item is idempotent

      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }

  private def handleEvent(state: State, event: Event) = {
    event match {
      case ProjectAdded(_, projectId, tasks)    => state.addProject(projectId, tasks)
      case ProjectUpdated(_, projectId, tasks)    => state.updateProject(projectId, tasks)
      case ProjectRemoved(_, projectId)  => state.removeProject(projectId)
    }
  }

}
