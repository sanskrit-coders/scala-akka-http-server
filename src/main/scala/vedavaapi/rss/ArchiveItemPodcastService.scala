package vedavaapi.rss

import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.{Pattern, PatternSyntaxException}
import javax.ws.rs.Path

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.{ByteString, Timeout}
import dbSchema.archive.ItemInfo
import dbSchema.rss.Podcast
import dbUtils.jsonHelper
import io.swagger.annotations._
import vedavaapi.Utils

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class ArchivePodcastRequest(archiveIds: Seq[String], useArchiveOrder: Boolean = true,
                                 filePattern: String, podcastTemplate: Podcast)

class ArchiveReaderException extends Exception {

}

class ArchiveReaderActor extends Actor
  with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  val http = Http(context.system)

  def readHttpString(uri: String): Future[String] = {
    val responseFuture = http.singleRequest(HttpRequest(uri = uri))
    responseFuture.flatMap(response => response match {
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
  }

  def getPodcastFuture(archiveId: String, podcastRequest: ArchivePodcastRequest): Future[Podcast] = {

    val uri = f"http://archive.org/metadata/${archiveId}"
    // Example response: http://jsoneditoronline.org/?id=e031ab3cecf3cd6e0891eb9f303cd963
    readHttpString(uri = uri).map(responseString => {
//      log.debug(responseString)
      val archiveItem = jsonHelper.fromString[ItemInfo](responseString)
      archiveItem.toPodcast(filePattern = podcastRequest.filePattern, useArchiveOrder = podcastRequest.useArchiveOrder, podcast = podcastRequest.podcastTemplate)
    })
  }

  def getFinalPodcastFuture(podcastRequest: ArchivePodcastRequest): Future[Podcast] = {
    assert(podcastRequest.archiveIds.nonEmpty)
    val podcastFutures = podcastRequest.archiveIds.map(x => getPodcastFuture(archiveId = x, podcastRequest = podcastRequest))
    val podcastTrysFuture = Utils.getFutureOfTrys(futures = podcastFutures)
    podcastTrysFuture.map[Podcast](podcastTrys => {
      val podcastFailures = podcastTrys.filter(_.isFailure).map(_.asInstanceOf[Failure[Podcast]])
      val podcastSuccesses = podcastTrys.filter(_.isSuccess).map(_.asInstanceOf[Success[Podcast]])
      if (podcastFailures.nonEmpty) {
        throw new IllegalArgumentException(podcastFailures.map(_.exception.getMessage).mkString("\n\n"))
      } else {
        val podcasts = podcastSuccesses.map(_.value)
        if (podcasts.size <= 0) {
          throw new IllegalArgumentException("No podcast successfully read!")
        }
        val finalPodcast: Podcast = podcasts.foldLeft(podcasts.head){
          (p1: Podcast, p2: Podcast) => Podcast.merge(podcast1 = p1, podcast2 = p2)
        }
        finalPodcast
      }
    })
  }

  def receive: PartialFunction[Any, Unit] = {
    case podcastRequest: ArchivePodcastRequest => {
      getFinalPodcastFuture(podcastRequest = podcastRequest).map(_.getNode.toString()).pipeTo(sender())
    }
    case podcastRequestUrl: String => {
      readHttpString(uri = podcastRequestUrl).map(responseString => {
        log.debug(responseString)
        val podcastRequest = jsonHelper.fromString[ArchivePodcastRequest](responseString)
        getFinalPodcastFuture(podcastRequest = podcastRequest).map(_.getNode.toString()).pipeTo(sender())
      })

    }
  }
}

// Returns text/plain , so does not extend Json4sSupport trait unlike some other REST API services.
@Api(value = "/podcasts/v1", produces = " application/rss+xml")
@Path("/podcasts/v1")
class PodcastService(archiveReaderActorRef: ActorRef)(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives {

  // Actor ask timeout
  implicit val timeout: Timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)

  val route: Route = getPodcast

  def regexValid(pattern: String): Boolean = {
    try {
      Pattern.compile(pattern) != null
    } catch {
      case _: PatternSyntaxException => false
    }
    true
  }

  @Path("/archiveItems/{archiveId}")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.", notes = "Click on Try it out! See <a href=\"https://github.com/vedavaapi/scala-akka-http-server/blob/master/README_PODCASTING_TOOLS.md\">README_PODCASTING</a> for background." +
    "\n  But wait - Check <a href=\"https://docs.google.com/spreadsheets/d/1KMhtMaHCQpucqxH3aVcmYmPvQyV9vmunvckV2ARvD4M/edit#gid=0\">this sheet</a> to see if the relevant podcast has already been generated." +
    "\nIf the output looks good, you can submit the generated podcast URL (copy from the generated CURL command, perhaps replacing https with http for speed) to indices like <a href=\"https://play.google.com/music/podcasts/portal#p:id=playpodcast/all-podcasts\">Google Play</a>, <a href=\"https://podcastsconnect.apple.com/#/new-feed/\">ITunes</a>, Stitcher and TuneInRadio." +
    "\nAlternatively, if the feed changes rarely or never, you can copy and serve the RSS feed output from some other page (eg. pastebin or github); and submit its URL to ITunes etc.." +
    "\nAnd let us know via <a href=\"https://goo.gl/forms/jTT3DvXTVqu1jU0j2\">this form</a>!", nickname = "getPodcast", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "archiveId", value = "The Archive Item ID, which you find in the url https://archive.org/details/___itemId___",
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
    new ApiImplicitParam(name = "isExplicitYesNo", value = "Required by itunes, recommended by Google Play.", allowableValues = "yes, no, clean", defaultValue = "no",
      required = true, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "filePattern", value = "What types of files should we include in the podcast? Provide a regular expression. .*\\.mp3 is the default value.", example = "mp3",
      required = false, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "title", value = "The archive item title is used by default. If you want something different, set it here.",
      required = false, dataType = "string", paramType = "query"),
    new ApiImplicitParam(name = "useArchiveOrder", value = "Should the files appear by the way in which Archive orders them (true by default)? Or should they be ordered by the time at which they were added to the Archive item?", example = "true", allowableValues = "true, false",
      required = false, dataType = "string", paramType = "query"),
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return podcast feed", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getPodcast: Route =
    path("podcasts" / "v1" / "archiveItems" / Segment)(
      (archiveId: String) => {
        parameters('publisherEmail, 'imageUrl ? "https://i.imgur.com/dQjPQYi.jpg", 'languageCode ? "en", 'categoriesCsv ? "Society & Culture", 'isExplicitYesNo ? "no", 'filePattern ? ".*\\.mp3", 'useArchiveOrder ? "true", 'title ?)((publisherEmail, imageUrl, languageCode, categoriesCsv, isExplicitYesNo, filePattern, useArchiveOrder, title) => {
          (get & validate(Locale.getISOLanguages.contains(languageCode), s"languageCode $languageCode not found in Locale.getISOLanguages.") &
            validate(regexValid(filePattern), s"filePattern $filePattern is not valid.") &
            validate(!archiveId.contains("//archive.org/details"), s"<<$archiveId>> seems to be an invalid archiveId. Don't provide the entire URL.") &
            onComplete(
              ask(archiveReaderActorRef, ArchivePodcastRequest(archiveIds = Seq(archiveId.trim), useArchiveOrder = useArchiveOrder.toBoolean, filePattern = filePattern.trim,
                podcastTemplate = Podcast(title = title.getOrElse(""), description = "", items = Seq(), publisherEmail = publisherEmail.trim, languageCode = languageCode.trim, imageUrl = imageUrl.trim, categories = categoriesCsv.split(",").map(_.trim), isExplicitYesNo = Some(isExplicitYesNo)))).mapTo[String])) {
            case Success(podcastFeed) => complete {
              HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`application/rss+xml`, HttpCharsets.`UTF-8`), podcastFeed))
            }
            case Failure(ex) => complete((StatusCodes.InternalServerError, s"An error occurred: ${ex.getMessage}, with stacktrace:\n${ex.getStackTrace.mkString("\n")}"))
          }
        }

        )
      }
    )
}
