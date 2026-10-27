ThisBuild / scalaVersion := "3.10.0"
ThisBuild / organization := "io.github.retronym"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

lazy val root = project.in(file("."))
  .settings(
    name := "scala-sharing",
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src"),
    Test / unmanagedSourceDirectories := Seq(baseDirectory.value / "test"),
    libraryDependencies ++= Seq(
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.jetbrains.kotlinx" % "lincheck-jvm" % "2.39" % Test,
    ),
    Test / fork := true,
  )
