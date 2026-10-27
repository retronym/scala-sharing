ThisBuild / scalaVersion := "3.10.0"
ThisBuild / organization := "io.github.retronym"
ThisBuild / licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))

// the site's front page is the README, copied into a generated site root for scaladoc
lazy val siteRoot = taskKey[File]("the scaladoc site root, with the README as its index")

// the README's examples as the compiler expands them, for checking the panels that show each mode's code path
addCommandAlias("printExpansions",
  """; set Test / scalacOptions += "-Vprint:inlining" ; Test / clean ; Test / compile ; set Test / scalacOptions -= "-Vprint:inlining"""")

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
    siteRoot := {
      val dir = target.value / "site-root"
      val readme = IO.read(baseDirectory.value / "README.md")
        .replace("Docs and API: <https://retronym.github.io/scala-sharing/docs/index.html>. ", "Source: <https://github.com/retronym/scala-sharing>. ")
        // the README's links into the API are absolute, for GitHub; on the site, relative to this page (docs/index.html)
        .replace("https://retronym.github.io/scala-sharing/", "../")
      // the tabs' style, which GitHub would strip from the README, as a block at the top of the page
      val style = IO.read(baseDirectory.value / "site" / "tabs.css")
      val body = readme.linesIterator.dropWhile(_.startsWith("# ")).mkString("\n")
      IO.write(dir / "_docs" / "index.md", s"<style>\n$style</style>\n\n$body")
      dir
    },
    Compile / doc / scalacOptions ++= Seq("-siteroot", siteRoot.value.getPath),
    Compile / doc / scalacOptions ++= Seq(
      "-project", "scala-sharing",
      "-project-version", "snapshot",
      "-source-links:github://retronym/scala-sharing/main",
      "-social-links:github::https://github.com/retronym/scala-sharing",
    ),
  )
