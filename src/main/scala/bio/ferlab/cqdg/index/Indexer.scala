package bio.ferlab.cqdg.index

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.spark.sql.SparkSession

object Indexer extends App {
  val Array(input) = args
  //input  = /home/plaplante/CHUST/projects/cqdg/cqdg-etl/src/test/resources/csv/output

  implicit val spark: SparkSession = SparkSession.builder
    .master("local")
    .config("es.index.auto.create", "true")
    .appName(s"Indexer").getOrCreate()

  def indexBasedOnDirectoryStructure(directoryToIndex:String, esHost:String, indexConfig:String, indexName:String): Unit ={
    val http = new DefaultHttpClient()
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
    s"$input/cases",
    "http://localhost:9200",
    "donor_index.json",
    "cases")

  indexBasedOnDirectoryStructure(
    s"$input/files",
    "http://localhost:9200",
    "file_index.json",
    "files")

  spark.stop()


}
