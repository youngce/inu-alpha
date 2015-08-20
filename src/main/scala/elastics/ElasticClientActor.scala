package elastics

import akka.actor.Actor
import com.sksamuel.elastic4s.ElasticClient
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse
import org.elasticsearch.action.support.master.AcknowledgedResponse

import scala.concurrent.Future
import scala.util.{Failure, Success}

object ElasticClientActor {
  case object Install
}

class ElasticClientActor extends Actor
  with LteTemplate
  with PercolatorIndex
  with AnalyzersIndex
  with util.ImplicitActorLogging {

  import ElasticClientActor._

  import context.dispatcher

  def receive = {
    case Install =>

      val tasks = Seq(
        `PUT _template/lte`,
        `PUT inu-percolate`
          .flatMap { _ => `PUT inu-percolate/_mapping/.percolator`}
          .flatMap { _ => putInuPercolateMapping("cht", "whitespace")}
          .flatMap { _ => putInuPercolateMapping("ytx", "ik_stt_analyzer")}
      )

      Future.traverse(tasks) { task => task }.onComplete {
        case Success(results) =>
          results.foreach {
            case (name, resp:AcknowledgedResponse) =>
              log.info(s"$name acknowledged ${resp.isAcknowledged}")
            case (name, resp) => log.info(s"$name $resp")
          }
        case Failure(ex) => ex.logError(e=> s"$e")

      }

  }
}
