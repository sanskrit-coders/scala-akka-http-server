name := "vedavaapi-swagger-akka-http"

scalaVersion := "2.12.4"

resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.sonatypeRepo("snapshots")

// The library versions should be as mutually compatible as possible - else there will be weird runtime errors.
// We just use whatever we found compatible with akka-http-core in scala-utils_2.12
val akkaVersion = "2.4.20"
val akkaHttpVersion = "10.0.11"

libraryDependencies ++= Seq(
  // REST API documentation with swagger.
  "io.swagger" % "swagger-jaxrs" % "1.5.16",
  // As suggested in https://stackoverflow.com/questions/43574426/how-to-resolve-java-lang-noclassdeffounderror-javax-xml-bind-jaxbexception-in-j
  // to resolve blow-up due to swagger :  java.lang.NoClassDefFoundError: javax/xml/bind/annotation/XmlRootElement.
  "javax.xml.bind" % "jaxb-api" % "2.3.0",
  "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.11.0",

  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,  // The Akka HTTP server
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,  // We use Akka Actor model for concurrent processing.
  "ch.megard" %% "akka-http-cors" % "0.2.1",  // To enable CORS.

  // JSON processing.
  "de.heikoseeberger" %% "akka-http-json4s" % "1.19.0-M2",
  "org.json4s" % "json4s-native_2.12" % "3.5.3"

  ,"com.github.sanskrit-coders" % "scala-utils_2.12" % "0.5"
  ,"com.github.sanskrit-coders" % "indic-transliteration_2.12" % "1.25"
  ,"com.github.sanskrit-coders" % "sanskrit-lttoolbox_2.12" % "0.9" excludeAll(
    // Depends on 2.5.4, which is incompatible with this.
    ExclusionRule("com.typesafe.akka", "akka-actor_2.12")
    )

  // Logging
  ,"com.typesafe.akka" %% "akka-slf4j" % akkaVersion
  ,"org.slf4j" % "slf4j-simple" % "1.7.25"
)
