package ch.mibex.bamboo.shipit

import java.io.File

import com.atlassian.bamboo.utils.FileVisitor
import com.atlassian.plugin.Application
import com.atlassian.plugin.parsers.XmlDescriptorParser
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object Utils {

  // we circumvent the type-safety of Spray here but it is otherwise to cumbersome to just get a
  // few values out of a deeply nested JSON tree
  implicit object AnyJsonFormat extends JsonFormat[Any] {

    def write(x: Any) = x match {
      case n: Int => JsNumber(n)
      case s: String => JsString(s)
      case q: Seq[_] => seqFormat[Any].write(q)
      case m: Map[_, _] => mapFormat[String, Any].write(m.asInstanceOf[Map[String, Any]])
      case b: Boolean if b => JsTrue
      case b: Boolean if !b => JsFalse
      case u => serializationError("Do not understand object of type " + u.getClass.getName)
    }

    def read(value: JsValue) = value match {
      case JsNumber(n) => n.intValue()
      case JsString(s) => s
      case a: JsArray => listFormat[Any].read(value)
      case o: JsObject => mapFormat[String, Any].read(value)
      case JsTrue => true
      case JsFalse => false
      case JsNull => null
      case x => deserializationError("Do not understand how to deserialize " + x)
    }

  }

  def map2Json(map: Map[String, Any]): String = map.toJson.compactPrint

  def mapFromJson(json: String): Map[String, Any] = json.parseJson.convertTo[Map[String, Any]]

  def findPluginKeyInDescriptor(): String = {
    val descriptor = Option(getClass.getClassLoader.getResource("atlassian-plugin.xml")).getOrElse(
      throw new IllegalStateException("Can't find atlassian-plugin.xml")
    )
    val is = descriptor.openStream()
    try {
      val parser = new XmlDescriptorParser(is, Set[Application]().asJava)
      parser.getKey
    } finally {
      is.close()
    }
  }

  def findMostRecentMatchingFile(filePattern: String, localPath: File) = {
    val rawArtifacts = ListBuffer[File]()
    val namesVisitor = new FileVisitor(localPath) {
      override def visitFile(file: File): Unit = {
        rawArtifacts += file
      }
    }
    namesVisitor.visitFilesThatMatch(filePattern)
    val lastModified = Ordering.by((_: File).lastModified)
    // if we don't take the most recent one, we might upload old plug-in versions
    rawArtifacts.reduceOption(lastModified.max)
  }

  def toBuildNumber(versionString: String) = {
    val version = new DefaultArtifactVersion(versionString)
    val buildNr = Array(version.getMajorVersion, version.getMinorVersion, version.getIncrementalVersion)
      .map(v => "%-3s".format(v).replace(' ', '0'))
      .mkString("")
      .toInt
    buildNr
  }

}
