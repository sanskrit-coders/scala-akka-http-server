package vedavaapi.swagger

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.server.{Directives, Route}
import akka.util.Timeout
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.{Contact, Info}
import io.swagger.models.{ExternalDocs, Scheme}
import io.swagger.models.auth.BasicAuthDefinition
import scl.grammar.{AnalyserService, GeneratorService}
import vedavaapi.SwaggerSettings
import vedavaapi.rss.PodcastService

import scala.concurrent.ExecutionContext

class SwaggerDocService(val swagger_settings: SwaggerSettings) extends SwaggerHttpService {
  override val apiClasses = Set(classOf[AnalyserService], classOf[GeneratorService], classOf[PodcastService])
  override val basePath = swagger_settings.base_http_path.getOrElse(super.basePath)
  override val host: String = if (!swagger_settings.port.isDefined) swagger_settings.hostname.get else s"${swagger_settings.hostname.get}:${swagger_settings.port.get}"
  override val schemes = swagger_settings.protocols.map(Scheme.forValue).toList
  override val info = Info(version = "1.0", contact = Some(Contact(name="Contact: Issues page", url="https://github.com/vedavaapi/scala-akka-http-server/issues", email="")))
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