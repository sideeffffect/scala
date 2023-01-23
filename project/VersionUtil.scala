package scala.build

import sbt._
import Keys._

import java.util.{Date, Locale, Properties, TimeZone}
import java.io.{File, FileInputStream, StringWriter}
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, TemporalQueries, TemporalQuery}
import scala.collection.JavaConverters._
import BuildSettings.autoImport._

object VersionUtil {
  lazy val copyrightString = settingKey[String]("Copyright string.")
  lazy val shellBannerString = settingKey[String]("Shell welcome banner string.")
  lazy val versionProperties = settingKey[Versions]("Version properties.")
  lazy val gitProperties = settingKey[GitProperties]("Current git information")
  lazy val buildCharacterPropertiesFile = settingKey[File]("The file which gets generated by generateBuildCharacterPropertiesFile")
  lazy val generateVersionPropertiesFile = taskKey[File]("Generate version properties file.")
  lazy val generateBuildCharacterPropertiesFile = taskKey[File]("Generate buildcharacter.properties file.")
  lazy val extractBuildCharacterPropertiesFile = taskKey[File]("Extract buildcharacter.properties file from bootstrap scala-compiler.")

  lazy val globalVersionSettings = Seq[Setting[_]](
    // Set the version properties globally (they are the same for all projects)
    Global / versionProperties := versionPropertiesImpl.value,
    gitProperties := gitPropertiesImpl.value,
    Global / version := versionProperties.value.mavenVersion
  )

  lazy val generatePropertiesFileSettings = Seq[Setting[_]](
    copyrightString := "Copyright 2002-2023, LAMP/EPFL and Lightbend, Inc.",
    shellBannerString := """
      |     ________ ___   / /  ___
      |    / __/ __// _ | / /  / _ |
      |  __\ \/ /__/ __ |/ /__/ __ |
      | /____/\___/_/ |_/____/_/ | |
      |                          |/  %s""".stripMargin.linesIterator.mkString("%n"),
    Compile / resourceGenerators += generateVersionPropertiesFile.map(file => Seq(file)).taskValue,
    generateVersionPropertiesFile := generateVersionPropertiesFileImpl.value
  )

  lazy val generateBuildCharacterFileSettings = Seq[Setting[_]](
    buildCharacterPropertiesFile := ((ThisBuild / baseDirectory).value / "buildcharacter.properties"),
    generateBuildCharacterPropertiesFile := generateBuildCharacterPropertiesFileImpl.value
  )

  case class Versions(canonicalVersion: String, mavenBase: String, mavenSuffix: String, osgiVersion: String, commitSha: String, commitDate: String, isRelease: Boolean) {
    val githubTree =
      if(isRelease) "v" + mavenVersion
      else if(commitSha != "unknown") commitSha
      else "master"

    def mavenVersion: String = mavenBase + mavenSuffix
    override def toString = s"Canonical: $canonicalVersion, Maven: $mavenVersion, OSGi: $osgiVersion, github: $githubTree"

    def toMap: Map[String, String] = Map(
      "version.number" -> canonicalVersion,
      "maven.version.number" -> mavenVersion,
      "osgi.version.number" -> osgiVersion
    )
  }

  case class GitProperties(date: String, sha: String)

  private lazy val gitPropertiesImpl: Def.Initialize[GitProperties] = Def.setting {
    val log = sLog.value
    val (dateObj, sha) = {
      try {
        // Use JGit to get the commit date and SHA
        import org.eclipse.jgit.storage.file.FileRepositoryBuilder
        import org.eclipse.jgit.revwalk.RevWalk
        val db = new FileRepositoryBuilder().findGitDir.build
        val head = db.resolve("HEAD")
        if (head eq null) {
          import scala.sys.process._
          try {
            // Workaround lack of git worktree support in JGit https://bugs.eclipse.org/bugs/show_bug.cgi?id=477475
            val sha = List("git", "rev-parse", "HEAD").!!.trim
            val commitDateIso = List("git", "log", "-1", "--format=%cI", "HEAD").!!.trim
            val date = java.util.Date.from(DateTimeFormatter.ISO_DATE_TIME.parse(commitDateIso, new TemporalQuery[Instant] {
              override def queryFrom(temporal: TemporalAccessor): Instant = Instant.from(temporal)
            }))
            (date, sha.substring(0, 7))
          } catch {
            case ex: Exception =>
              ex.printStackTrace()
              log.info("No git HEAD commit found -- Using current date and 'unknown' SHA")
              (new Date, "unknown")
          }
        } else {
          val commit = new RevWalk(db).parseCommit(head)
          (new Date(commit.getCommitTime.toLong * 1000L), commit.getName.substring(0, 7))
        }
      } catch {
        case ex: Exception =>
          log.error("Could not determine commit date + SHA: " + ex)
          log.trace(ex)
          (new Date, "unknown")
      }
    }
    val date = {
      val df = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH)
      df.setTimeZone(TimeZone.getTimeZone("UTC"))
      df.format(dateObj)
    }
    GitProperties(date, sha)
  }

  /** Compute the canonical, Maven and OSGi version number from `baseVersion` and `baseVersionSuffix`.
    * Examples of the generated versions:
    *
    * ("2.11.8", "SNAPSHOT"    ) -> ("2.11.8-20151215-133023-7559aed", "2.11.8-bin-SNAPSHOT",         "2.11.8.v20151215-133023-7559aed")
    * ("2.11.8", "SHA-SNAPSHOT") -> ("2.11.8-20151215-133023-7559aed", "2.11.8-bin-7559aed-SNAPSHOT", "2.11.8.v20151215-133023-7559aed")
    * ("2.11.8", "SHA"         ) -> ("2.11.8-7559aed",                 "2.11.8-bin-7559aed",          "2.11.8.v20151215-133023-7559aed")
    * ("2.11.0", "SHA"         ) -> ("2.11.0-7559aed",                 "2.11.0-pre-7559aed",          "2.11.0.v20151215-133023-7559aed")
    * ("2.11.8", ""            ) -> ("2.11.8",                         "2.11.8",                      "2.11.8.v20151215-133023-VFINAL-7559aed")
    * ("2.11.8", "M3"          ) -> ("2.11.8-M3",                      "2.11.8-M3",                   "2.11.8.v20151215-133023-M3-7559aed")
    * ("2.11.8", "RC4"         ) -> ("2.11.8-RC4",                     "2.11.8-RC4",                  "2.11.8.v20151215-133023-RC4-7559aed")
    * ("2.11.8-RC4", "SPLIT"   ) -> ("2.11.8-RC4",                     "2.11.8-RC4",                  "2.11.8.v20151215-133023-RC4-7559aed")
    *
    * A `baseVersionSuffix` of "SNAPSHOT" is the default, which is used for local snapshot builds. The PR validation
    * job uses "SHA-SNAPSHOT". A proper version number for an integration build can be computed with "SHA". An empty
    * suffix is used for releases. All other suffix values are treated as RC / milestone builds. The special suffix
    * value "SPLIT" is used to split the real suffix off from `baseVersion` instead and then apply the usual logic. */
  private lazy val versionPropertiesImpl: Def.Initialize[Versions] = Def.setting {
    val (date, sha) = (gitProperties.value.date, gitProperties.value.sha)

    val (base, suffix) = {
      val (b, s) = (baseVersion.value, baseVersionSuffix.value)
      if(s == "SPLIT") {
        val split = """([\w+\.]+)(-[\w+\.-]+)??""".r
        val split(b2, sOrNull) = b
        (b2, Option(sOrNull).map(_.drop(1)).getOrElse(""))
      } else (b, s)
    }



    val Patch = """\d+\.\d+\.(\d+)""".r
    def cross = base match {
      case Patch(p) if p.toInt > 0 => "bin"
      case _ => "pre"
    }

    val (canonicalV, mavenSuffix, osgiV, release) = suffix match {
      case "SNAPSHOT"     => (s"$base-$date-$sha",   s"-$cross-SNAPSHOT",      s"$base.v$date-$sha",         false)
      case "SHA-SNAPSHOT" => (s"$base-$date-$sha",   s"-$cross-$sha-SNAPSHOT", s"$base.v$date-$sha",         false)
      case "SHA"          => (s"$base-$sha",         s"-$cross-$sha",          s"$base.v$date-$sha",         false)
      case ""             => (s"$base",              "",                       s"$base.v$date-VFINAL-$sha",  true)
      case _              => (s"$base-$suffix",      s"-$suffix",              s"$base.v$date-$suffix-$sha", true)
    }


    Versions(canonicalV, base, mavenSuffix, osgiV, sha, date, release)
  }

  private lazy val generateVersionPropertiesFileImpl: Def.Initialize[Task[File]] = Def.task {
    writeProps(versionProperties.value.toMap ++ Seq(
        "copyright.string" -> copyrightString.value,
        "shell.banner"     -> shellBannerString.value
      ),
      (Compile / resourceManaged).value / s"${thisProject.value.id}.properties")
  }

  private lazy val generateBuildCharacterPropertiesFileImpl: Def.Initialize[Task[File]] = Def.task {
    val v = versionProperties.value
    writeProps(v.toMap ++ versionProps ++ Map(
      "maven.version.base" -> v.mavenBase,
      "maven.version.suffix" -> v.mavenSuffix
    ), buildCharacterPropertiesFile.value)
  }

  private def writeProps(m: Map[String, String], propFile: File): File = {
    // Like:
    // IO.write(props, null, propFile)
    // But with deterministic key ordering and no timestamp
    val fullWriter = new StringWriter()
    for (k <- m.keySet.toVector.sorted) {
      val writer = new StringWriter()
      val props = new Properties()
      props.put(k, m(k))
      props.store(writer, null)
      writer.toString.linesIterator.drop(1).foreach{line => fullWriter.write(line); fullWriter.write("\n")}
    }
    IO.write(propFile, fullWriter.toString)
    propFile
  }

  private[build] def readProps(f: File): Map[String, String] = {
    val props = new Properties()
    val in = new FileInputStream(f)
    try props.load(in)
    finally in.close()
    props.asScala.toMap
  }

  /** The global versions.properties data */
  lazy val versionProps: Map[String, String] = {
    val versionProps = readProps(file("versions.properties"))
    versionProps.map { case (k, v) => (k, sys.props.getOrElse(k, v)) } // allow sys props to override versions.properties
  }
}
