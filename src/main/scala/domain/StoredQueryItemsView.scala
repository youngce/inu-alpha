package domain

import akka.actor._
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.persistence.PersistentView
import akka.util.Timeout
import com.sksamuel.elastic4s.DefinitionAttributes.{DefinitionAttributeBoost, DefinitionAttributeRewrite}
import com.sksamuel.elastic4s.QueryDefinition
import scala.collection.JavaConversions._
import scala.concurrent.duration._

object StoredQueryItemsView {

  import StoredQueryAggregateRoot.{BoolClause, StoredQuery}

  val storedQueryItemsViewSingleton = "/user/stored-query-items-view/active"

  case class Query(text: Option[String], tags: Option[String]) {
    def asQueryDefinitions = {
      import com.sksamuel.elastic4s.ElasticDsl._
      List(
        text.map { queryStringQuery(_) asfields "_all" },
        tags.map { matchQuery("tags", _).operator("or") }
      ).flatten
    }
  }

  case class GetStoredQuery(id: String)

  case class StoredQueryItem(title: String, tags: Option[String], status: Option[String]) {
    require( title.nonEmpty )
  }

  case class StoredQueryResponse(entity: Option[StoredQuery])

  case class QueryResponse(items: Set[(String, StoredQueryItem)], tags: Set[String])

  case class GetItem(id: String)

  case class GetItemClauses(id: String, occurrence: String)

  case class ItemDetailResponse(id: String, item: StoredQueryItem)

  case class ItemClausesResponse(clauses: Map[Int, BoolClause])

  case class ItemNotFound(id: String)

  object StoredQueryItem {
    import com.sksamuel.elastic4s.RichSearchHit
    def apply(h: RichSearchHit) = {
      val Some(x) = extract(h)
      x
    }

    import com.sksamuel.elastic4s.RichSearchHit
    def extract(h: RichSearchHit): Option[StoredQueryItem] = try {
      Some(StoredQueryItem(
        title = h.field("title").value[String],
        tags = h.fieldOpt("tags").map { _.values().mkString(" ") },
        status = Some("enabled")
      ))
    } catch {
      case ex: Exception => None
    }
  }
}

class StoredQueryItemsView extends PersistentView with util.ImplicitActorLogging with elastics.PercolatorIndex {

  override val viewId: String = "stored-query-aggregate-root-view"

  import StoredQueryAggregateRoot._
  import StoredQueryItemsView._
  import akka.pattern._
  override val persistenceId: String = "stored-query-aggregate-root"

  var items: Map[String, StoredQuery] = Map(temporaryId -> StoredQuery(temporaryId, "temporary"))
  var queryResp = QueryResponse(Set.empty, Set.empty)

  import context.dispatcher

  ClusterReceptionistExtension(context.system).registerService(self)

  def receive: Receive = {
    case ItemCreated(entity, dp) if isPersistent =>
      updateState (entity.id -> entity)

    case ItemsChanged(xs, _ , _) =>
      updateState(xs: _*)

    case GetItem(id) =>
      sender ! (for {
        StoredQuery(_, title, _, tags) <- items.get(id)
        item = StoredQueryItem(title, Some(tags.mkString(" ")), Some("enabled"))
      } yield ItemDetailResponse(id, item)).getOrElse(ItemNotFound(id))

    case GetItemClauses(id, occurrence) =>
      sender ! (for {
        StoredQuery(id, _, clauses, _) <- items.get(id)
        filtered = clauses.filter { case (_, clause) => clause.occurrence == occurrence }
      } yield ItemClausesResponse(filtered)).getOrElse(ItemNotFound(id))

    case ChangesRegistered(records) =>
      log.info(s"$records were registered.")

    case q: Query =>
      import com.sksamuel.elastic4s.ElasticDsl._

      implicit val timeout = Timeout(5.seconds)

      `GET inu-percolate/.percolator/_search`(q.asQueryDefinitions).map {
        resp => queryResp.copy(items = resp.hits.map { h => h.id -> StoredQueryItem(h) }.toSet)
      }.recover { case ex =>
        ex.logError()
        queryResp } pipeTo sender()

    case GetStoredQuery(id) =>
      sender() ! StoredQueryResponse(items.get(id).map { loadNamedBoolClauseDependencies(_, items) })

  }

  private def updateState(values: (String, StoredQuery)*) = {
    items = items ++ values
    queryResp = queryResp.copy(tags = accumulateItemsTags )
  }

  def accumulateItemsTags = {
    items.values.map { _.tags }.foldLeft(Set.empty[String]){ _ ++ _ }
  }
}
