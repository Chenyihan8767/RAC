ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "org.yihan"

lazy val root = (project in file("."))
  .settings(
    name := "riscv-ai-accel",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % "6.7.0",
      "org.chipsalliance" %% "chisel" % "6.7.0" % Test,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xsource:2.13"
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.7.0" cross CrossVersion.full),
    Compile / run / fork := true
  )
