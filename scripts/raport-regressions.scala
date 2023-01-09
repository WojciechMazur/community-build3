#!/usr/bin/env -S scala-cli shebang
//> using scala "3"
//> using lib "com.sksamuel.elastic4s:elastic4s-client-esjava_2.13:8.5.1"
//> using lib "org.slf4j:slf4j-simple:2.0.5"

import com.sksamuel.elastic4s
import elastic4s.*
import elastic4s.http.JavaClient
import elastic4s.ElasticDsl.*
import elastic4s.requests.searches.aggs.TermsOrder
import elastic4s.requests.searches.*

import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.auth.*

import scala.io.Source
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.io.AnsiColor.*

given ExecutionContext = ExecutionContext.global

val BuildSummariesIndex = "project-build-summary"
val DefaultTimeout = 5.minutes
val ElasticsearchUrl = "https://localhost:9200"
// ./scripts/show-elastic-credentials.sh
val ElasticsearchCredentials = new UsernamePasswordCredentials(
  sys.env.getOrElse("ES_USERNAME", "elastic"),
  sys.env.getOrElse("ES_PASSWORD", "changeme")
)

lazy val NightlyReleases = {
  val re = raw"(?<=title=$")(.+-bin-\d{8}-\w{7}-NIGHTLY)(?=/$")".r
  val html = Source.fromURL("https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/")
  re.findAllIn(html.mkString).toVector
}

lazy val StableScalaVersions = {
  val re = raw"(?<=title=$")(\d+\.\d+\.\d+(-RC\d+)?)(?=/$")".r
  val html = Source.fromURL("https://repo1.maven.org/maven2/org/scala-lang/scala3-compiler_3/")
  re.findAllIn(html.mkString).toVector
}
def PreviousScalaReleases = NightlyReleases

// Report all community build filures for given Scala version
@main def raportForScalaVersion(scalaVersion: String, opts: String*) =
  val compareWithScalaVersion = opts.collectFirst {
    case opt if opt.contains("-compareWith=") => opt.dropWhile(_ != '=').tail
  }
  printLine()
  println(s"Reporting failed community build projects using Scala $scalaVersion")
  val failedProjects = listFailedProjects(scalaVersion)
  printLine()
  val reportedProjects = compareWithScalaVersion
    .foldRight(failedProjects) { case (comparedVersion, failedNow) =>
      println(s"Excluding projects failing already in $comparedVersion")
      val ignoredProjects =
        listFailedProjects(comparedVersion, logFailed = false)
          .map(_.project)
          .toSet
      failedNow.filter(p => !ignoredProjects.contains(p.project))
    }
  if reportedProjects.nonEmpty then
    reportCompilerRegressions(reportedProjects, scalaVersion)(Reporter.Default(scalaVersion))
  printLine()
  esClient.close()

case class Project(searchName: String) extends AnyVal {
  def orgRepoName = searchName.replace("_", "/")
}
case class FailedProject(project: Project, version: String, buildURL: String)
type SourceFields = Map[String, AnyRef]
// Unsafe API to check project summary results in all the modules
extension (summary: List[SourceFields])
  def compilerFailure: Boolean = existsModuleThat {
    hasFailedIn("compile") || hasFailedIn("test-compile")
  }
  def testsFailure: Boolean = existsModuleThat(hasFailedIn("test"))
  def publishFailure: Boolean = existsModuleThat(hasFailedIn("publish"))
  def docFailure: Boolean = existsModuleThat(hasFailedIn("doc"))

  private def hasFailedIn(task: String)(using module: SourceFields) = {
    val taskResults = module(task).asInstanceOf[SourceFields]
    taskResults("status") == "failed"
  }
  private def existsModuleThat(pred: SourceFields ?=> Boolean) = summary.exists(pred(using _))
end extension

def listFailedProjects(scalaVersion: String, logFailed: Boolean = true): Seq[FailedProject] =
  val Limit = 1000
  val projectVersionsStatusAggregation =
    termsAgg("versions", "version")
      .order(TermsOrder("buildTimestamp", asc = false))
      .subaggs(
        maxAgg("buildTimestamp", "timestamp"),
        termsAgg("status", "status")
      )
      .size(20) // last 5 versions

  def process(resp: SearchResponse): Seq[FailedProject] = {
    val projectVersions = resp.aggs
      .terms("failedProjects")
      .buckets
      .map { bucket =>
        val name = bucket.key
        val lastVersion = bucket.terms("versions").buckets.head.key
        name -> lastVersion
      }
      .toMap

    def hasNewerPassingVersion(project: Project, failedVersion: String) =
      esClient
        .execute {
          search(BuildSummariesIndex)
            .query(
              boolQuery().must(
                termQuery("projectName.keyword", project.searchName),
                termQuery("status", "success"),
                termQuery("scalaVersion", scalaVersion)
              )
            )
            .sourceInclude("version")
            .sortBy(fieldSort("timestamp").desc())
        }
        .map(_.map(_.hits.hits.exists { result =>
          isVersionNewerOrEqualThen(
            version = result.sourceField("version").asInstanceOf[String],
            reference = failedVersion
          )
        }))
        .await(DefaultTimeout)
        .result
    end hasNewerPassingVersion

    resp.hits.hits
      .map(_.sourceAsMap)
      .distinctBy(_("projectName"))
      .flatMap { fields =>
        val project = Project(fields("projectName").asInstanceOf[String])
        val summary = fields("summary").asInstanceOf[List[SourceFields]]
        val buildURL = fields("buildURL").asInstanceOf[String]
        val lastFailedVersion = projectVersions(project.searchName)

        import scala.io.AnsiColor.{RED, YELLOW, MAGENTA, RESET, BOLD}
        def logProject(label: String)(color: String) = if logFailed then
          println(
            s"$color${label.padTo(8, " ").mkString}$RESET failure in $BOLD${project.orgRepoName} @ ${projectVersions(
              project.searchName
            )}$RESET - $buildURL"
          )
        val compilerFailure = summary.compilerFailure
        if hasNewerPassingVersion(project, lastFailedVersion) then None // ignore failure
        else
          if summary.compilerFailure then logProject("COMPILER")(RED)
          if summary.testsFailure then logProject("TEST")(YELLOW)
          if summary.docFailure then logProject("DOC")(MAGENTA)
          if summary.publishFailure then logProject("PUBLISH")(MAGENTA)
          Option.when(compilerFailure) {
            FailedProject(
              project,
              version = lastFailedVersion,
              buildURL = buildURL
            )
          }
      }
  }

  esClient
    .execute {
      search(BuildSummariesIndex)
        .query(
          boolQuery()
            .must(
              termQuery("scalaVersion", scalaVersion),
              termQuery("status", "failure")
            )
        )
        .size(Limit)
        .sourceInclude("projectName", "summary", "buildURL")
        .sortBy(fieldSort("projectName.keyword"), fieldSort("timestamp").desc())
        .aggs(
          termsAgg("failedProjects", "projectName.keyword")
            .size(Limit)
            .subaggs(projectVersionsStatusAggregation)
        )
    }
    .await(DefaultTimeout)
    .fold(
      reportFailedQuery("GetFailedQueries").andThen(_ => Nil),
      process(_)
    )

end listFailedProjects

case class ProjectHistoryEntry(
    project: Project,
    scalaVersion: String,
    version: String,
    isCurrentVersion: Boolean,
    compilerFailure: Boolean
)
def projectHistory(project: FailedProject) =
  esClient
    .execute {
      search(BuildSummariesIndex)
        .query {
          boolQuery()
            .must(
              termsQuery("scalaVersion", PreviousScalaReleases),
              termQuery("projectName.keyword", project.project.searchName)
            )
            .should(
              termQuery("version", project.version)
            )
        }
        .sortBy(
          fieldSort("scalaVersion").desc(),
          fieldSort("timestamp").desc()
        )
        .sourceInclude("scalaVersion","version","summary")
    }
    .map(
      _.fold[Seq[ProjectHistoryEntry]](
        reportFailedQuery(s"Project build history ${project.project.orgRepoName}")
          .andThen(_ => Nil),
        _.hits.hits
          .map(_.sourceAsMap)
          .distinctBy(fields => (fields("scalaVersion"), fields("version")))
          .map { fields =>
            val version = fields("version").asInstanceOf[String]
            val isCurrentVersion = project.version == version
            ProjectHistoryEntry(
              project.project,
              scalaVersion = fields("scalaVersion").asInstanceOf[String],
              version = fields("version").asInstanceOf[String],
              isCurrentVersion = isCurrentVersion,
              compilerFailure = fields("summary").asInstanceOf[List[SourceFields]].compilerFailure
            )
          }
      )
    )
    .await(DefaultTimeout)
end projectHistory

trait Reporter {
  def prelude: String = ""
  def report(
      scalaVersion: String,
      failedProjects: Map[Project, FailedProject],
      sameVersionRegressions: Seq[ProjectHistoryEntry],
      diffVersionRegressions: Seq[ProjectHistoryEntry]
  ): Unit
}
object Reporter:
  class Default(testedScalaVersion: String) extends Reporter:
    override def prelude: String = ""

    override def report(
        scalaVersion: String,
        failedProjects: Map[Project, FailedProject],
        sameVersionRegressions: Seq[ProjectHistoryEntry],
        diffVersionRegressions: Seq[ProjectHistoryEntry]
    ): Unit = {
      def showRow(project: String, version: String, buildURL: String, issueURL: String = "") =
        println(s"| $project | $version | $issueURL | $buildURL |")
      val allRegressions = sameVersionRegressions ++ diffVersionRegressions
      printLine()
      println(
        s"Projects with last successful builds using Scala <b>$BOLD$scalaVersion$RESET</b> [${allRegressions.size}]:"
      )
      showRow("Project", "Version", "Build URL", "Reproducer issue")
      showRow("-------", "-------", "---------", "----------------")
      for p <- allRegressions do
        val version = failedProjects
          .get(p.project)
          .collect {
            case failed if p.version != failed.version => s"${p.version} -> ${failed.version}"
          }
          .getOrElse(p.version)
        val buildUrl = {
          val url = failedProjects(p.project).buildURL
          val buildId = url.split("/").reverse.dropWhile(_.isEmpty).head
          s"[Open CB #$buildId]($url)"
        }
        showRow(p.project.orgRepoName, version, buildUrl)
    }
  end Default

private def reportCompilerRegressions(
    projects: Seq[FailedProject],
    scalaVersion: String
)(reporter: Reporter): Unit =
  val failedProjectHistory =
    projects
      .zip(projects.map(this.projectHistory))
      .toMap

  val failedProjects = projects.map(v => v.project -> v).toMap
  val projectHistory = failedProjectHistory.map { (key, value) => key.project -> value }
  val allHistory = projectHistory.values.flatten.toSeq

  if reporter.prelude.nonEmpty then
    printLine()
    println(reporter.prelude)
  val alwaysFailing =
    PreviousScalaReleases.reverse
      .dropWhile(isVersionNewerOrEqualThen(_, scalaVersion))
      .foldLeft(failedProjects.keySet) { case (prev, scalaVersion) =>
        def regressionsSinceLastVersion(exactVersion: Boolean) = allHistory
          .filter(v =>
            v.scalaVersion == scalaVersion &&
              v.isCurrentVersion == exactVersion &&
              !v.compilerFailure && // was successful in checked version
              prev.contains(v.project) // failed in newer version
          )
          .sortBy(_.project.searchName)
        val sameVersionRegressions = regressionsSinceLastVersion(exactVersion = true)
        val diffVersionRegressions = regressionsSinceLastVersion(exactVersion = false)
          .diff(sameVersionRegressions)
        val allRegressions = sameVersionRegressions ++ diffVersionRegressions

        if allRegressions.isEmpty then prev
        else
          reporter.report(
            scalaVersion,
            failedProjects,
            sameVersionRegressions,
            diffVersionRegressions
          )
          prev.diff(allRegressions.map(_.project).toSet)
      }
  if alwaysFailing.nonEmpty then
    reporter.report(
      "with no successful builds data",
      failedProjects,
      alwaysFailing
        .map { project =>
          projectHistory(project)
            .find(_.scalaVersion == scalaVersion)
            .getOrElse(
              ProjectHistoryEntry(
                project = project,
                scalaVersion = scalaVersion,
                version = "",
                isCurrentVersion = true,
                compilerFailure = true
              )
            )
        }
        .toSeq
        .sortBy(_.project.orgRepoName),
      Nil
    )

end reportCompilerRegressions

def printLine() = println("- " * 40)
def reportFailedQuery(msg: String)(err: RequestFailure) =
  System.err.println(s"Query failure - $msg\n${err.error}")
def isVersionNewerThen(version: String, reference: String) =
  val List(ref, ver) = List(reference, version)
    .map(_.split("[.-]").flatMap(_.toIntOption))
  if reference.startsWith(version) && ref.length > ver.length then true
  else if version.startsWith(reference) && ver.length > ref.length then false
  else
    val maxLength = ref.length max ver.length
    def loop(ref: List[Int], ver: List[Int]): Boolean =
      (ref, ver) match {
        case (r :: nextRef, v :: nextVer) =>
          if v > r then true
          else if v < r then false
          else loop(nextRef, nextVer)
        case _ => false
      }
    end loop
    loop(ref.padTo(maxLength, 0).toList, ver.padTo(maxLength, 0).toList)
end isVersionNewerThen
def isVersionNewerOrEqualThen(version: String, reference: String) =
  version == reference || isVersionNewerThen(version, reference)

lazy val esClient =
  val clientConfig = new HttpClientConfigCallback {
    override def customizeHttpClient(
        httpClientBuilder: HttpAsyncClientBuilder
    ): HttpAsyncClientBuilder = {
      val creds = new BasicCredentialsProvider()
      creds.setCredentials(AuthScope.ANY, ElasticsearchCredentials)
      httpClientBuilder
        .setDefaultCredentialsProvider(creds)
        // Custom SSL context that would not require ES certificate
        .setSSLContext(unsafe.UnsafeSSLContext)
        .setHostnameVerifier(unsafe.VerifiesAllHostNames)
    }
  }

  ElasticClient(
    JavaClient(
      ElasticProperties(ElasticsearchUrl),
      clientConfig
    )
  )
end esClient

// Used to connect in localhost port-forwarding to K8s cluster without certifacates
object unsafe:
  import javax.net.ssl.{SSLSocket, SSLSession}
  import java.security.cert.X509Certificate
  import org.apache.http.conn.ssl.X509HostnameVerifier
  object VerifiesAllHostNames extends X509HostnameVerifier {
    override def verify(x: String, y: SSLSession): Boolean = true
    override def verify(x: String, y: SSLSocket): Unit = ()
    override def verify(x: String, y: X509Certificate): Unit = ()
    override def verify(x: String, y: Array[String], x$2: Array[String]): Unit = ()
  }

  lazy val UnsafeSSLContext = {
    object TrustAll extends javax.net.ssl.X509TrustManager {
      override def getAcceptedIssuers(): Array[X509Certificate] = Array()
      override def checkClientTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
      override def checkServerTrusted(
          x509Certificates: Array[X509Certificate],
          s: String
      ) = ()
    }

    val instance = javax.net.ssl.SSLContext.getInstance("SSL")
    instance.init(null, Array(TrustAll), new java.security.SecureRandom())
    instance
  }
end unsafe