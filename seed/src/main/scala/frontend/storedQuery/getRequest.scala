package frontend.storedQuery.getRequest

import akka.actor.Props
import akka.pattern._
import akka.util.Timeout
import elastic.ImplicitConversions._
import es.indices.logs.VttField
import es.indices.{logs, storedQuery}
import es.indices.storedQuery._
import frontend.CollectionJsonSupport.`application/vnd.collection+json`
import frontend.{Pagination, PerRequest}
import org.elasticsearch.action.count.CountResponse
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.search.{SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import org.json4s
import org.json4s.JsonAST.JValue
import org.json4s._
import org.json4s.native.JsonMethods._
import protocol.storedQuery.Exchange.{MatchClause, NamedClause, SpanNearClause}
import protocol.storedQuery.Terminology._
import spray.http.StatusCodes._
import spray.http.Uri.Path
import spray.routing._
import text.ImplicitConversions._

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps, reflectiveCalls}

trait CollectionJsonBuilder {
  def body(hits: Iterable[json4s.JValue], tags: String, pagination: Seq[String]): String

  def itemsMap(hits: Iterable[json4s.JValue]) = hits.map(extract)
  private def extract(hit: json4s.JValue): Option[(String, String)] = {
    implicit val formats = org.json4s.DefaultFormats
    hit match {
      case o: JObject =>
        (o \ "id").extractOpt[String].map { id =>
          val data = compact(render(o \ "data"))
          Some((id, data))
        }.getOrElse(None)
      case _ => None
    }
  }
}

object GetStoredQueryDetailRequest {
  def props(occur: String)(implicit ctx: RequestContext, client: org.elasticsearch.client.Client, storedQueryId: String) =
    Props(classOf[GetStoredQueryDetailRequest], ctx, client, storedQueryId, occur)
}

case class GetStoredQueryDetailRequest(ctx: RequestContext, implicit val client: org.elasticsearch.client.Client ,storedQueryId: String, occur: String) extends PerRequest {

  import context.dispatcher
  import storedQuery._

  lazy val getItemDetail =
    prepareGet(storedQueryId)
      .setFetchSource(Array(s"occurs.$occur"), null)
      .setTransformSource(true)

  getItemDetail.execute().asFuture.map {
    case r: GetResponse => r.getSourceAsString
  }.recover { case _ => """{ "error": { } }""" } pipeTo self

  def processResult: Receive = {
    case json: String =>
      response {
        requestUri { uri =>
          respondWithMediaType(`application/vnd.collection+json`) {
            val items = json match {
              case "{}" => "[]"
              case _ => compact(render(parse(json) \\ occur)) richFormat Map("uri" -> s"""\\/$occur$$""".r.replaceAllIn(s"$uri", ""))
            }

            complete(OK,
              s"""{
                 |  "collection" : {
                 |    "version": "1.0",
                 |    "href" : "${uri}",
                 |
                 |    "items" : $items
                 |  }
                 |}
               """.stripMargin)
          }
        }
      }
  }
}

object GetStoredQueryRequest {
  def props(implicit ctx: RequestContext, client: org.elasticsearch.client.Client,  storedQueryId: String) =
    Props(classOf[GetStoredQueryRequest], ctx, client: org.elasticsearch.client.Client,  storedQueryId)
}

case class GetStoredQueryRequest(ctx: RequestContext, implicit val client: org.elasticsearch.client.Client,  storedQueryId: String) extends PerRequest {

  import context.dispatcher
  import storedQuery._
  implicit val formats = org.json4s.DefaultFormats

  lazy val getItem =
    prepareGet(storedQueryId)
      .setFetchSource(Array("item"), null)
      .setTransformSource(true)


  getItem.execute().asFuture.map {
    case r: GetResponse => r.getSourceAsString
  }.recover { case _ => """{ "error": { } }""" } pipeTo self

  def processResult: Receive = {
    case json: String =>
      response {
        URI( href =>  {
          respondWithMediaType(`application/vnd.collection+json`) {
            complete(OK, itemRepresentation(parse(json), href))
          }
        })
      }
  }

  def itemRepresentation(json: JValue, href: java.net.URI)(implicit formats: Formats): String = {

    val data = json \ "item" \ "data"

    val prompts = Map("title" -> "模型名稱", "tags" -> "模型組")

    val template = data.extractOpt[List[JObject]].map(_.map {
      case o@JObject(("name", JString(name)) :: ("value", _) :: Nil) if prompts.contains(name) =>
        o merge JObject(("prompt", JString(prompts(name))))
      case o: JObject =>
        o merge JObject(("prompt", JString("")))
    }).map{ d => compact(render(JArray(d)))}.getOrElse("[]")

    s"""{
       | "collection" : {
       |   "version" : "1.0",
       |   "href" : "",
       |
       |   "items" : [
       |     {
       |       "href" : "${href}",
       |       "data" : ${compact(render(data))},
       |
       |       "links" : [
       |        { "rel" : "preview",
       |          "name" : "preview",
       |          "href" : "$href/preview",
       |          "data" : [
       |            { "name" : "size", "prompt" : "size of displayed logs" },
       |            { "name" : "from", "prompt" : "logs display from" }
       |          ]
       |        },
       |        ${Occurrences.map(n => s"""{ "rel" : "section", "name" : "$n", "href" : "$href/$n" }""").mkString(",")},
       |        ${BoolQueryClauses.map(n => s"""{ "rel" : "edit", "href" : "$href/$n" }""").mkString(",")}
       |       ]
       |     }
       |   ],
       |
       |   "template" : {
       |      "data" : ${template}
       |   }
       | }
       |}""".stripMargin
  }
}

object QueryStoredQueryRequest {
  def props(bodyBuilder: CollectionJsonBuilder,
            queryString: Option[String] = None, queryTags: Option[String] = None,
            size: Int = 10, from: Int = 0)(implicit ctx: RequestContext, client: org.elasticsearch.client.Client) =
    Props(classOf[QueryStoredQueryRequest], ctx, client, bodyBuilder, buildQueryDefinition(queryString, queryTags), size, from)
}

case class QueryStoredQueryRequest(ctx: RequestContext, implicit val client: Client,
                                   bodyBuilder: CollectionJsonBuilder,
                                   qb: QueryBuilder,
                                   size: Int, from: Int) extends PerRequest {

  import Pagination._
  import context.dispatcher
  import storedQuery._

  implicit val timeout = Timeout(5 seconds)

  val getTags = (context.actorSelection(protocol.storedQuery.NameOfAggregate.view.client) ? protocol.storedQuery.Exchange.SearchTags)
    .map {
      case r: common.StringMapHolder => r.text
      case unexpected =>
        log.warning(s"SearchTags error with an unexpected Tags: $unexpected")
        ""
    }

  (for {
    tags <- getTags
    searchResponse <- prepareSearch
      .setQuery(qb)
      .setFetchSource(Array("item"), null)
      .setSize(size).setFrom(from)
      .execute()
      .asFuture
  } yield (searchResponse, tags)) pipeTo self

  def processResult: Receive = {
    case (r: SearchResponse ,tags: String) =>
      response {
        requestUri(implicit uri =>  {
          respondWithMediaType(`application/vnd.collection+json`) {
            val hits = r.getHits.map { h => parse(h.sourceAsString()) \ "item" }
            complete(OK, bodyBuilder.body(hits, tags, Pagination(size, from, r).links))
          }
        })
      }
  }
}

object GetClauseTemplateRequest {
  def props(implicit ctx: RequestContext) =
    Props(classOf[GetClauseTemplateRequest], ctx)
}

case class GetClauseTemplateRequest(ctx: RequestContext) extends PerRequest {

  def sample(clause: String): JValue = {
    import protocol.storedQuery.ImplicitJsonConversions._
    val data: JValue = clause match {
      case "named" => NamedClause("", "template", "must")
      case "match" => MatchClause("term", "dialogs", "AND", "must")
      case "near" => SpanNearClause("hello search", "dialogs", 10, inOrder = false, "must")
    }

    val queryPrompt = clause match {
      case "near" => "it must contain at least two words"
      case _ => ""
    }

    //add property prompt
    data match {
      case JArray(arr) =>
        JArray(arr.map(_.asInstanceOf[JObject]).foldLeft(List.empty[JObject]) { (acc, obj) =>
          import org.json4s.JsonDSL._
          obj \ "name" match {
            case JString("field") => obj ~ ("prompt" -> "dialogs agent* customer*") :: acc
            case JString("query") => obj ~ ("prompt" -> queryPrompt) ~ ("value" -> "") :: acc
            case _ => obj :: acc
          }
        })
      case _ => data
    }
  }

  def processResult: Receive = {
    case clause: String =>
      response {
        URI { href =>

          val ver = JField("version", JString("1.0"))
          val data = JField("data", sample(clause))
          val template = JField("template", JObject(data))
          val collection = JField("collection", JObject(ver, JField("href", JString(s"$href")), template))

          respondWithMediaType(`application/vnd.collection+json`) {
            complete(OK, compact(render(JObject(collection))))
          }
        }
      }
  }
}


object Preview {

  implicit class SearchRequestBuilder0(builder: SearchRequestBuilder) {
    def setVttHighlight() =
      builder
        .addField(VttField.NAME)
        .setHighlighterRequireFieldMatch(true)
        .setHighlighterNumOfFragments(0)
        .setHighlighterPreTags("<em>")
        .setHighlighterPostTags("</em>")
        .addHighlightedField("agent*")
        .addHighlightedField("customer*")
        .addHighlightedField("dialogs")
  }

  def props(size: Int = 10, from: Int = 0)(implicit ctx: RequestContext, client: org.elasticsearch.client.Client , storedQueryId: String) = {

    import scala.concurrent.ExecutionContext.Implicits.global

    val getQuery =
      prepareGet(storedQueryId)
        .setFetchSource(Array("query"), null)
        .setTransformSource(true)
        .execute().asFuture
        .map { r => compact(render(parse(r.getSourceAsString) \ "query")) }

    val search = for {
      query <- getQuery
      hits <- logs.prepareSearch()
        .setSize(size).setFrom(from)
        .setQuery(query).setVttHighlight().execute().asFuture
    } yield hits

    Props(classOf[Preview], ctx, client, search, size, from, Map(("_id", storedQueryId)))
  }

}

case class Preview(ctx: RequestContext, implicit val client: org.elasticsearch.client.Client,
                   search: Future[SearchResponse],
                   size: Int, from: Int,
                   itemUriQuery: Map[String,String]) extends PerRequest {

  import context.dispatcher

  search pipeTo self

  def processResult: Receive = {
    case r: SearchResponse =>
      response {
        requestUri { implicit uri =>
          import Pagination._
          val `HH:mm:ss.SSS` = org.joda.time.format.DateTimeFormat.forPattern("HH:mm:ss.SSS")
          def startTime(value: logs.VttHighlightFragment): Int =
            `HH:mm:ss.SSS`.parseDateTime(value.start).getMillisOfDay

          val links = Pagination(size, from, r).links.+:(s"""{ "href" : "$uri/status", "rel" : "status" }""").filterNot(_.isEmpty).mkString(",")

          val items = r.getHits.map { case logs.SearchHitHighlightFields(location, fragments) =>
            s"""{
               |  "href" : "${uri.withPath(Path(s"/$location")).withQuery(itemUriQuery)}",
               |  "data" : [
               |    { "name" : "highlight", "array" : [ ${fragments.toList.sortBy { e => startTime(e) }.map { case logs.VttHighlightFragment(start, keywords) => s""""$start $keywords"""" }.mkString(",")} ] },
               |    { "name" : "keywords" , "value" : "${fragments.flatMap { _.keywords.split("""\s""") }.toSet.mkString(" ")}" }
               |  ]
               |}""".stripMargin
          }.mkString(",")

          complete(OK,
            s"""{
              |   "collection" : {
              |     "version" : "1.0",
              |     "href" : "$uri",
              |     "links" : [ $links ],
              |
              |     "items" : [ $items ]
              |   }
              |}""".stripMargin)
        }
      }
    case ex: Exception =>
      response {
        requestUri { uri =>
          log.error(ex, s"$uri")
          complete(InternalServerError,
            s"""{
               |  "collection" : {
               |    "version" : "1.0",
               |    "href" : "$uri",
               |    "error": { "message" : "${ex.getMessage}" }
               |  }
               |}""".stripMargin)
        }
      }
  }
}

object Status {
  def props(implicit ctx: RequestContext, client: org.elasticsearch.client.Client , storedQueryId: String) =
    Props(classOf[Status], ctx, client, storedQueryId)
}

case class Status(ctx: RequestContext, implicit val client: org.elasticsearch.client.Client, storedQueryId: String) extends PerRequest {
  import context.dispatcher

  def count(query: String) = {
    logs.prepareCount()
      .setQuery(QueryBuilders.wrapperQuery(query))
      .execute().asFuture
  }

  (for {
    query <- getSourceOf(storedQueryId ,"query")
    hits <- count(query)
  } yield hits) pipeTo self

  def processResult: Receive = {
    case r: CountResponse =>
      response {
        requestUri { uri =>
          complete(OK, s"""{
                          |   "collection" : {
                          |     "version" : "1.0",
                          |     "href" : "$uri",
                          |
                          |     "items" : [ {
                          |       "href" : "$uri",
                          |       "data" : [
                          |         { "name": "count", "value" : ${r.getCount} }
                          |       ]
                          |     } ]
                          |   }
                          |}""".stripMargin)
        }
      }
  }
}
