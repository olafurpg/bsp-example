inThisBuild(List(
  scalaVersion := "2.12.8"
))

lazy val bill = project.settings(
  libraryDependencies ++= List(
    "ch.epfl.scala" % "bsp4j" % "2.0.0-M2",
    "com.lihaoyi" %% "pprint" % "0.5.3",
    "com.geirsson" %% "coursier-small" % "1.3.1",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0",
    "org.scalatest" %% "scalatest" % "3.0.5" % Test
  )
)
