package vedavaapi

import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteConcatenation
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import sanskrit_coders.scl._
import scl.grammar.{AnalyserService, GeneratorService}
import vedavaapi.swagger.SwaggerDocService

import scala.concurrent.ExecutionContextExecutor
import scala.io.Source

case class ServerConfig(deployment_directory: Option[String])

object Server extends App with RouteConcatenation {
  implicit val system = ActorSystem("akka-http-server")
  sys.addShutdownHook(system.terminate())

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  // Read the server configuration.
  implicit val jsonFormats = DefaultFormats
  val serverConfigStr =  Source.fromResource("server_config_local.json").getLines().mkString(" ")
  val serverConfig = Serialization.read[ServerConfig](serverConfigStr)

  ///////////////////////// Initialize various actors
  //  val binLocation = getClass.getResource("/scl_bin/all_morf.bin").getPath
  val binLocation = new File(serverConfig.deployment_directory.get,   "src/main/resources/scl_bin").getAbsolutePath
  val analyser = new Analyser(binFilePath = new File(binLocation, "all_morf.bin").getAbsolutePath)
  val analyserActor = system.actorOf(
    Props(classOf[AnalyserActor], analyser))
  val subantaGenerator = new SubantaGenerator(binFilePath = new File(binLocation, "sup_gen.bin").getAbsolutePath)
  val tinantaGenerator = new TinantaGenerator(binFilePath = new File(binLocation, "wif_gen.bin").getAbsolutePath)
  val generatorActor = system.actorOf(
    Props(classOf[GeneratorActor], subantaGenerator, tinantaGenerator))


  //////// Done initializing actors.

  // Set up the routes.
  val routes =
    cors() {concat(
      new AnalyserService(analyserActor).route,
      new GeneratorService(generatorActor).route,
      SwaggerDocService.routes)  // Corresponds to : api-docs/
    }
  Http().bindAndHandle(routes, "0.0.0.0", 9090)
}
