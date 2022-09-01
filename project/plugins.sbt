resolvers += Resolver.sonatypeRepo("snapshots")

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.7")
addSbtPlugin("com.indoorvivants" % "sbt-commandmatrix" % "0.0.5")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
addSbtPlugin(
  "com.indoorvivants" % "subatomic-plugin" % "0.0.7"
)
addSbtPlugin(
  "com.indoorvivants" % "bindgen-sbt-plugin" % "0.0.13"
)

// We want to have all the interface sources available in this project's own build
unmanagedSourceDirectories.in(Compile) +=
  (ThisBuild / baseDirectory).value.getParentFile /
    "interface" / "src" / "main" / "scala"
