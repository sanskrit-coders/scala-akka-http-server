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


@Api(value = "/analyze", produces = "application/json")
@Path("/analyze")
class AnalyserService(analyzerActorRef: ActorRef)(implicit executionContext: ExecutionContext)
  extends Directives with Json4sSupport {
  implicit val jsonFormats = jsonHelper.formats
  implicit val jsonWritePretty = ShouldWritePretty.True
  implicit val jsonSerialization = Serialization
  implicit val timeout = Timeout(10.seconds)


  val route = getAnalysis

  @Path("/{word}")
  @ApiOperation(value = "Return Analysis of a word", notes = "", nickname = "hello", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "word", value = "Word to analyse", required = false, dataType = "string", paramType = "path")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return Analysis", response = classOf[SclAnalysis]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def getAnalysis =
    path("analyze" / Segment) { word =>
      get {
        complete {
          (analyzerActorRef ? word)  // Send Hello(name) to hello actor, get response in a Future object.
            .mapTo[Seq[SclAnalysis]]
        }
      }
    }

}
