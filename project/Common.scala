import sbt.Keys._

object Common {
  
  val settings =
    List(
      name := "inu-alpha",
      version := "1.0.4",
      organization := "jaohaohsuan",
      scalaVersion := Version.scala
    )
}
