package com.avs.workflow.bootstrap

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config


object EventProcessorSettings {

  def apply(system: ActorSystem[_]): EventProcessorSettings = {
    apply(system.settings.config.getConfig("event-processor"))
  }

  def apply(config: Config): EventProcessorSettings = {
    //val keepAliveInterval: FiniteDuration = config.getDuration("keep-alive-interval").toMillis.millis
    //val tagPrefix: String = config.getString("tag-prefix")
    val parallelism: Int = config.getInt("parallelism")
    EventProcessorSettings(parallelism)
  }
}

final case class EventProcessorSettings(parallelism: Int)
