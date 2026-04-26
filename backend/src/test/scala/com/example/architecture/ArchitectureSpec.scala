package com.example.architecture

import zio.*
import zio.test.*
import java.io.File
import scala.io.Source

/** Architecture rules enforced via source-level import scanning.
  * ArchUnit's bytecode-based ClassFileImporter is not used here because the
  * bundled ASM version does not support Java 25 class files (major version 69).
  */
object ArchitectureSpec extends ZIOSpecDefault:

  private val srcRoot = new File("src/main/scala/com/example")

  private def allScalaFilesUnder(subDir: String): List[File] =
    val dir = new File(srcRoot, subDir)
    if !dir.exists() then return Nil
    def recurse(f: File): List[File] =
      if f.isDirectory then f.listFiles().toList.flatMap(recurse)
      else if f.getName.endsWith(".scala") then List(f)
      else Nil
    recurse(dir)

  private def containsForbiddenDependency(file: File, forbidden: String): Boolean =
    val src = Source.fromFile(file)
    try
      src.getLines().exists { rawLine =>
        val line = rawLine.trim
        (line.startsWith("import ") || line.startsWith("export ")) && line.contains(forbidden)
      }
    finally src.close()

  private def violations(files: List[File], forbidden: String): List[String] =
    files
      .filter(containsForbiddenDependency(_, forbidden))
      .map(_.getPath)

  override def spec = suite("Architecture rules")(
    test("API routes must not depend directly on infrastructure DB classes") {
      // Main.scala is the composition root and is allowed to wire all layers together
      val bad = violations(allScalaFilesUnder("app"), "com.example.infrastructure.db")
        .filterNot(_.endsWith("Main.scala"))
      assertTrue(bad.isEmpty) ?? s"Violations: ${bad.mkString(", ")}"
    },
    test("Service modules must not depend on API routes") {
      val servicePkgs = List("messaging", "chapters", "auth", "sessions", "groups")
      val bad = servicePkgs.flatMap(pkg => violations(allScalaFilesUnder(pkg), "com.example.app"))
      assertTrue(bad.isEmpty) ?? s"Violations: ${bad.mkString(", ")}"
    },
    test("Infrastructure DB must not depend on service or API packages") {
      val forbidden = List("com.example.app", "com.example.messaging", "com.example.chapters")
      val bad = forbidden.flatMap(f => violations(allScalaFilesUnder("infrastructure"), f))
      assertTrue(bad.isEmpty) ?? s"Violations: ${bad.mkString(", ")}"
    }
  )
