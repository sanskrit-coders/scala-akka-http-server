package vedavaapi.rss

import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.ws.rs.Path

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
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

case class ArchivePodcastRequest(archiveId: String, publisherEmail: String, imageUrl: String, languageCode: String = "en", fileExtensions: Seq[String], categories: Seq[String], isExplicitYesNo: Option[String] = None)

class ArchiveReaderActor extends Actor
  with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http(context.system)

  def receive = {
    case podcastRequest: ArchivePodcastRequest => {
      val uri = f"http://archive.org/metadata/${podcastRequest.archiveId}"
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
        archiveItem.toPodcast(fileExtensions = podcastRequest.fileExtensions, publisherEmail = podcastRequest.publisherEmail, imageUrl = podcastRequest.imageUrl,
          languageCode = podcastRequest.languageCode, categories = podcastRequest.categories, isExplicitYesNo = podcastRequest.isExplicitYesNo).getNode().toString()
      })
      podcastFuture.pipeTo(sender())
    }
  }

}

// Returns text/plain , so does not extend Json4sSupport trait unlike some other REST API services.
@Api(value = "/podcasts/v1", produces = " application/rss+xml")
@Path("/podcasts/v1")
class PodcastService(archiveReaderActorRef: ActorRef)(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives {

  // Actor ask timeout
  implicit val timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)

  val route = getPodcast

  @Path("/archiveItems/{archiveId}")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.", notes = "Click on Try it out! See <a href=\"https://github.com/vedavaapi/scala-akka-http-server/README_PODCASTING_TOOLS.md\">README_PODCASTING</a> for background.\n You can submit the generated podcast to indices like <a href=\"https://play.google.com/music/podcasts/portal#p:id=playpodcast/all-podcasts\">Google Play</a>, <a href=\"https://podcastsconnect.apple.com/#/new-feed/\">ITunes</a>, Stitcher and TuneInRadio.", nickname = "getPodcast", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "archiveId", value = "The Archive Item ID, which you find in the url",
      example = "CDAC-tArkShya-shAstra-viShayaka-bhAShaNAni", defaultValue = "CDAC-tArkShya-shAstra-viShayaka-bhAShaNAni",
      required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "publisherEmail", value = "This may be used to verify your authority to submit the feed to indices like (say) Google Play. So, use an address you can access.",
      example = "podcast-bhaaratii@googlegroups.com",
      defaultValue = "podcast-bhaaratii@googlegroups.com",
      required = true, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "languageCode", value = "The language should be specified according to RFC 3066, RFC 4647 and RFC 5646. List: http://www.loc.gov/standards/iso639-2/php/code_list.php .",
      example = "en",
      defaultValue = "en",
      required = true, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "categoriesCsv", value = "Only certain categories are valid, see: <a href=\"https://support.google.com/googleplay/podcasts/answer/6260341#rpt\">here</a> for Google Play and <a href=\"https://www.seriouslysimplepodcasting.com/itunes-podcast-category-list/\">here</a> for ITunes.",
      example = "Arts, Business, Comedy, Education, Games & Hobbies, Government & Organizations, Health, Kids & Family, Music, News & Politics, Religion & Spirituality, Science & Medicine, Society & Culture, Sports & Recreation, TV & Film, Technology",
      defaultValue = "Society & Culture",
      required = false, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "imageUrl", value = "The desired feed image url. Image must be square and over 1200 x 1200 for Google Play, and over 1400 x 1400 for ITunes.",
      example = "https://i.imgur.com/dQjPQYi.jpg",
      defaultValue = "https://i.imgur.com/dQjPQYi.jpg",
      required = false, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "isExplicitYesNo", value = "Required by itunes, recommended by Google Play.", allowableValues = "Yes, No", defaultValue = "No",
      required = true, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "fileExtensionsCsv", value = "What types of files should we include in the podcast? mp3 is the default value.", example = "mp3",
      required = false, dataType = "string", paramType = "query"),
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return podcast feed", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getPodcast =
    path("podcasts" / "v1" / "archiveItems" / Segment)(
      (archiveId: String) => {
        parameters('publisherEmail, 'imageUrl ? "https://i.imgur.com/dQjPQYi.jpg", 'languageCode ? "en", 'categoriesCsv ? "Society & Culture", 'isExplicitYesNo ? "No", 'fileExtensionsCsv ? "mp3")((publisherEmail, imageUrl, languageCode, categoriesCsv, isExplicitYesNo, fileExtensionsCsv) => {
          get {
            (validate(Locale.getISOLanguages.contains(languageCode), s"languageCode $languageCode not found in Locale.getISOLanguages .") &
              onSuccess(
                ask(archiveReaderActorRef, ArchivePodcastRequest(archiveId = archiveId, publisherEmail = publisherEmail, languageCode = languageCode, imageUrl = imageUrl, categories = categoriesCsv.split(",").map(_.trim), isExplicitYesNo = Some(isExplicitYesNo), fileExtensions = fileExtensionsCsv.split(",").map(_.trim))).mapTo[String])) (
              podcastFeed => complete {
                HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`application/rss+xml`, HttpCharsets.`UTF-8`), podcastFeed))
              }
            )
          }
        }
        )
      }
    )
}
