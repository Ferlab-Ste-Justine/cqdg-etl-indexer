package ca.cqdg.index

import java.nio.charset.StandardCharsets

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.protocol.HttpContext
import org.apache.http.{HttpRequest, HttpRequestInterceptor}
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}
import org.spark_project.guava.io.BaseEncoding


/**
 *
 * N.B.: esHost must include the port eg.: http(s)://search.qa.cqdg.ferlab.bio:443, http://localhost:9200
 *
 *
 * Export certificate https://search.qa.cqdg.ferlab.bio/ from Chrome
 * Save to disk (/home/plaplante/CHUST/projects/cqdg/qa.cqdg.ferlab.bio)
 *
 * keytool -import -alias search-cqdg-qa -keystore /home/plaplante/.sdkman/candidates/java/8.0.265.hs-adpt/jre/lib/security/cacerts -file /home/plaplante/CHUST/projects/cqdg/qa.cqdg.ferlab.bio
 * keytool -import -alias search-cqdg-qa -keystore /home/plaplante/.sdkman/candidates/java/11.0.7.hs-adpt/lib/security/cacerts -file /home/plaplante/CHUST/projects/cqdg/qa.cqdg.ferlab.bio
 *
 * PASSWORD: changeit
 *
 * -Djavax.net.ssl.trustStore=/home/plaplante/.sdkman/candidates/java/8.0.265.hs-adpt/jre/lib/security/cacerts
 * -Djavax.net.ssl.trustStorePassword=changeit
 */


object Indexer extends App {

  val LOGGER: Logger = LoggerFactory.getLogger(Indexer.getClass)

  //input  = /home/plaplante/CHUST/projects/cqdg/cqdg-etl/src/test/resources/csv/output
  //basicAuthHeader = base64(username:password)

  val (input:String, esHost: String, username:Option[String], password:Option[String]) = args match {
    case Array(input, esHost) => (input, esHost, None, None)
    case Array(input, esHost, username, password) => (input, esHost, Some(username), Some(password))
    case _ => {
      LOGGER.error("Usage: GenomicDataImporter task_name [batch_id]")
      System.exit(-1)
    }
  }

  implicit val spark: SparkSession = if(username.isDefined && password.isDefined)
                                        SparkSession.builder
                                          .master("local")
                                          .config("es.index.auto.create", "true")
                                          .config("es.nodes", esHost)
                                          .config("es.net.http.auth.user", username.get)
                                          .config("es.net.http.auth.pass", password.get)
                                          .config("es.nodes.wan.only", true)
                                          .appName(s"Indexer").getOrCreate()
                                     else
                                        SparkSession.builder
                                          .master("local")
                                          .config("es.index.auto.create", "true")
                                          .config("es.nodes", esHost)
                                          .config("es.nodes.wan.only", true)
                                          .appName(s"Indexer").getOrCreate()

  def indexBasedOnDirectoryStructure(directoryToIndex:String, indexConfig:String, indexName:String): Unit ={
    val http = new DefaultHttpClient()
    if(username.isDefined && password.isDefined) {
      http.addRequestInterceptor(new HttpRequestInterceptor {
        override def process(request: HttpRequest, context: HttpContext): Unit = {
          val auth = s"${username.get}:${password.get}"
          request.addHeader(
            "Authorization",
            s"Basic ${BaseEncoding.base64().encode(auth.getBytes(StandardCharsets.UTF_8))}"
          )
        }
      })
    }

    val fs = FileSystem.get(new Configuration())
    val aliasUrl:String = if(esHost.endsWith("/")) esHost.concat("_aliases") else s"$esHost/_aliases"

    //Initialize donor indices based on the folder name
    val status = fs.listStatus(new Path(directoryToIndex))
    val indices = status
      .filter(_.getPath.getName.startsWith("study_id"))
      .map(dir => {
        ESIndicesManager.swapIndex(http, esHost, dir, s"_$indexName", indexConfig, aliasUrl)
      })

    //Add a global alias to search all indexes/studies at once

    ESIndicesManager.setAlias(http, Some(indices), None, indexName, aliasUrl)

    http.getConnectionManager.shutdown()
  }

  //NB.: The output folders MUST BE "cases" and "files"
  indexBasedOnDirectoryStructure(
    s"$input/donors",
    "donor_index.json",
    "donors")

  indexBasedOnDirectoryStructure(
    s"$input/files",
    "file_index.json",
    "files")

  indexBasedOnDirectoryStructure(
    s"$input/studies",
    "study_index.json",
    "studies")

  spark.stop()
}
