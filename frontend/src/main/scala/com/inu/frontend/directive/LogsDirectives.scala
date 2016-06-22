package com.inu.frontend.directive

import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.percolate.PercolateSourceBuilder
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.json4s._
import org.json4s.native.JsonMethods._
import spray.routing._
import shapeless._

trait LogsDirectives extends Directives {

  implicit def client: org.elasticsearch.client.Client

  import QueryBuilders._
  val noReturnQuery = boolQuery().mustNot(matchAllQuery())

  def prepareGetVtt = {
    path("""^logs-\d{4}\.\d{2}\.\d{2}$""".r / Segment / Segment).hflatMap {
      case index :: typ :: id :: HNil =>
        provide(client.prepareGet(index,typ,id)
                      .setFields("vtt")
                      .setFetchSource(Array("dialogs", "agent*", "customer*"), null))
    }
  }

  def prepareSearch(query: JValue): Directive1[SearchRequestBuilder] = {
    parameter('size.as[Int] ? 10, 'from.as[Int] ? 0 ).hflatMap {
      case size :: from :: HNil => {
        provide(
          client.prepareSearch("logs-*")
                .setQuery(
                  query \ "bool" match {
                    case JObject(Nil) => boolQuery().should(noReturnQuery)
                    case _ => boolQuery().should(noReturnQuery).should(wrapperQuery(compact(render(query))))
                  }
                )
                .setSize(size).setFrom(from)
                .addField("vtt")
                  .setHighlighterRequireFieldMatch(true)
                  .setHighlighterNumOfFragments(0)
                  .setHighlighterPreTags("<em>")
                  .setHighlighterPostTags("</em>")
                  .addHighlightedField("agent*")
                  .addHighlightedField("customer*")
                  .addHighlightedField("dialogs")
        )
      }
      case _ => reject
    }
  }

//  def count(percolators: Map[String, JValue]) = {
//
//    percolators.par.map { case (id, json) => client.prepareSearch("logs-*").setQuery() }
//
//  }

}