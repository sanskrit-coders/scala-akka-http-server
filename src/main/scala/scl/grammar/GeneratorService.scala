package scl.grammar

import javax.ws.rs.Path

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives
import akka.pattern.ask
import akka.util.Timeout
import dbSchema.grammar.{Praatipadika, SclAnalysis, Subanta, SupVibhakti}
import dbUtils.jsonHelper
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import io.swagger.annotations._
import org.json4s.native.Serialization

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.pattern.{ask, pipe}

@Api(value = "/grammar/v1/generators", produces = "application/json")
@Path("/grammar/v1/generators")
class GeneratorService(generatorActorRef: ActorRef)(implicit executionContext: ExecutionContext)
  extends Directives with Json4sSupport {
  implicit val jsonFormats = jsonHelper.formats
  implicit val jsonWritePretty = ShouldWritePretty.True
  implicit val jsonSerialization = Serialization
  implicit val timeout = Timeout(10.seconds)


  val route = generateSubanta

  @Path("/praatipadikas/{prakaara}/{linga}/{root}/{vibhaktiIn}/{vachana}")
  @ApiOperation(value = "Return Analysis of a word", notes = "", nickname = "Analyse", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "prakaara", allowableValues = "sAXAraNa,sarvanAma,safkhyA,safkhyeya,pUraNa", value = "Click on 'Try it out'!", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "linga", allowableValues = "pum,napum,swrI", value = "Click on 'Try it out'!", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "root", value = "root in WX", example = "rAma", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "vibhaktiIn", value = "Click on 'Try it out'! (8 is sambodhana-prathamA)", allowableValues = "1,2,3,4,5,6,7,8", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "vachana", value = "Click on 'Try it out'!", allowableValues = "1,2,3", dataType = "string", paramType = "path"),
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "pada-s", response = classOf[Seq[String]]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def generateSubanta =
    path("grammar" / "v1" / "generators" / "praatipadikas" / Segment / Segment / Segment / Segment / Segment) {
      (prakaara: String, linga: String, root: String, vibhaktiIn: String, vachana: String) => {
        get {
          complete {
            val praatipadika = Praatipadika(root = root, prakaara = Some(prakaara), linga = Some(linga))
            val vibhakti = new SupVibhakti(vibhaktiNum = (vibhaktiIn.toInt - 1) % 7 + 1, prakaara = if (vibhaktiIn.toInt == 8) Some("सम्बोधनम्") else None)
            val subantaIn = new Subanta(pada = null, praatipadika = Some(praatipadika), vibhakti = Some(vibhakti), vachana = Some(vachana.toInt))
            ask(generatorActorRef, subantaIn)
              .mapTo[Seq[String]]
          }
        }
      }
    }

}
