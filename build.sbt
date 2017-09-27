name := "vedavaapi-swagger-akka-http"

scalaVersion := "2.12.3"

resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

val akkaVersion = "2.5.4"
val akkaHttpVersion = "10.0.10"

libraryDependencies ++= Seq(
  // REST API documentation with swagger.
  "io.swagger" % "swagger-jaxrs" % "1.5.16",
  "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.11.0",

  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,  // The Akka HTTP server
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,  // We use Akka Actor model for concurrent processing.
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,  // ??
  "ch.megard" %% "akka-http-cors" % "0.2.1",  // To enable CORS.

  // JSON processing.
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "de.heikoseeberger" %% "akka-http-json4s" % "1.19.0-M2",
  "org.json4s" % "json4s-native_2.12" % "3.5.3",

  // Logging
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "org.slf4j" % "slf4j-simple" % "1.7.25"
)
