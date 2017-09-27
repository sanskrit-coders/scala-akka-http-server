package vedavaapi.swagger

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.Info
import io.swagger.models.ExternalDocs
import io.swagger.models.auth.BasicAuthDefinition
import scl.grammar.{AnalyserService, GeneratorService}
import vedavaapi.rss.PodcastService

import scala.concurrent.ExecutionContext

class SwaggerDocService(val hostname: String, val port: Int) extends SwaggerHttpService {
  override val apiClasses = Set(classOf[AnalyserService], classOf[GeneratorService], classOf[PodcastService])
  override val host = s"$hostname:$port"
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