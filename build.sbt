import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt.Level

ThisBuild / organization := "com.example"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.3"
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-source:future",
  "-experimental"
)

lazy val zioV        = "2.1.21"
lazy val zioJsonV    = "0.7.44"
lazy val zioHttpV    = "3.4.0"
lazy val zioConfigV  = "4.0.5"
lazy val zioLoggingV = "2.5.1"

lazy val postgresV   = "42.7.8"
lazy val flywayV     = "11.14.1"
lazy val logbackV    = "1.5.20"
lazy val argon2V     = "2.12"
lazy val tcV         = "1.21.3"

lazy val laminarV    = "17.2.1"
lazy val scalaJsDomV = "2.8.1"

lazy val sharedSourceDir = file("shared/src/main/scala")
lazy val sharedTestDir   = file("shared/src/test/scala")

lazy val sharedJVM = (project in file("shared-jvm"))
  .settings(
    name := "shared-jvm",
    Compile / unmanagedSourceDirectories += sharedSourceDir,
    Test / unmanagedSourceDirectories += sharedTestDir,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % zioJsonV
    )
  )

lazy val sharedJS = (project in file("shared-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "shared-js",
    Compile / unmanagedSourceDirectories += sharedSourceDir,
    Test / unmanagedSourceDirectories += sharedTestDir,
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-json" % zioJsonV
    )
  )

lazy val backend = (project in file("backend"))
  .dependsOn(sharedJVM)
  .settings(
    name := "backend",
    Compile / unmanagedResourceDirectories += baseDirectory.value / "resources",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"                    % zioV,
      "dev.zio" %% "zio-streams"            % zioV,
      "dev.zio" %% "zio-http"               % zioHttpV,
      "dev.zio" %% "zio-json"               % zioJsonV,
      "dev.zio" %% "zio-config"             % zioConfigV,
      "dev.zio" %% "zio-config-typesafe"    % zioConfigV,
      "dev.zio" %% "zio-logging-slf4j2"     % zioLoggingV,
      "org.postgresql" % "postgresql"       % postgresV,
      "org.flywaydb"  % "flyway-core"       % flywayV,
      "org.flywaydb"  % "flyway-database-postgresql" % flywayV,
      "de.mkammerer" % "argon2-jvm"         % argon2V,
      "ch.qos.logback" % "logback-classic"  % logbackV,
      "dev.zio" %% "zio-test"               % zioV % Test,
      "dev.zio" %% "zio-test-sbt"           % zioV % Test
    ),
    libraryDependencies += "com.google.cloud.tools" % "jib-core" % "0.27.3",
    jibBaseImage := "eclipse-temurin:21-jre",
    jibOrganization := "localhost",
    jibName := "chat-app-backend",
    jibVersion := "latest",
    jibTags := Nil,
    jibTcpPorts := List(8080),
    jibTarget := baseDirectory.value / "target" / "jib" / "chat-app-backend.tar",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true,
    Test / logLevel := Level.Warn
  )

lazy val backendIt = (project in file("backend-it"))
  .dependsOn(backend)
  .settings(
    name := "backend-it",
    Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "backend" / "resources",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"               % zioV % Test,
      "dev.zio" %% "zio-test-sbt"           % zioV % Test,
      "org.testcontainers" % "testcontainers" % tcV % Test,
      "org.testcontainers" % "postgresql"     % tcV % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / fork := true,
    Test / parallelExecution := false,
    Test / logLevel := Level.Warn
  )

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"    %%% "laminar"     % laminarV,
      "org.scala-js" %%% "scalajs-dom" % scalaJsDomV,
      "dev.zio"      %%% "zio-json"    % zioJsonV,
      "org.scalameta" %%% "munit"      % "1.0.0" % Test
    )
  )

lazy val root = (project in file("."))
  .aggregate(sharedJVM, sharedJS, backend, backendIt, frontend)
  .settings(
    name := "chat-app",
    publish / skip := true
  )