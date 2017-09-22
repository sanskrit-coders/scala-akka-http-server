package vedavaapi

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteConcatenation
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import vedavaapi.hello.{HelloActor, HelloService}
import vedavaapi.swagger.SwaggerDocService
import sanskrit_coders.scl.{Analyser, AnalyserActor}
import scl.grammar.analyzer.AnalyserService

import scala.concurrent.ExecutionContextExecutor
import scala.io.Source

case class ServerConfig(deployment_directory: Option[String], serverUrl: Option[String])

object Rest extends App with RouteConcatenation {
  implicit val system = ActorSystem("akka-http-server")
  sys.addShutdownHook(system.terminate())

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val hello = system.actorOf(Props[HelloActor])

  // TODO Read as instance of ServerConfig and use.
  val serverConfig =  Source.fromResource("server_config.json").toString()

  //  val binLocation = getClass.getResource("/scl_bin/all_morf.bin").getPath
  val binLocation = "/home/vvasuki/scala-akka-http-server/src/main/resources/scl_bin/all_morf.bin"

  val analyser = new Analyser(binFilePath = binLocation)
  val analyserActor = system.actorOf(
    Props(classOf[AnalyserActor], analyser))
  val routes =
    cors() {concat(
      new HelloService(hello).route,
      new AnalyserService(analyserActor).route,
      SwaggerDocService.routes)
    }
  Http().bindAndHandle(routes, "0.0.0.0", 9090)
}
