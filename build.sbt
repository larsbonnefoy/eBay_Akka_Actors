val scala3Version = "3.5.2"
val AkkaVersion = "2.10.0"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,

  "ch.qos.logback" % "logback-classic" % "1.4.11", // Logback Classic
  "org.slf4j" % "slf4j-api" % "2.0.0" // SLF4J API
)

lazy val root = project
  .in(file("."))
  .settings(
    name := "akka_actors",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
  )
