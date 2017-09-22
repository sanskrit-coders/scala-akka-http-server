package scl.grammar

import javax.ws.rs.Path

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives
import akka.pattern.ask
import akka.util.Timeout
import dbSchema.grammar.SclAnalysis
import dbUtils.jsonHelper
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import io.swagger.annotations._
import org.json4s.native.Serialization

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.pattern.{ ask, pipe }

@Api(value = "/analyse", produces = "application/json")
@Path("/analyse")
class AnalyserService(analyserActorRef: ActorRef)(implicit executionContext: ExecutionContext)
  extends Directives with Json4sSupport {
  implicit val jsonFormats = jsonHelper.formats
  implicit val jsonWritePretty = ShouldWritePretty.True
  implicit val jsonSerialization = Serialization
  implicit val timeout = Timeout(10.seconds)


  val route = getAnalysis

  @Path("/{word}")
  @ApiOperation(value = "Return Analysis of a word", notes = "", nickname = "Analyse", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "word", value = "Word to analyse, in HK", required = true, dataType = "string", paramType = "path")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return Analysis", response = classOf[SclAnalysis]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getAnalysis =
    path("analyse" / Segment) { word =>
      get {
        complete {
          ask(analyserActorRef, word)
            .mapTo[Seq[SclAnalysis]]
        }
      }
    }

}
