// Based on https://github.com/lampepfl/dotty/blob/main/project/scripts/bisect.scala

/*
This script will bisect a problem with the compiler based on success/failure of the validation script passed as an argument.
It starts with a fast bisection on released nightly builds.
Then it will bisect the commits between the last nightly that worked and the first nightly that failed.
Look at the `usageMessage` below for more details.
*/




import sys.process._
import scala.io.Source
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.nio.charset.StandardCharsets
import java.nio.file.Files

        //  --extra-scalac-options ${{ inputs.extra-scalac-options }} \
        //     --disabled-scalac-options ${{ inputs.disabled-scalac-options }} \
        //     --community-build-dir ${{ github.workspace }}/opencb

val usageMessage = """
  |Usage:
  |  > scala-cli project/scripts/bisect.scala -- [<bisect-options>] <projectName> <targets>*
  |
  |The optional <bisect-options> may be any combination of:
  |* --dry-run
  |    Don't try to bisect - just make sure the validation command works correctly
  |
  |* --extra-scalac-options <options>
  |    Comma delimited of additional scalacOptions passed to project build
  |
  |* --disabled-scalac-options <options>
  |    Comma delimited of disabled scalacOptions passed to project build
  |
  |* --community-build-dir <path>
  |    Directory with community build project from which the project config would be resolved
  |
  |* --compiler-dir <path>
  |    Directory containing Scala compiler repository, required for commit-based bissect
  |
  |* --releases <releases-range>
  |    Bisect only releases from the given range (defaults to all releases).
  |    The range format is <first>..<last>, where both <first> and <last> are optional, e.g.
  |    * 3.1.0-RC1-bin-20210827-427d313-NIGHTLY..3.2.1-RC1-bin-20220716-bb9c8ff-NIGHTLY
  |    * 3.2.1-RC1-bin-20220620-de3a82c-NIGHTLY..
  |    * ..3.3.0-RC1-bin-20221124-e25362d-NIGHTLY
  |    The ranges are treated as inclusive.
  |
  |* --should-fail
  |    Expect the validation command to fail rather that succeed. This can be used e.g. to find out when some illegal code started to compile.
  |
  |Warning: The bisect script should not be run multiple times in parallel because of a potential race condition while publishing artifacts locally.

""".stripMargin

@main def run(args: String*): Unit =
  val scriptOptions =
    try ScriptOptions.fromArgs(args)
    catch
      case _ =>
        sys.error(s"Wrong script parameters.\n${usageMessage}")

  val validationScript = scriptOptions.validationCommand.validationScript
  val releases = Releases.fromRange(scriptOptions.releasesRange)
  val releaseBisect = ReleaseBisect(validationScript, shouldFail = scriptOptions.shouldFail, releases)

  releaseBisect.verifyEdgeReleases()

  if (!scriptOptions.dryRun) then
    val (lastGoodRelease, firstBadRelease) = releaseBisect.bisectedGoodAndBadReleases()
    println(s"Last good release: ${lastGoodRelease.version}")
    println(s"First bad release: ${firstBadRelease.version}")
    println("\nFinished bisecting releases\n")

    val commitBisect = CommitBisect(validationScript, shouldFail = scriptOptions.shouldFail, lastGoodRelease.hash, firstBadRelease.hash)
    commitBisect.bisect()


case class ScriptOptions(validationCommand: ValidationCommand, dryRun: Boolean, releasesRange: ReleasesRange, shouldFail: Boolean)
object ScriptOptions:
  def fromArgs(args: Seq[String]) =
    val defaultOptions = ScriptOptions(
      validationCommand = null,
      dryRun = false,
      ReleasesRange(first = None, last = None),
      shouldFail = false
    )
    parseArgs(args, defaultOptions)

  private def parseArgs(args: Seq[String], options: ScriptOptions): ScriptOptions =
    println(s"parse: $args")
    args match
      case "--dry-run" :: argsRest => parseArgs(argsRest, options.copy(dryRun = true))
      case "--releases" :: argsRest =>
        val range = ReleasesRange.tryParse(argsRest.head).get
        parseArgs(argsRest.tail, options.copy(releasesRange = range))
      case "--should-fail" :: argsRest => parseArgs(argsRest, options.copy(shouldFail = true))
      case args =>
        val command = ValidationCommand.fromArgs(args)
        options.copy(validationCommand = command)

case class ValidationCommand(projectName: String, openCommunityBuildDir: File,  targets: Seq[String]):
  val remoteValidationScript: File = ValidationScript.buildProject(
    projectName = projectName,
    targets = Option.when(targets.nonEmpty)(targets.mkString(" ")),
    extraScalacOptions = "",
    disabledScalacOption="",
    runId ="test",
    buildURL="",
    executeTests = false
  )
  val validationScript: File = ValidationScript.dockerRunBuildProject(projectName, remoteValidationScript, openCommunityBuildDir)

object ValidationCommand:
  def fromArgs(args: Seq[String]) =
    args match
      case Seq(projectName, openCBDir, targets*) => ValidationCommand(projectName, new File(openCBDir), targets)


object ValidationScript:
  def buildProject(projectName: String, targets: Option[String], extraScalacOptions: String, disabledScalacOption: String, runId: String, buildURL: String, executeTests: Boolean): File = tmpScript{
    val configPatch =
      if executeTests
      then ""
      else """* { "tests": "compile-only"} """
    raw"""
    |#!/usr/bin/env bash
    |set -e
    |
    |scalaVersion="$$1"       # e.g. 3.3.3
    |
    |DefaultConfig='{"memoryRequestMb":4096}'
    |ConfigFile="/opencb/.github/workflows/buildConfig.json"
    |if [[ ! -f "$$ConfigFile" ]]; then
    |    echo "Not found build config: $$ConfigFile"
    |    exit 1
    |fi
    |config () {
    |  path=".\"${projectName}\"$$@"
    |  jq -c -r "$$path" $$ConfigFile
    |}
    |
    |touch build-logs.txt  build-summary.txt
    |# Assume failure unless overwritten by a successful build
    |echo 'failure' > build-status.txt
    |
    |/build/build-revision.sh \
    |  "$$(config .repoUrl)" \
    |  "$$(config .revision)" \
    |  "$${scalaVersion}" \
    |  "" \
    |  "${targets.getOrElse("$(config .targets)")}" \
    |  "https://scala3.westeurope.cloudapp.azure.com/maven2/bisect/" \
    |  '1.6.2' \
    |  "$$(config .config '$configPatch' // $${DefaultConfig} '$configPatch')" \
    |  "$extraScalacOptions" \
    |  "$disabledScalacOption"
    |
    |#/build/feed-elastic.sh \
    |#  'https://scala3.westeurope.cloudapp.azure.com/data' \
    |#  "${projectName}" \
    |#  "$$(cat build-status.txt)" \
    |#  "$$(date --iso-8601=seconds)" \
    |#  build-summary.txt \
    |#  build-logs.txt \
    |#  "$$(config .version)" \
    |#  "$${scalaVersion}" \
    |#  "${runId}" \
    |#  "${buildURL}"
    |#
    |#if [ $$? != 0 ]; then
    |#  echo "::warning title=Indexing failure::Indexing results of ${projectName} failed"
    |#fi
    |
    |grep -q "success" build-status.txt;
    |exit $$?
    """.stripMargin
  }

  def dockerRunBuildProject(projectName: String, validationScript: File, openCBDir: File): File =
    val validationScriptPath="/scripts/validationScript.sh"
    val imageVersion = "v0.2.4"
    tmpScript(raw"""
      |#!/usr/bin/env bash
      |set -e
      |scalaVersion=$$1
      |ConfigFile="${openCBDir.toPath().resolve(".github/workflows/buildConfig.json").toAbsolutePath()}"
      |DefaultJDK=11
      |javaVersion=$$(jq -r ".\"${projectName}\".config.java.version // $${DefaultJDK}" $$ConfigFile)
      |docker run --rm \
      |  -v ${validationScript.getAbsolutePath()}:$validationScriptPath \
      |  -v ${openCBDir.getAbsolutePath()}:/opencb/ \
      |  virtuslab/scala-community-build-project-builder:jdk$${javaVersion}-$imageVersion \
      |  /bin/bash $validationScriptPath $$scalaVersion
    """.stripMargin)

  private def tmpScript(content: String): File =
    val executableAttr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
    val tmpPath = Files.createTempFile("scala-bisect-validator", "", executableAttr)
    val tmpFile = tmpPath.toFile

    print(s"Bisecting with validation script: ${tmpPath.toAbsolutePath}\n")
    print("#####################################\n")
    print(s"${content}\n\n")
    print("#####################################\n\n")

    tmpFile.deleteOnExit()
    Files.write(tmpPath, content.getBytes(StandardCharsets.UTF_8))
    tmpFile


case class ReleasesRange(first: Option[String], last: Option[String]):
  def filter(releases: Seq[Release]) =
    def releaseIndex(version: String): Int =
      val index = releases.indexWhere(_.version == version)
      assert(index > 0, s"${version} matches no nightly compiler release")
      index

    val startIdx = first.map(releaseIndex(_)).getOrElse(0)
    val endIdx = last.map(releaseIndex(_) + 1).getOrElse(releases.length)
    val filtered = releases.slice(startIdx, endIdx).toVector
    assert(filtered.nonEmpty, "No matching releases")
    filtered

object ReleasesRange:
  def all = ReleasesRange(None, None)
  def tryParse(range: String): Option[ReleasesRange] = range match
    case s"${first}..${last}" => Some(ReleasesRange(
      Some(first).filter(_.nonEmpty),
      Some(last).filter(_.nonEmpty)
    ))
    case _ => None

class Releases(val releases: Vector[Release])

object Releases:
  lazy val allReleases: Vector[Release] =
    val re = raw"""(?<=title=")(.+-bin-\d{8}-\w{7}-NIGHTLY)(?=/")""".r
    val html = Source.fromURL("https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/")
    re.findAllIn(html.mkString).map(Release.apply).toVector

  def fromRange(range: ReleasesRange): Vector[Release] = range.filter(allReleases)

case class Release(version: String):
  private val re = raw".+-bin-(\d{8})-(\w{7})-NIGHTLY".r
  def date: String =
    version match
      case re(date, _) => date
      case _ => sys.error(s"Could not extract date from release name: $version")
  def hash: String =
    version match
      case re(_, hash) => hash
      case _ => sys.error(s"Could not extract hash from release name: $version")

  override def toString: String = version


class ReleaseBisect(validationScript: File, shouldFail: Boolean, allReleases: Vector[Release]):
  assert(allReleases.length > 1, "Need at least 2 releases to bisect")

  private val isGoodReleaseCache = collection.mutable.Map.empty[Release, Boolean]

  def verifyEdgeReleases(): Unit =
    println(s"Verifying the first release: ${allReleases.head.version}")
    assert(isGoodRelease(allReleases.head), s"The evaluation script unexpectedly failed for the first checked release")
    println(s"Verifying the last release: ${allReleases.last.version}")
    assert(!isGoodRelease(allReleases.last), s"The evaluation script unexpectedly succeeded for the last checked release")

  def bisectedGoodAndBadReleases(): (Release, Release) =
    val firstBadRelease = bisect(allReleases)
    assert(!isGoodRelease(firstBadRelease), s"Bisection error: the 'first bad release' ${firstBadRelease.version} is not a bad release")
    val lastGoodRelease = firstBadRelease.previous
    assert(isGoodRelease(lastGoodRelease), s"Bisection error: the 'last good release' ${lastGoodRelease.version} is not a good release")
    (lastGoodRelease, firstBadRelease)

  extension (release: Release) private def previous: Release =
    val idx = allReleases.indexOf(release)
    allReleases(idx - 1)

  private def bisect(releases: Vector[Release]): Release =
    if releases.length == 2 then
      if isGoodRelease(releases.head) then releases.last
      else releases.head
    else
      val mid = releases(releases.length / 2)
      if isGoodRelease(mid) then bisect(releases.drop(releases.length / 2))
      else bisect(releases.take(releases.length / 2 + 1))

  private def isGoodRelease(release: Release): Boolean =
    isGoodReleaseCache.getOrElseUpdate(release, {
      println(s"Testing ${release.version}")
      val result = Seq(validationScript.getAbsolutePath, release.version).!
      val isGood = if(shouldFail) result != 0 else result == 0 // invert the process status if failure was expected
      println(s"Test result: ${release.version} is a ${if isGood then "good" else "bad"} release\n")
      isGood
    })

class CommitBisect(validationScript: File, shouldFail: Boolean, lastGoodHash: String, fistBadHash: String):
  def bisect(): Unit =
    println(s"Starting bisecting commits $lastGoodHash..$fistBadHash\n")
    // Always bootstrapped
    val scala3CompilerProject = "scala3-compiler-bootstrapped"
    val scala3Project = "scala3-bootstrapped"
    val mavenRepo = "https://scala3.westeurope.cloudapp.azure.com/maven2/bisect/"
    val validationCommandStatusModifier = if shouldFail then "! " else "" // invert the process status if failure was expected
    val bisectRunScript = raw"""
      |export NIGHTLYBUILD=yes
      |scalaVersion=$$(sbt "print ${scala3CompilerProject}/version" | tail -n1)
      |rm -r out
      |sbt "clean; set every sonatypePublishToBundle := Some(\"CommunityBuildRepo\" at \"$mavenRepo\"); ${scala3Project}/publish"
      |${validationCommandStatusModifier}${validationScript.getAbsolutePath} "$$scalaVersion"
    """.stripMargin
    "git bisect start".!
    s"git bisect bad $fistBadHash".!
    s"git bisect good $lastGoodHash".!
    Seq("git", "bisect", "run", "sh", "-c", bisectRunScript).!
    s"git bisect reset".!
