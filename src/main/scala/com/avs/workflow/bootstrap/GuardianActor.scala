package com.avs.workflow.bootstrap

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.management.scaladsl.AkkaManagement
import com.avs.workflow.domain.{AccountsActor}


/**
 * Root actor bootstrapping the application
 */
object GuardianActor {

  def apply(httpPort: Int): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      val system = context.system
      val settings = EventProcessorSettings(system)

      // Start Akka Management
      AkkaManagement(system).start()

      //Initialise Accounts actor
      AccountsActor.init(system, settings)

      val routes = new WorkflowRoutes(context.system)
      WorkflowHttpServer.start(routes.workflow, httpPort, context.system)

      Behaviors.empty
    }
  }
}


