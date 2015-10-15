package frontend.analysis

import akka.actor.Props
import es.indices.storedQuery
import elastic.ImplicitConversions._
import frontend.PerRequest
import frontend.storedQuery.getRequest.{CollectionJsonBuilder, QueryStoredQueryRequest}
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.SearchHit
import org.json4s.{DefaultFormats, Formats}
import org.json4s.native.JsonMethods._
import spray.http.StatusCodes._
import spray.http.Uri
import spray.http.Uri.Path
import spray.routing.RequestContext
import scala.concurrent.Future
import scala.language.implicitConversions

object Condition {

  def apply(h: SearchHit)(implicit json4sFormats: Formats): Condition = {
    val source = parse(h.sourceAsString())
    val title = (source \ "title").extract[String]
    val query = compact(render(source \ "query"))
    Condition(h.id, title, query, "", Seq.empty)
  }

  def set(conditions: Seq[String]) = Condition("set", "set", "", "set", conditions)
}

case class Condition(storedQueryId: String, title: String, query: String, state: String = "", conditions: Iterable[String], hits: Long = 0) {

  def count(implicit queries: Map[String, Condition], client: Client) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val qb = conditions.flatMap(queries.get).foldLeft(boolQuery()){ (acc, c) => acc.must(wrapperQuery(c.query)) }
    client.prepareCount("logs*")
      .setQuery(qb)
      .execute().asFuture.map { resp =>
      //set hits
      queries.getOrElse(storedQueryId, this).copy(hits = resp.getCount, state = state)
    }
  }
}

class ConditionSet(conditions: Seq[String]) {

  def exclude(storedQueryId: String): Condition =
    Condition(storedQueryId, storedQueryId, "", "excludable", conditions.filterNot(_ == storedQueryId))

  def include(storedQueryId: String): Condition =
    Condition(storedQueryId, storedQueryId, "", "includable", conditions.+:(storedQueryId))

  def all = Condition("set", "set", "", "set", conditions)

}

class FurtherLinks(uri: Uri, storedQueryId: String) {

  def include = append("conditionSet", remove("include", uri.query.toMap)).toSeq
  def exclude = append("include", remove("conditionSet", uri.query.toMap)).toSeq
  def deleteIncludable = remove("include", uri.query.toMap).toSeq

  private def remove(key: String, map: Map[String, String]): Map[String, String] = {
    map.getOrElse(key, storedQueryId).replace(storedQueryId, "").trim match {
      case "" => map.-(key)
      case value => map.+(key -> value)
    }
  }
    //Option(uri.query.toMap.getOrElse(key, storedQueryId).replace(storedQueryId, "").trim).filter(_.nonEmpty).map(key -> _)

  private def append(key: String, map: Map[String, String]):  Map[String, String] =
    s"${map.getOrElse(key, "")} $storedQueryId".trim match {
      case "" => map.-(key)
      case appended =>  map.+(key -> appended)
    }

  def action1(state: String): String = {
    (state match {
      case "includable" => Some(deleteIncludable)
      case _ => None
    }).map { map =>
      s"""{"rel" : "remove", "href" : "${uri.withQuery(map: _*)}", "prompt" : "delete" }"""
    }.getOrElse("")
  }

  def action0(state: String): String = {
    (state match {
      case "excludable" => Some(exclude, "exclude")
      case "includable" => Some(include, "include")
      case _ => None
    }).map { case (map, prompt) =>
      s"""{"rel" : "action", "href" : "${uri.withQuery(map: _*)}", "prompt" : "$prompt" }"""
    }.getOrElse("")
  }
}

object CrossAnalysisRequest {
  def props(conditionSet: Seq[String], include: Seq[String], exclude: Seq[String])(implicit ctx: RequestContext, client: org.elasticsearch.client.Client): Props =
    Props(classOf[CrossAnalysisRequest], ctx, client, conditionSet, include, exclude)
}

object CrossAnalysisSourceRequest {

  import org.elasticsearch.index.query.QueryBuilders._
  import es.indices.storedQuery._

  def props(exclude: Seq[String], bodyBuilder: CollectionJsonBuilder)(queryString: Option[String] = None,
                                   queryTags: Option[String] = None,
                                   size: Int = 10, from: Int = 0)
                                   (implicit ctx: RequestContext, client: org.elasticsearch.client.Client) = {
    println(s"CrossAnalysisSourceRequest.props $exclude")
    Props(classOf[QueryStoredQueryRequest],
      ctx, client,
      bodyBuilder,
      buildQueryDefinition(queryString, queryTags).mustNot(idsQuery().ids(exclude: _*)),
      size, from)
  }

}

case class CrossAnalysisRequest(ctx: RequestContext, implicit val client: org.elasticsearch.client.Client, conditionSet: Seq[String], include: Seq[String], exclude: Seq[String])
  extends PerRequest {

  import storedQuery._
  import scala.collection.JavaConversions._
  import akka.pattern._
  import context.dispatcher
  implicit def json4sFormats: Formats = DefaultFormats
  implicit def seqToSet(value: Seq[String]): ConditionSet = new ConditionSet(value)

  //log.info(s"conditionSet=$conditionSet, include=$include, exclude=$exclude")

  lazy val fetchStoredQueries =
    prepareSearch
      .setQuery(idsQuery(".percolator").addIds(conditionSet ++ include ++ exclude))
      .setFetchSource(Array("query", "title"), null)
      .execute.asFuture
      .map { resp =>
        resp.getHits.foldLeft(Map.empty[String, Condition]){ (acc, h) => acc + (h.id() -> Condition(h))}
      }

  fetchStoredQueries.flatMap { implicit queries =>
      for {
        excluded <- Future.traverse(exclude) { c => conditionSet.exclude(c).count }
        included <- Future.traverse(include) { c => conditionSet.include(c).count }
        set <- conditionSet.all.count
      } yield (excluded ++ included).:+(set)
    } pipeTo self

  def processResult: Receive = {
    case xs: Seq[_] =>
      response {
        requestUri { uri =>
          val items = xs.map { case Condition(storedQueryId, title, _, state, _, hits) =>

            implicit def toLink(id: String): FurtherLinks = new FurtherLinks(uri,id)
            //{ "rel" : "more", "href" : "${uri.withPath(uri.path / "logs")}" },
            val links = (storedQueryId.action0(state) :: storedQueryId.action1(state) :: Nil).filter(_.nonEmpty).mkString(",")
            s"""{
                 | "data" : [ { "name" : "hits", "value" : $hits }, { "name" : "title", "value" : "$title" }, { "name" : "state", "value" : "$state"} ],
                 | "links" : [ $links ]
                 |}""".stripMargin
            }.mkString(",")

          complete(OK, s"""{
                         |  "collection" : {
                         |    "version" : "1.0",
                         |    "href" : "$uri",
                         |    "links" : [
                         |     { "rel" : "source", "href" : "${uri.withPath(uri.path / "source")}" }
                         |    ],
                         |
                         |    "items" : [ $items ]
                         |  }
                         |}""".stripMargin)
        }
      }
    case ex: Exception =>
      response {
        requestUri { uri =>
          log.error(ex, s"$uri")
          complete(InternalServerError,
            s"""{
               |  "error" : {
               |    "message" : "${ex.getMessage}"
               |  }
               |}""".stripMargin)
        }
      }
  }
}
