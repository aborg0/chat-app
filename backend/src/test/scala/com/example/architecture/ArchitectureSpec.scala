package com.example.architecture

import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption}
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

object ArchitectureSpec extends ZIOSpecDefault:

  private val servicePackages = Seq(
    "com.example.auth..",
    "com.example.messaging..",
    "com.example.chapters..",
    "com.example.groups..",
    "com.example.sessions.."
  )

  private val topLevelPackages = Set(
    "app",
    "auth",
    "chapters",
    "groups",
    "infrastructure",
    "messaging",
    "notifications",
    "oauth",
    "observability",
    "sessions"
  )

  private val importedClasses =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("com.example")

  private def assertRule(rule: ArchRule): TestResult =
    try
      rule.check(importedClasses)
      assertTrue(true)
    catch
      case e: AssertionError => assertTrue(false) ?? e.getMessage

  private def topLevelPackageOf(fqcnOrPackage: String): Option[String] =
    val clean = fqcnOrPackage.stripPrefix("com.example.")
    clean.split("\\.").headOption.filter(topLevelPackages.contains)

  private final case class PackageMetrics(ca: Int, ce: Int):
    def instability: Double =
      val denom = ca + ce
      if denom == 0 then 0.0 else ce.toDouble / denom.toDouble

  private final case class ArchitectureMetrics(
    packageMetrics: Map[String, PackageMetrics],
    boundaryCrossingRatio: Double,
    crossLayerEdgeCount: Int,
    totalEdgeCount: Int,
    maxOutDegree: Int
  )

  private def computeMetrics: ArchitectureMetrics =
    val classes = importedClasses.asScala.toSeq

    val sameCodebaseEdges = classes.flatMap { sourceClass =>
      val sourcePkg = topLevelPackageOf(sourceClass.getPackageName)
      sourceClass.getDirectDependenciesFromSelf.asScala.flatMap { dep =>
        val targetPkg = topLevelPackageOf(dep.getTargetClass.getPackageName)
        for
          s <- sourcePkg
          t <- targetPkg
        yield s -> t
      }
    }

    val totalEdges = sameCodebaseEdges.size
    val crossLayerEdges = sameCodebaseEdges.count { case (s, t) => s != t }
    val boundaryCrossingRatio =
      if totalEdges == 0 then 0.0 else crossLayerEdges.toDouble / totalEdges.toDouble

    val outgoingByPkg = sameCodebaseEdges.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap
    val incomingByPkg = sameCodebaseEdges.groupBy(_._2).view.mapValues(_.map(_._1).toSet).toMap

    val allPkgs = outgoingByPkg.keySet ++ incomingByPkg.keySet
    val packageMetrics = allPkgs.map { pkg =>
      val ca = incomingByPkg.getOrElse(pkg, Set.empty).size
      val ce = outgoingByPkg.getOrElse(pkg, Set.empty).size
      pkg -> PackageMetrics(ca = ca, ce = ce)
    }.toMap

    val maxOutDegree = outgoingByPkg.values.map(_.size).foldLeft(0)(Math.max)

    ArchitectureMetrics(
      packageMetrics = packageMetrics,
      boundaryCrossingRatio = boundaryCrossingRatio,
      crossLayerEdgeCount = crossLayerEdges,
      totalEdgeCount = totalEdges,
      maxOutDegree = maxOutDegree
    )

  private val appMustNotDependOnDbRule =
    noClasses
      .that()
      .haveNameMatching("com\\.example\\.app\\.ApiRoutes.*")
      .should()
      .dependOnClassesThat()
      .resideInAPackage("com.example.infrastructure.db..")

  private val servicesMustNotDependOnAppRule =
    noClasses
      .that()
      .resideInAnyPackage(servicePackages*)
      .should()
      .dependOnClassesThat()
      .resideInAPackage("com.example.app..")

  private val coreServicesMustNotDependOnAppConfigRule =
    noClasses
      .that()
      .resideInAnyPackage(servicePackages*)
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "com.example.app.AppConfig",
        "com.example.app.DbConfig",
        "com.example.app.HttpConfig",
        "com.example.app.OAuthConfig",
        "com.example.app.OAuthProviderConfig"
      )

  private val infraMustNotDependOnUpperLayersRule =
    noClasses
      .that()
      .resideInAPackage("com.example.infrastructure..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage((Seq("com.example.app..") ++ servicePackages)*)

  private val nonAppMustNotDependOnTransportRule =
    noClasses
      .that()
      .resideOutsideOfPackage("com.example.app..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("zio.http..")

  private val authShouldNotDependOnOtherCoreModulesRule =
    noClasses
      .that()
      .resideInAPackage("com.example.auth..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("com.example.messaging..", "com.example.chapters..", "com.example.groups..")

  private val sessionsShouldNotDependOnOtherCoreModulesRule =
    noClasses
      .that()
      .resideInAPackage("com.example.sessions..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("com.example.auth..", "com.example.messaging..", "com.example.chapters..", "com.example.groups..")

  private val groupsShouldNotDependOnOtherCoreModulesRule =
    noClasses
      .that()
      .resideInAPackage("com.example.groups..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("com.example.auth..", "com.example.messaging..", "com.example.chapters..", "com.example.sessions..")

  private val messagingShouldNotDependOnOtherCoreModulesRule =
    noClasses
      .that()
      .resideInAPackage("com.example.messaging..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("com.example.auth..", "com.example.chapters..", "com.example.groups..", "com.example.sessions..")

  private val chaptersShouldNotDependOnCoreModulesExceptMessagingRule =
    noClasses
      .that()
      .resideInAPackage("com.example.chapters..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage("com.example.auth..", "com.example.groups..", "com.example.sessions..")

  private val topLevelPackagesShouldBeAcyclicRule =
    slices()
      .matching("com.example.(*)..")
      .should()
      .beFreeOfCycles()

  override def spec = suite("Architecture rules")(
    test("App layer should not depend on DB infrastructure outside composition root") {
      assertRule(appMustNotDependOnDbRule)
    },
    test("Service modules should not depend on app layer") {
      assertRule(servicesMustNotDependOnAppRule)
    },
    test("Core service modules should not depend on app configuration types") {
      assertRule(coreServicesMustNotDependOnAppConfigRule)
    },
    test("Infrastructure should not depend on service or app layers") {
      assertRule(infraMustNotDependOnUpperLayersRule)
    },
    test("Non-app packages should not depend on transport types") {
      assertRule(nonAppMustNotDependOnTransportRule)
    },
    test("Auth module should be independent of other core domain modules") {
      assertRule(authShouldNotDependOnOtherCoreModulesRule)
    },
    test("Sessions module should be independent of other core domain modules") {
      assertRule(sessionsShouldNotDependOnOtherCoreModulesRule)
    },
    test("Groups module should be independent of other core domain modules") {
      assertRule(groupsShouldNotDependOnOtherCoreModulesRule)
    },
    test("Messaging module should be independent of other core domain modules") {
      assertRule(messagingShouldNotDependOnOtherCoreModulesRule)
    },
    test("Chapters module should only depend on messaging among core modules") {
      assertRule(chaptersShouldNotDependOnCoreModulesExceptMessagingRule)
    },
    test("Top-level backend packages should be acyclic") {
      assertRule(topLevelPackagesShouldBeAcyclicRule)
    },
    test("Architecture metrics should stay within agreed guardrails") {
      val metrics = computeMetrics

      val maxInstability =
        metrics.packageMetrics.values.map(_.instability).foldLeft(0.0)(Math.max)

      val metricsLine =
        s"ARCH_METRICS|boundaryCrossingRatio=${metrics.boundaryCrossingRatio}|crossLayerEdgeCount=${metrics.crossLayerEdgeCount}|totalEdgeCount=${metrics.totalEdgeCount}|maxOutDegree=${metrics.maxOutDegree}|maxInstability=$maxInstability"
      println(metricsLine)

      val report =
        s"""boundaryCrossingRatio=${metrics.boundaryCrossingRatio}
           |crossLayerEdgeCount=${metrics.crossLayerEdgeCount}
           |totalEdgeCount=${metrics.totalEdgeCount}
           |maxOutDegree=${metrics.maxOutDegree}
           |maxInstability=$maxInstability""".stripMargin

      assertTrue(metrics.totalEdgeCount > 0) ?? report &&
      assertTrue(metrics.boundaryCrossingRatio <= 0.50) ?? report &&
      assertTrue(metrics.maxOutDegree <= 8) ?? report &&
      assertTrue(maxInstability <= 1.0) ?? report
    }
  )
