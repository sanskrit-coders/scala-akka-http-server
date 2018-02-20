package vedavaapi.rss

import java.net.URLDecoder
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
import sanskrit_coders.RichHttpClient.HttpClient
import sanskrit_coders.{RichHttpClient, Utils}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class ArchivePodcastRequest(archiveIds: Seq[String], useArchiveOrder: Boolean = true,
                                 filePattern: String, podcastTemplate: Podcast)
case class ArchivePodcastRequestUri(requestUri: String)

class ArchiveReaderException extends Exception {

}

class ArchiveReaderActor extends Actor
  with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  private val simpleClient: HttpRequest => Future[HttpResponse] = Http(context.system).singleRequest(_: HttpRequest)
  private val redirectingClient: HttpRequest => Future[HttpResponse] = RichHttpClient.httpClientWithRedirect(simpleClient)

  def getPodcastFuture(archiveId: String, podcastRequest: ArchivePodcastRequest): Future[Podcast] = {

    val uri = f"http://archive.org/metadata/${archiveId}"
    // Example response: http://jsoneditoronline.org/?id=e031ab3cecf3cd6e0891eb9f303cd963
    RichHttpClient.httpResponseToString(redirectingClient(HttpRequest(uri = uri))).map(responseString => {
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
        podcastFailures.foreach(failure => {
          log.error(failure.exception.getMessage)
          log.error(failure.exception.getStackTrace.mkString("\n"))
        })
        throw new IllegalArgumentException(podcastFailures.map(_.exception.getSuppressed.mkString("\n\n")).mkString("\n\n"))
      } else {
        val podcasts = podcastSuccesses.map(_.value)
        if (podcasts.size <= 0) {
          throw new IllegalArgumentException("No podcast successfully read!")
        }
        val finalPodcast: Podcast = podcasts.foldLeft(null.asInstanceOf[Podcast]){
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
    case podcastRequestUrl: ArchivePodcastRequestUri => {
      log.debug(podcastRequestUrl.toString)
      RichHttpClient.httpResponseToString(redirectingClient(HttpRequest(uri = podcastRequestUrl.requestUri))).map(responseString => {
        log.debug(responseString)
        val podcastRequest = jsonHelper.fromString[ArchivePodcastRequest](responseString)
        getFinalPodcastFuture(podcastRequest = podcastRequest)
      }).flatten.map(_.getNode.toString()).pipeTo(sender())
    }
  }
}

// Returns application/rss+xml, so does not extend Json4sSupport trait unlike some other REST API services.
@Api(value = "/podcasts_v1", produces = " application/rss+xml")
@Path("/podcasts/v1")
class PodcastService(archiveReaderActorRef: ActorRef)(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives {

  // Actor ask timeout
  implicit val timeout: Timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)

  val route: Route = concat(getPodcast, getPodcastFromUri)

  def regexValid(pattern: String): Boolean = {
    try {
      Pattern.compile(pattern) != null
    } catch {
      case _: PatternSyntaxException => false
    }
    true
  }

  final val USAGE_TIPS = "Click on Try it out! See <a href=\"https://github.com/vedavaapi/scala-akka-http-server/blob/master/README_PODCASTING_TOOLS.md\">README_PODCASTING</a> for background." +
    "\n  But wait - Check <a href=\"https://docs.google.com/spreadsheets/d/1KMhtMaHCQpucqxH3aVcmYmPvQyV9vmunvckV2ARvD4M/edit#gid=0\">this sheet</a> to see if the relevant podcast has already been generated." +
    "\n If the output looks good:" +
    "<ul>" +
    "<li>If the feed changes rarely or never, you can copy and serve the RSS feed output from some other page (eg. pastebin or github); and submit its URL to indices like <a href=\"https://play.google.com/music/podcasts/portal#p:id=playpodcast/all-podcasts\">Google Play</a>, <a href=\"https://podcastsconnect.apple.com/#/new-feed/\">ITunes</a>, Stitcher and TuneInRadio.</li>" +
    "<li>You can submit the generated podcast URL (copy from the generated CURL command, perhaps replacing https with http for speed) to indices like ITunes etc.., but do this sparingly as you might not want to rely on the stability of this server. </li>" +
    "</ul>" +
    "\n Finally, let us know via <a href=\"https://goo.gl/forms/jTT3DvXTVqu1jU0j2\">this form</a>!"

  @Path("/archiveItems/{archiveId}")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.", notes = USAGE_TIPS, nickname = "getPodcast", httpMethod = "GET")
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


  /* We are not using /archiveRequests/{archiveRequestUri} below because we observed the below error behind vedavaapi apache proxy:
  "The requested URL /scala/podcasts/v1/archiveRequests/https://github.com/sanskrit-coders/rss-feeds/raw/master/feeds/kn/requestJsons/r_ganesh_all_lectures.json was not found on this server."
  Hence making archiveRequestUri a query parameter, rather than a path parameter.
   */
  @Path("/archiveRequests")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.",
    notes = USAGE_TIPS, nickname = "getPodcastFromUri", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "archiveRequestUri", value = "URI of a valid archive request.",
      example = "https://github.com/sanskrit-coders/rss-feeds/raw/master/feeds/kn/requestJsons/r_ganesh_all_lectures.json", defaultValue = "https://github.com/sanskrit-coders/rss-feeds/raw/master/feeds/kn/requestJsons/r_ganesh_all_lectures.json",
      required = true, dataType = "string", paramType = "query"),
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return podcast feed", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getPodcastFromUri: Route =
    path("podcasts" / "v1" / "archiveRequests")(
      parameters('archiveRequestUri)((archiveRequestUri: String) => {
        (get & onComplete(ask(archiveReaderActorRef, ArchivePodcastRequestUri(requestUri = URLDecoder.decode(archiveRequestUri, "UTF-8"))).mapTo[String])) {
          case Success(podcastFeed) => complete {
            HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`application/rss+xml`, HttpCharsets.`UTF-8`), podcastFeed))
          }
          case Failure(ex) => complete((StatusCodes.InternalServerError, s"An error occurred: ${ex.getMessage}, with stacktrace:\n${ex.getStackTrace.mkString("\n")}"))
        }
      })
    )
}
