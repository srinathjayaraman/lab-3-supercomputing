name := "Transformer"
version := "1.0"
scalaVersion := "2.13.3"

scalastyleFailOnWarning := true

fork in run := true
connectInput in run := true
outputStrategy := Some(StdoutOutput)

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-simple" % "1.8.0-beta4",
  "org.apache.kafka" %% "kafka-streams-scala" % "2.6.0",
  "io.github.azhur" %% "kafka-serde-circe" % "0.5.0"
)

val circeVersion = "0.13.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)