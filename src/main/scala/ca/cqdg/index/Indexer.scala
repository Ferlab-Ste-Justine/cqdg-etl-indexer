package ca.cqdg.index

import com.amazonaws.ClientConfiguration
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.protocol.HttpContext
import org.apache.http.{HttpRequest, HttpRequestInterceptor}
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}
import org.spark_project.guava.io.BaseEncoding

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import scala.util.Properties


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
  //basicAuthHeader = base64(username:password)

  val (esHost: String, username:Option[String], password:Option[String]) = args match {
    case Array(esHost) => (esHost, None, None)
    case Array(esHost, username, password) => (esHost, Some(username), Some(password))
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
                                          .appName(s"CQDG ETL Indexer").getOrCreate()
                                     else
                                        SparkSession.builder
                                          .master("local")
                                          .config("es.index.auto.create", "true")
                                          .config("es.nodes", esHost)
                                          .config("es.nodes.wan.only", true)
                                          .appName(s"CQDG ETL Indexer").getOrCreate()

  val s3Endpoint = getConfiguration("SERVICE_ENDPOINT", "http://localhost:9000")
  val s3Bucket: String = getConfiguration("AWS_BUCKET", "cqdg")
  spark.sparkContext.hadoopConfiguration.set("fs.s3a.endpoint", s3Endpoint)
  spark.sparkContext.hadoopConfiguration.set("fs.s3a.path.style.access", "true")

  val clientConfiguration = new ClientConfiguration
  clientConfiguration.setSignerOverride("AWSS3V4SignerType")

  val s3Client: AmazonS3 = AmazonS3ClientBuilder.standard()
    .withEndpointConfiguration(
      new EndpointConfiguration(
        getConfiguration("SERVICE_ENDPOINT", s3Endpoint),
        getConfiguration("AWS_DEFAULT_REGION", Regions.US_EAST_1.name()))
    )
    .withPathStyleAccessEnabled(true)
    .withClientConfiguration(clientConfiguration)
    .build()

  def indexBasedOnDirectoryStructure(directoryToIndex:String, indexConfig:String, indexName:String): Unit ={
    val httpBuilder = HttpClientBuilder.create()//.useSystemProperties()

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
    val aliasUrl:String = if(esHost.endsWith("/")) esHost.concat("_aliases") else s"$esHost/_aliases"

    //Initialize indices based on the folder name
    //1 index per study
    val listing: ObjectListing = s3Client.listObjects(s3Bucket, directoryToIndex)
    val indices = listing.getObjectSummaries.asScala
      .filter(_.getKey.startsWith(s"${directoryToIndex}/study_id="))
      .map(dir => {
        ESIndicesManager.swapIndex(http, esHost, s3Bucket, dir.getKey, s"_$indexName", indexConfig, aliasUrl)
      })

    //Add a global alias to search all indexes/studies at once

    ESIndicesManager.setAlias(http, Some(indices), None, indexName, aliasUrl)
    http.close()
  }

  //NB.: The output folders MUST BE "cases" and "files"
  indexBasedOnDirectoryStructure(
    s"clinical-data-etl-indexer/donors",
    "donors_index.json",
    "donors")

  indexBasedOnDirectoryStructure(
    s"clinical-data-etl-indexer/files",
    "files_index.json",
    "files")

  indexBasedOnDirectoryStructure(
    s"clinical-data-etl-indexer/studies",
    "studies_index.json",
    "studies")

  spark.stop()
}
