package scl.grammar

import java.util.concurrent.TimeUnit
import javax.ws.rs.Path

import akka.actor.ActorRef
import akka.http.scaladsl.server.{Directives, Route}
import akka.pattern.ask
import akka.util.Timeout
import dbSchema.grammar._
import dbUtils.jsonHelper
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import io.swagger.annotations._
import org.json4s.Formats
import org.json4s.native.Serialization

import scala.concurrent.ExecutionContext

@Api(value = "/grammar_v1_generators", produces = "application/json")
@Path("/grammar/v1/generators")
class GeneratorService(generatorActorRef: ActorRef)(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives with Json4sSupport {
  implicit val jsonFormats: Formats = jsonHelper.formats
  implicit val jsonWritePretty: Json4sSupport.ShouldWritePretty = ShouldWritePretty.True
  implicit val jsonSerialization: Serialization.type = Serialization

  // Actor ask timeout
  implicit val timeout: Timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)


  val route: Route = concat(generateSubanta, generateTinanta)

  @Path("/praatipadikas/{prakaara}/{linga}/{root}/{vibhaktiIn}/{vachana}")
  @ApiOperation(value = "Return declension", notes = "", nickname = "Analyse", httpMethod = "GET")
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
  def generateSubanta: Route =
    path("grammar" / "v1" / "generators" / "praatipadikas" / Segment / Segment / Segment / Segment / Segment) (
      (prakaara: String, linga: String, root: String, vibhaktiIn: String, vachana: String) => {
        get {
          complete {
            val praatipadika = Praatipadika(root = Some(root), prakaara = Some(prakaara), linga = Some(linga))
            val vibhakti = SupVibhakti(vibhaktiNum = (vibhaktiIn.toInt - 1) % 7 + 1, prakaara = if (vibhaktiIn.toInt == 8) Some("सम्बोधनम्") else None)
            val subantaIn = Subanta(pada = null, praatipadika = Some(praatipadika), vibhakti = Some(vibhakti), vachana = Some(vachana.toInt))
            ask(generatorActorRef, subantaIn)
              .mapTo[Seq[String]]
          }
        }
      }
    )

  // Sample line to be parsed: cur1,curaz,curAxiH,sweye
  // Size of this map: some multiple of 74kb, which is the file size.
  // One can optimize for memory by not preconstructing the Dhaatu objects, and just storing the sclCode as map values.
  private val dhaatuMap = scala.io.Source.fromInputStream(is = getClass.getResource("/scl_bin/scl_dhAtu_list.csv").openStream()).getLines().
    map(line => line.split(",")).filter(_.length == 4).map(x =>
    Tuple2(s"${x(1)},${x(2)},${x(3)}", Dhaatu(
      root = Some(x(1)), sclCode = Some(x(0)), arthas = Some(Seq(x(3))), gaNas = Some(Seq(x(2)))
    ))).toMap


  @Path("/alternateFormsWx/{alternateFormWx}/{prayoga}/{lakaara}/{puruSha}/{vachana}/{kimpadI}")
  @ApiOperation(value = "Return conjugation", notes = "", nickname = "Analyse", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(value = "A tinanta to indicate the root of interest", example = "corayawi", name = "alternateFormWx", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(allowableValues = "karwari,karmaNi", value = "Click on 'Try it out'!", name = "prayoga", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(allowableValues = "lat,lit,let,lut,lqt,lot,laf,lif,luf,lqf", value = "Click on 'Try it out'!", name = "lakaara", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(allowableValues = "pra,ma,u", value = "Click on 'Try it out'!", name = "puruSha", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(allowableValues = "1,2,3", value = "Click on 'Try it out'!", name = "vachana", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(allowableValues = "parasmEpaxI,AwmanepaxI", value = "Click on 'Try it out'!", name = "kimpadI", required = true, dataType = "string", paramType = "path")))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "pada-s", response = classOf[Seq[String]]),
    new ApiResponse(code = 404, message = "Root not found"),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def generateTinanta: Route =
    path("grammar" / "v1" / "generators" / "alternateFormsWx" / Segment / Segment / Segment / Segment / Segment / Segment) (
      (alternateFormWx: String, prayoga: String, lakaara: String, puruSha: String, vachana: String, kimpadI: String) => {
        get {
          complete {
            val vivaxaa = TinVivaxaa(prayoga = Some(prayoga), kimpadI = Some(kimpadI), lakaara = Some(lakaara), puruSha = Some(puruSha), vachana = Some(vachana.toInt))
            ask(generatorActorRef, (vivaxaa, alternateFormWx))
              .mapTo[Seq[String]]
          }
        }
      }
    )
}
