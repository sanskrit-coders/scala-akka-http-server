package scl.grammar

import java.util.concurrent.TimeUnit
import javax.ws.rs.Path

import akka.actor.ActorRef
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.util.Timeout
import dbSchema.grammar.Analysis
import dbUtils.jsonHelper
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import io.swagger.annotations._
import org.json4s.Formats
import org.json4s.native.Serialization

import scala.concurrent.ExecutionContext

@Api(value = "/grammar/v1/analyser", produces = "application/json")
@Path("/grammar/v1/analyser")
class AnalyserService(analyserActorRef: ActorRef)(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives with Json4sSupport {
  implicit val jsonFormats: Formats = jsonHelper.formats
  implicit val jsonWritePretty: Json4sSupport.ShouldWritePretty = ShouldWritePretty.True
  implicit val jsonSerialization: Serialization.type = Serialization

  // Actor ask timeout
  implicit val timeout: Timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)


  val route: Route = getAnalysis

  @Path("/{word}")
  @ApiOperation(value = "Return Analysis of a word", notes = "", nickname = "Analyse", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "word", value = "Word to analyse, in HK", required = true, dataType = "string", paramType = "path")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return Analysis", response = classOf[Seq[Analysis]]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getAnalysis: Route =
    path("grammar" / "v1" / "analyser" / Segment) ( word =>
      get {
        complete {
          ask(analyserActorRef, word)
            .mapTo[Seq[Analysis]]
        }
      }
    )

}
