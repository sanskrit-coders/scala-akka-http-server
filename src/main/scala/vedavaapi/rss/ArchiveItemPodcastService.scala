package vedavaapi.rss

import javax.ws.rs.Path

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.{ByteString, Timeout}
import dbSchema.grammar.SclAnalysis
import dbUtils.jsonHelper
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import io.swagger.annotations._
import org.json4s.native.{JsonMethods, Serialization}
import akka.pattern.{ask, pipe}
import dbSchema.archive.ItemInfo
import org.json4s.DefaultFormats

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class ArchiveReaderActor extends Actor
  with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http(context.system)

  def receive = {
    case archiveId: String => {
      val uri = f"http://archive.org/metadata/$archiveId"
      // Example response: http://jsoneditoronline.org/?id=e031ab3cecf3cd6e0891eb9f303cd963
      val responseFuture = http.singleRequest(HttpRequest(uri = uri))
      val responseStringFuture = responseFuture.flatMap(response => response match {
        case HttpResponse(StatusCodes.OK, headers, entity, _) =>
          // The below is a Future[String] which is filled when the stream is read. That future is what we return!
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
        case resp@HttpResponse(code, _, _, _) =>
          val message = "Request failed, response code: " + code
          log.warning(message = message)
          // Always make sure you consume the response entity streams (of type Source[ByteString,Unit]) by for example connecting it to a Sink (for example response.discardEntityBytes() if you don’t care about the response entity), since otherwise Akka HTTP (and the underlying Streams infrastructure) will understand the lack of entity consumption as a back-pressure signal and stop reading from the underlying TCP connection!
          resp.discardEntityBytes()
          Future.failed(new Exception(message))
      })
      val podcastFuture = responseStringFuture.map(responseString => {
        log.debug(responseString)
        val archiveItem = jsonHelper.fromString[ItemInfo](responseString)
        jsonHelper.asString(archiveItem)
      })
      podcastFuture.pipeTo(sender())
    }
  }

}

// Returns text/plain , so does not extend Json4sSupport trait unlike some other REST API services.
@Api(value = "/podcasts/v1", produces = "text/plain")
@Path("/podcasts/v1")
class PodcastService(archiveReaderActorRef: ActorRef)(implicit executionContext: ExecutionContext)
  extends Directives {
  implicit val timeout = Timeout(10.seconds)

  val route = getPodcast

  @Path("/archiveItems/{archiveId}")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.", notes = "Since the return value is not a JSON, you cannot hit 'Try it out' on swagger UI. You'll have to open the request url in a separate tab.", nickname = "getPodcast", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "archiveId", value = "Word to analyse, in HK",
      example = "CDAC-tArkShya-shAstra-viShayaka-bhAShaNAni", defaultValue = "CDAC-tArkShya-shAstra-viShayaka-bhAShaNAni",
      required = true, dataType = "string", paramType = "path")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return podcast feed", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getPodcast =
    path("podcasts" / "v1" / "archiveItems" / Segment) (
      (archiveId: String) => {
        get {
          onSuccess(ask(archiveReaderActorRef, archiveId).mapTo[String]) (
            podcastFeed => complete(podcastFeed)
          )
        }
      }
    )
}
