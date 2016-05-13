package seed

import java.net.InetAddress

import akka.actor._
import com.typesafe.config.{Config, ConfigFactory}
import PersistenceConfigurator._
import NodeConfigurator._
import akka.cluster.Cluster
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.io.IO
import domain.storedQuery.StoredQueryAggregateRoot
import frontend.ServiceActor
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.transport.InetSocketTransportAddress
import spray.can.Http
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.collection.JavaConversions._

object Main extends App {

  val config: Config = ConfigFactory.load().register().enableCassandraPlugin()

  implicit val system = ActorSystem(config.getString("storedq.cluster-name"), config)

  system.log.info("Configured seed nodes: " + config.getStringList("akka.cluster.seed-nodes").mkString(", "))
  system.actorOf(Props[ClusterMonitor], "cluster-monitor")

  val settings = org.elasticsearch.common.settings.Settings.settingsBuilder()
  .put("cluster.name", config.getString("elasticsearch.cluster-name")).build()
  implicit val client = TransportClient.builder().settings(settings).build()
    .addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(config.getString("elasticsearch.transport-address")), 9300))
  system.actorOf(ClusterSingletonManager.props(
    singletonProps = Props(classOf[StoredQueryAggregateRoot]),
    terminationMessage = PoisonPill,
    settings = ClusterSingletonManagerSettings(system)),
    name = s"${protocol.storedQuery.NameOfAggregate.root}")

  system.actorOf(ClusterSingletonManager.props(
    singletonProps = read.storedQuery.StoredQueryAggregateRootView.props,
    terminationMessage = PoisonPill,
    settings = ClusterSingletonManagerSettings(system)
  ), protocol.storedQuery.NameOfAggregate.view.name)

  system.actorOf(ClusterSingletonProxy.props(
    singletonManagerPath = protocol.storedQuery.NameOfAggregate.root.manager,
    settings = ClusterSingletonProxySettings(system)
  ), name = protocol.storedQuery.NameOfAggregate.root.proxy) ! StoredQueryAggregateRoot.Initial

  system.actorOf(ClusterSingletonProxy.props(
    singletonManagerPath = protocol.storedQuery.NameOfAggregate.view.manager,
    settings = ClusterSingletonProxySettings(system)
  ), protocol.storedQuery.NameOfAggregate.view.proxy)

  implicit val timeout = Timeout(5.seconds)

  val service = system.actorOf(Props(classOf[ServiceActor], client), "service")
  IO(Http) ? Http.Bind(service, interface = "0.0.0.0", port = frontend.Config.port)

  sys.addShutdownHook {
    system.terminate()
  }
}

/*
if(m.hasRole("compute")){
        system.actorOf(ClusterSingletonManager.props(
          singletonProps = Props(classOf[StoredQueryAggregateRoot]),
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(system)),
          name = s"${protocol.storedQuery.NameOfAggregate.root}")
      }

      if(m.hasRole("sync")) {

        system.actorOf(ClusterSingletonManager.props(
          singletonProps = read.storedQuery.StoredQueryAggregateRootView.props,
          terminationMessage = PoisonPill,
          settings = ClusterSingletonManagerSettings(system)
        ), protocol.storedQuery.NameOfAggregate.view.name)

      }

      if(m.hasRole("web")) {
        system.actorOf(ClusterSingletonProxy.props(
          singletonManagerPath = protocol.storedQuery.NameOfAggregate.root.manager,
          settings = ClusterSingletonProxySettings(system)
        ), name = protocol.storedQuery.NameOfAggregate.root.proxy) ! StoredQueryAggregateRoot.Initial

        system.actorOf(ClusterSingletonProxy.props(
          singletonManagerPath = protocol.storedQuery.NameOfAggregate.view.manager,
          settings = ClusterSingletonProxySettings(system)
        ), protocol.storedQuery.NameOfAggregate.view.proxy)

        val service = system.actorOf(Props(classOf[ServiceActor], client), "service")
        IO(Http) ? Http.Bind(service, interface = "0.0.0.0", port = frontend.Config.port)
      }
 */
