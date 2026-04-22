ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.13"

lazy val root = (project in file("."))
  .settings(
    name := "FunctionalRetailPipeline",
    scalacOptions ++= Seq("-Ymacro-annotations"),
    allowUnsafeScalaLibUpgrade := true,
    javaOptions ++= Seq(
      "-Xmx1G",
      "-Xms1G",
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=200"
    )
  )

val DoobieVersion = "1.0.0-RC12"

libraryDependencies ++= Seq(
  // Core Libraries
  "org.typelevel" %% "cats-effect"    % "3.5.4",
  "org.tpolecat"  %% "doobie-core"     % DoobieVersion,
  "org.tpolecat"  %% "doobie-postgres" % DoobieVersion,
  "org.postgresql" % "postgresql"      % "42.7.3",

  // Streaming for parallel processing
  "co.fs2"        %% "fs2-io"          % "3.9.4",

  // Utilities & Logging
  "org.typelevel"  %% "log4cats-slf4j"  % "2.6.0",
  "ch.qos.logback" %  "logback-classic" % "1.2.13",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
)
