package vedavaapi


import java.util.concurrent.TimeUnit
import java.util.regex.{Pattern, PatternSyntaxException}
import javax.ws.rs.Path

import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import io.swagger.annotations._
import sanskritnlp.transliteration.transliterator

import scala.concurrent.ExecutionContext

// Returns text/plain, so does not extend Json4sSupport trait unlike some other REST API services.
@Api(value = "/transliterations_v1", produces = "text/plain")
@Path("/transliterations/v1")
class TransliteratorService()(implicit executionContext: ExecutionContext, requestTimeoutSecs: Int)
  extends Directives {

  // Actor ask timeout
  implicit val timeout: Timeout = Timeout(requestTimeoutSecs, TimeUnit.SECONDS)

  val route: Route = concat(transliterate)

  def regexValid(pattern: String): Boolean = {
    try {
      Pattern.compile(pattern) != null
    } catch {
      case _: PatternSyntaxException => false
    }
    true
  }

  final val USAGE_TIPS = "Click on Try it out!"

  @Path("/{sourceScript}/{destScript}")
  @ApiOperation(value = "Return the podcast corresponding to an archive item.", notes = USAGE_TIPS, nickname = "getPodcast", httpMethod = "POST")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "sourceScript", value = "Name of the script used for the inputString value.",
      allowableValues = "iast, iastDcs, as, optitrans, dev, gujarati, gurmukhi, kannada, telugu, malayalam, oriya, bengali, assamese", defaultValue = "dev",
      required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "destScript", value = "Name of the script to be used for the output of this API.",
      allowableValues = "iast, iastDcs, as, optitrans, dev, gujarati, gurmukhi, kannada, telugu, malayalam, oriya, bengali, assamese", defaultValue = "optitrans",
      required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "inputString", value = "A text in the input script/ scheme.",
      example = "छिन्नमस्ते नमस्ते",
      required = true, dataType = "string", paramType = "form"),
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return podcast feed", response = classOf[String]),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def transliterate: Route =
    path("transliterations" / "v1" / Segment / Segment)(
      (sourceScript: String, destScript: String) => {
        formFields('inputString) { (inputString) =>
          complete(transliterator.transliterate(in_str = inputString, sourceScheme = sourceScript, destScheme = destScript))
        }
      }
    )
}
