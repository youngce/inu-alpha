package frontend.storedFilter

import elastic.ImplicitConversions._
import es.indices.logs
import org.elasticsearch.index.query.QueryBuilders
import org.json4s.JsonAST.JObject
import org.json4s._
import org.json4s.native.JsonMethods._
import spray.routing._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContextExecutor
import scala.language.implicitConversions
import scala.util.matching.Regex

trait TemplateExtractor extends Directives {

  implicit def client: org.elasticsearch.client.Client
  implicit def executionContext: ExecutionContextExecutor

  implicit def jFieldToString(value: (String, JValue)): String = value._1
  implicit def stringToString(value: String): String = value
  implicit class Regex0[A](xs: List[A]) {
    def r(implicit f: A => String):Regex = xs.map { a => f(a).formatted("""^%s$""")}.mkString("|").r
  }

  def properties(mapping: JValue): Directive1[(Regex, JValue)] = {

    val result = for {
      meta@JObject(props0) <- (mapping \ "_meta" \ "properties").toOption
      mp@JObject(props1)   <- (mapping \ "properties").toOption
      xs = props0.map(_._1).intersect(props1.map(_._1))
    } yield (xs.r(identity), meta)

    result match {
      case Some(x) => provide(x)
      case None => reject
    }
  }

  def queries(json: JValue): Directive1[(Regex, JObject)] = {
    json \ "queries" match {
      case q@JObject(xs) =>
        provide((xs.r, q))
      case _ => reject()
    }
  }

  def template: Directive1[Map[String, JValue]] = onSuccess(for {
    templates  <- logs.getTemplate
    mappings  <- templates.getIndexTemplates.headOption.map(_.mappings).future(new Exception("template1 doesn't exist"))
  } yield mappings.map { x => (x.key, parse(x.value.string()) \ x.key) }.toMap)

  def dataSources(usr: String)(exists: Set[String]): Directive1[Set[String]] = onSuccess({
    client.prepareSearch("internal")
    .setTypes("authorized")
    .setQuery(QueryBuilders.termQuery("user", usr))
    .setFetchSource("dataSources", null).execute().future.map { r =>
      r.getHits().foldLeft(Set.empty[String]){ (acc, e) =>
        parse(e.getSourceAsString()) \ "dataSources" match {
          case JArray(xs) => acc ++ xs.map {
            case JString(str) => str
            case other => ""
          }.filterNot { _.isEmpty }
          case _ => acc
        }
      }.intersect(exists)
    }
  })

}