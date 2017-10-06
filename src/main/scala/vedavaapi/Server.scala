package vedavaapi

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteConcatenation
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.slf4j.LoggerFactory
import sanskrit_coders.scl._
import scl.grammar.{AnalyserService, GeneratorService}
import vedavaapi.rss.{ArchiveReaderActor, PodcastService}
import vedavaapi.swagger.{SwaggerDocService, SwaggerUIService}

import scala.concurrent.ExecutionContextExecutor
import scala.io.Source

case class SwaggerSettings(hostname: Option[String] = Some("localhost"), port: Option[Int] = None, base_http_path: Option[String] = Some("/"), protocols: Seq[String] = Seq("http"))
case class ServerConfig(deployment_directory: Option[String], service_port: Option[Int] = Some(9090), swagger_settings: SwaggerSettings)

object Server extends App with RouteConcatenation {
  implicit val system: ActorSystem = ActorSystem("akka-http-server")
  sys.addShutdownHook(system.terminate())

  val logger = LoggerFactory.getLogger(this.getClass)

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // Read the server configuration.
  implicit val jsonFormats: DefaultFormats.type = DefaultFormats
  val serverConfigStr =  Source.fromResource("server_config_local.json").getLines().mkString(" ")
  val serverConfig = Serialization.read[ServerConfig](serverConfigStr)

  import com.typesafe.config.ConfigFactory

  val akkaHttpConfig = ConfigFactory.load

  ///////////////////////// Initialize various actors
  ////// SCL grammar actors
  //  val binLocation = getClass.getResource("/scl_bin/all_morf.bin").getPath
  val binLocation = new File(serverConfig.deployment_directory.get,   "src/main/resources/scl_bin").getAbsolutePath
  val analyser = new Analyser(binFilePath = new File(binLocation, "all_morf.bin").getAbsolutePath)
  val analyserActor = system.actorOf(
    Props(classOf[AnalyserActor], analyser))
  val subantaGenerator = new SubantaGenerator(binFilePath = new File(binLocation, "sup_gen.bin").getAbsolutePath)
  val tinantaGenerator = new TinantaGenerator(binFilePath = new File(binLocation, "wif_gen.bin").getAbsolutePath)
  val generatorActor = system.actorOf(
    Props(classOf[GeneratorActor], subantaGenerator, tinantaGenerator))

  ////// Other actors
  val archiveReaderActor = system.actorOf(Props(classOf[ArchiveReaderActor]))


  //////// Done initializing actors.

  import akka.http.scaladsl.model.StatusCodes
  import akka.http.scaladsl.server.Directives._

  implicit val requestTimeoutSecs: Int = akkaHttpConfig.getDuration("akka.http.server.request-timeout", TimeUnit.SECONDS).toInt
  logger.info(s"requestTimeoutSecs: $requestTimeoutSecs")

  // Set up the routes.
  val routes =
    cors() {concat(
      path("") {
        redirect("swagger/index.html", StatusCodes.TemporaryRedirect)
      },
      new AnalyserService(analyserActor).route,
      new GeneratorService(generatorActor).route,
      new PodcastService(archiveReaderActor).route,
      new SwaggerUIService().route,
      new SwaggerDocService(swagger_settings = serverConfig.swagger_settings).routes  // Corresponds to : api-docs/
    )
    }
  Http().bindAndHandle(routes, "0.0.0.0", serverConfig.service_port.get)
}
