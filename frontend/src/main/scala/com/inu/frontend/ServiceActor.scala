package com.inu.frontend

import com.inu.frontend.analysis.AnalysisRoute
import com.inu.frontend.logs.LogsRoute
import spray.routing.HttpServiceActor
import spray.util.LoggingContext
import com.inu.frontend.storedquery.StoredQueryRoute

class ServiceActor(implicit val client: org.elasticsearch.client.Client) extends HttpServiceActor
  with CorsSupport
  with StoredQueryRoute
  with LogsRoute
  with AnalysisRoute {

  implicit val system = context.system
  implicit val executionContext = system.dispatchers.lookup("my-thread-pool-dispatcher")
  implicit val json4sFormats = org.json4s.DefaultFormats ++ org.json4s.ext.JodaTimeSerializers.all

  val log = LoggingContext.fromActorRefFactory(actorRefFactory)

  def receive = runRoute(
     pathPrefix("sapi") {
      cors {
        `_query/template/` ~
          `logs-*` ~
          `_analysis`
       }
     }
  )
}
