package vedavaapi.swagger

import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.Info
import io.swagger.models.ExternalDocs
import io.swagger.models.auth.BasicAuthDefinition
import scl.grammar.analyzer.AnalyserService
import vedavaapi.hello.HelloService

object SwaggerDocService extends SwaggerHttpService {
  override val apiClasses = Set( classOf[HelloService], classOf[AnalyserService])
  override val host = "localhost:9090"
  override val info = Info(version = "1.0")
  override val externalDocs = Some(new ExternalDocs("Core Docs", "http://acme.com/docs"))
  override val securitySchemeDefinitions = Map("basicAuth" -> new BasicAuthDefinition())
  override val unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")
}