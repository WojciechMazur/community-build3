import org.jsoup._
import collection.JavaConverters._
import java.nio.file._
import scala.sys.process._

trait CacheDriver[K, T]:
  def write(v: T): String
  def load(data: String, key: K): T
  def dest(k: K): Path

def cachedSingle[V](dest: String)(op: => V)(using CacheDriver[String, V]): V =  
  cached((_: String) => op)(dest)

def cached[V, K](op: K => V)(using cacheDriver: CacheDriver[K, V]): K => V = 
  (k: K) => 
    val dest = cacheDriver.dest(k)
    try
      if Files.exists(dest) then cacheDriver.load(String(Files.readAllBytes(dest)), k)
      else 
        val res = op(k)
        Files.createDirectories(dest.getParent)
        Files.write(dest, cacheDriver.write(res).getBytes)
        res
    catch
      case e: Throwable =>
        throw new RuntimeException(s"When loading $dest", e)


val dataPath = Paths.get("data")

given CacheDriver[Project, ProjectModules] with
  def write(v: ProjectModules): String = 
    v.mvs.map(mv => (mv.version +: mv.modules).mkString(",")).mkString("\n")

  def load(data: String, key: Project): ProjectModules = 
    val mvs = data.linesIterator.toSeq.map { l =>
      val v +: mds = l.split(",").toSeq
      ModuleInVersion(v, mds)
    }
    ProjectModules(key, mvs)

  def dest(v: Project): Path = 
    dataPath.resolve("projectModules").resolve(v.org +"_" + v.name + ".csv")

given CacheDriver[String, Seq[Project]] with 
  def write(v: Seq[Project]): String = 
    v.map(p => Seq(p.org,p.name,p.stars).mkString(",")).mkString("\n")

  def load(data: String, k: String): Seq[Project] = 
    data.linesIterator.map{
      l => 
        val d = l.split(",")
        Project(d(0), d(1))(d(2).toInt)
    }.toSeq

  def dest(v: String): Path = dataPath.resolve(v)


object DepOps:
  def write(d: Dep) = Seq(d.id.org, d.id.name, d.version).mkString("%")
  def load(l: String): Dep = 
    val d = l.split('%')
    Dep(TargetId(d(0), d(1)), d(2))

given CacheDriver[ModuleVersion, Target] with 
  def write(v: Target): String = 
    (Dep(v.id, "_") +: v.deps).map(DepOps.write).mkString("\n")

  def load(data: String, key: ModuleVersion): Target = 
    val Dep(id, _) +: deps = data.linesIterator.toSeq.map(DepOps.load)
    Target(id, deps)

  def dest(v: ModuleVersion): Path = 
    val fn = Seq(v.p.org, v.p.name, v.name, v.version).mkString("", "_", ".csv")
    dataPath.resolve("targets").resolve(fn)