package ca.cqdg.index

import bio.ferlab.datalake.spark2.elasticsearch.{ElasticSearchClient, Indexer}
import org.apache.spark.sql.SparkSession


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


object IndexerV2 extends App {

  val Array(input, format, alias, oldRelease, newRelease, templateFileName, jobType, esHost, username, password) = args

  implicit val spark: SparkSession =
    SparkSession.builder
      .master("local")
      .config("es.index.auto.create", "true")
      .config("es.nodes", esHost)
      .config("es.net.http.auth.user", username)
      .config("es.net.http.auth.pass", password)
      .config("es.nodes.wan.only", "true")
      .appName(s"CQDG ETL Indexer").getOrCreate()

  val s3Endpoint = getConfiguration("SERVICE_ENDPOINT", "http://localhost:9000")
  spark.sparkContext.hadoopConfiguration.set("fs.s3a.endpoint", s3Endpoint)
  spark.sparkContext.hadoopConfiguration.set("fs.s3a.path.style.access", "true")

  implicit val esClient: ElasticSearchClient = new ElasticSearchClient(esHost.split(',').head, Some(username), Some(password))

  val path: String => String = str => {
    this.getClass.getClassLoader.getResource(str).getFile
  }

  val inputDf = spark.read.format(format).load(input)
  new Indexer(jobType, path(templateFileName), alias, oldRelease, newRelease).run(inputDf)

}
