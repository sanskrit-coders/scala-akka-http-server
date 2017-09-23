package vedavaapi.swagger

import akka.http.scaladsl.server.{Directives, Route}
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.Info
import io.swagger.models.ExternalDocs
import io.swagger.models.auth.BasicAuthDefinition
import scl.grammar.{AnalyserService, GeneratorService}

import scala.concurrent.ExecutionContext

object SwaggerDocService extends SwaggerHttpService {
  override val apiClasses = Set(classOf[AnalyserService], classOf[GeneratorService])
  override val host = "localhost:9090"
  override val info = Info(version = "1.0")
  override val externalDocs = Some(new ExternalDocs("Server docs", "https://github.com/vedavaapi/scala-akka-http-server"))
  override val securitySchemeDefinitions = Map("basicAuth" -> new BasicAuthDefinition())
  override val unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")
}

class SwaggerUIService(implicit executionContext: ExecutionContext) extends Directives {
  val route: Route =
    concat(path("swagger") {
      getFromResource("swagger/index.html")
    },
      getFromResourceDirectory("swagger"))
}