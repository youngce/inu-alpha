package frontend

import akka.actor._
import spray.http.StatusCodes._
import spray.routing._

trait PerRequest extends Actor with ActorLogging with Directives {

  import context._

  implicit class stringUri(s: String) {def uri: java.net.URI = java.net.URI.create(s)
  }

  val URI = extract(ctx => s"${ctx.request.uri}".uri)

  //import scala.concurrent.duration._
  //setReceiveTimeout(10.seconds)

  def ctx: RequestContext

  def processResult: Receive

  def receive = processResult orElse defaultReceive

  private def defaultReceive: Receive = {
    case ReceiveTimeout =>
      response { complete(RequestTimeout) }
    case res =>
      response { complete(InternalServerError, s"unexpected message: $res")}
  }

  def response(finalStep: Route): Unit = {
    finalStep(ctx)
    stop(self)
  }

  override def unhandled(message: Any): Unit = message match {
    case _ => log.debug(s"unhandled message: $message")
  }
}