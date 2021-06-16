package ca.cqdg.index

import ca.cqdg.index.ESIndicesManager.executeHttpRequest
import org.apache.http.{HttpRequest, HttpRequestInterceptor}
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.protocol.HttpContext
import org.apache.log4j.{BasicConfigurator, Level}
import org.slf4j.{Logger, LoggerFactory}
import org.spark_project.guava.io.BaseEncoding

import java.net.URL
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.io.Source
import scala.util.{Failure, Success, Try}

object CreateArraysApp extends App {

  /**
   * You need to re-create the indexes via Arranger before using this
   */

  BasicConfigurator.configure()
  org.apache.log4j.Logger.getRootLogger.setLevel(Level.INFO)

  val log: Logger = LoggerFactory.getLogger(CreateArraysApp.getClass)

  val (esHost: String, username: Option[String], password: Option[String]) = args match {
    case Array(esHost) => (esHost, None, None)
    case Array(esHost, username, password) => (esHost, Some(username), Some(password))
    case _ => ("http://localhost:9200/", None, None)
  }

  def extractArrayFieldFromConfiguration(configFile: String): Set[String] = {
    val arraysConfig = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(configFile))
    val arrays = arraysConfig.getLines().toSet
    arraysConfig.close()
    arrays
  }

  // extract every field line from the ES project, ex: "field" : "access_requirements",
  def extractArrayFieldsFromProject(http: HttpClient, projectUrl: URL): Set[String] = {
    val requestUrl = new URL(projectUrl, "arranger-projects-cqdg/_search").toString
    val httpRequest = new HttpGet(requestUrl)
    val response = executeHttpRequest(http, httpRequest, true, true)

    // good old ugly string delimiter finder
    def extractNextFieldAndValue(response: Option[String], lastIndex: Int): Option[(String, Int)] = {
      val fieldStr = "\"field\"";
      val delimiter = "\""
      response.flatMap(project => {
        val nextFieldIndex = project.indexOf(fieldStr, lastIndex)
        if(nextFieldIndex == -1) None
        else {
          val nextFieldValueStartIndex = project.indexOf(delimiter, nextFieldIndex + fieldStr.length)
          val nextFieldValueEndIndex = project.indexOf(delimiter, nextFieldValueStartIndex + delimiter.length)
          val fieldValue = project.substring(nextFieldValueStartIndex + delimiter.length, nextFieldValueEndIndex)
          Some((fieldValue, nextFieldValueEndIndex))
        }
      })
    }

    @tailrec
    def findALlFields(response: Option[String], lastIndex: Int, found: List[String]): List[String] = {
      val nextField: Option[(String, Int)] = extractNextFieldAndValue(response, lastIndex)
      if(nextField.isEmpty){
        found
      } else {
        findALlFields(response, nextField.get._2, nextField.get._1 :: found)
      }
    }

    Set(findALlFields(response, 0, List()):_*)
  }

  def createArraysRequest(http: HttpClient, projectUrl: URL, compatibleFields: List[String], indexName: String): Unit = {
    val escapeCompatibleFields = compatibleFields.map(str => "\""+str+"\"").mkString(",")
    val arraysQuerySource = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream("arrays/arrays_query.json"))
    val arraysQuery = try arraysQuerySource.getLines().mkString finally arraysQuerySource.close()

    val requestUrl = new URL(projectUrl, s"arranger-projects-cqdg/_update/$indexName").toString
    val request = new HttpPost(requestUrl)
    request.setEntity(new StringEntity(arraysQuery.replace("{{compatibleFields}}", escapeCompatibleFields)))
    request.setHeader("Content-Type", "application/json")

    val response = executeHttpRequest(http, request, true, true).get
    log.info(response)
  }

  val httpBuilder = HttpClientBuilder.create()

  if(username.isDefined && password.isDefined) {
    httpBuilder.addInterceptorFirst(new HttpRequestInterceptor {
      override def process(request: HttpRequest, context: HttpContext): Unit = {
        val auth = s"${username.get}:${password.get}"
        request.addHeader(
          "Authorization",
          s"Basic ${BaseEncoding.base64().encode(auth.getBytes(StandardCharsets.UTF_8))}"
        )
      }
    })
  }

  val http = httpBuilder.build()
  val projectUrl = new URL(esHost)

  val config = extractArrayFieldFromConfiguration("arrays/arrays.txt")
  val projectFields = extractArrayFieldsFromProject(http, projectUrl)

  val compatibleFields = projectFields.intersect(config).toList

  if(compatibleFields.isEmpty){
    log.error(s"Not compatible fields found between ES project (${projectFields.size}) and configuration (${config.size})")
  } else {
    createArraysRequest(http, projectUrl, compatibleFields, "studies")
    createArraysRequest(http, projectUrl, compatibleFields, "donors")
    createArraysRequest(http, projectUrl, compatibleFields, "files")
  }

  http.close()
}
