package com.inu.cluster

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._


/**
  * Created by henry on 4/1/16.
  */
class ClusterMonitor extends  Actor with ActorLogging {

  implicit val system: ActorSystem = context.system
  val cluster = Cluster(system)


  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  override def receive: Receive = {
    case MemberUp(member) =>
      log.info(s"Cluster member up: ${member.address} roles(${member.roles.mkString(",")})")
    case UnreachableMember(member) =>
      log.warning(s"Cluster member unreachable: ${member.address}")
    case _: MemberEvent =>
  }
}
