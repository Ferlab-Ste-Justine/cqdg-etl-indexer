package ca.cqdg.index

import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods._
import org.apache.http.entity.StringEntity
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession
import org.elasticsearch.spark.sql.sparkDatasetFunctions
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.write

import scala.io.Source

object ESIndicesManager {

  Logger.getLogger("ferlab").setLevel(Level.ERROR)
  val logger = Logger.getLogger(ESIndicesManager.getClass)

  def swapIndex(http: HttpClient, host:String, s3Bucket: String, dir:String, indexSuffix:String, indexConfig:String, aliasUrl:String)(implicit spark: SparkSession): String ={
    // dir = clinical-data-etl-indexer/donors/study_id=ST0001/dictionary_version=5.13/study_version=1/study_version_creation_date=2021-03-19/part-00000-0c395405-aadc-43a6-8550-eb0f54641300.c000.json
    val prefixParts = dir.split("/")
    val prefixStudy = prefixParts.take(3).mkString("/")
    val aliasName = prefixParts(2).split("=")(1).concat(indexSuffix).toLowerCase()

    val index1 = s"$aliasName-1"
    val index2 = s"$aliasName-2"

    val indexUrl1:String = if(host.endsWith("/")) s"$host$index1" else s"$host/$index1"
    val indexUrl2:String = if(host.endsWith("/")) s"$host$index2" else s"$host/$index2"

    val index1ExistsResponse:HttpResponse = http.execute(new HttpHead(indexUrl1));
    val index1ExistsResponseCode: Int = index1ExistsResponse.getStatusLine.getStatusCode

    EntityUtils.consumeQuietly(index1ExistsResponse.getEntity)

    if(200 != index1ExistsResponseCode){
      val index2ExistsResponse:HttpResponse = http.execute(new HttpHead(indexUrl2));
      val index2ExistsResponseCode: Int = index2ExistsResponse.getStatusLine.getStatusCode

      createIndex(http, indexConfig, indexUrl1)
      spark.read.json(s"s3a://${s3Bucket}/${prefixStudy}").saveToEs(index1)

      if(200 != index2ExistsResponseCode)
        setAlias(http, Some(List(index1)), None, aliasName, aliasUrl)
      else
        setAlias(http, Some(List(index1)), Some(List(index2)), aliasName, aliasUrl)

      deleteIndex(http, indexUrl2)

      index1
    }else{
      createIndex(http, indexConfig, indexUrl2)
      spark.read.json(s"s3a://${s3Bucket}/${prefixStudy}").saveToEs(index2)
      setAlias(http, Some(List(index2)), Some(List(index1)), aliasName, aliasUrl)
      deleteIndex(http, indexUrl1)

      index2
    }
  }

  def createIndex(http:HttpClient, indexConfig:String, indexUrl:String): Unit ={
    val configSource:Source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(indexConfig), "UTF-8")
    val config:String = try configSource.getLines().mkString finally configSource.close()

    val indexCreationRequest = new HttpPut(indexUrl)

    val body: StringEntity = new StringEntity(config)
    body.setContentType(new BasicHeader(HTTP.CONTENT_TYPE,"application/json"));

    indexCreationRequest.setEntity(body)
    executeHttpRequest(http, indexCreationRequest, true)
  }

  def setAlias(http:HttpClient, indicesNameAdd:Option[Seq[String]], indicesNameRemove:Option[Seq[String]], aliasName:String, aliasUrl:String): Unit ={
    val aliasesAddAction = indicesNameAdd.getOrElse(List.empty)
      .map(x => {
        AddAction(Map("index" -> x, "alias" -> aliasName))
      })

    val aliasesRemoveAction = indicesNameRemove.getOrElse(List.empty)
      .map(x => {
        RemoveAction(Map("index" -> x, "alias" -> aliasName))
      })

    if(aliasesAddAction.size > 0 || aliasesRemoveAction.size > 0){
      val aliasRequest = ActionsRequest(aliasesAddAction ++ aliasesRemoveAction)

      implicit val formats = DefaultFormats

      val aliasCreationPostRequest = new HttpPost(aliasUrl)

      if(logger.isDebugEnabled)
        logger.debug("ElasticSearch Alias Query:\n" + write(aliasRequest))

      val body: StringEntity = new StringEntity(write(aliasRequest))
      body.setContentType(new BasicHeader(HTTP.CONTENT_TYPE,"application/json"));

      aliasCreationPostRequest.setEntity(body)
      executeHttpRequest(http, aliasCreationPostRequest, false)
    }
  }

  def deleteIndex(http:HttpClient, indexUrl:String): Unit ={
    val indexDeletionRequest = new HttpDelete(indexUrl)
    executeHttpRequest(http, indexDeletionRequest, false)
  }

  def executeHttpRequest(http:HttpClient, request: HttpRequestBase, throwException: Boolean):Unit = {
    val res:HttpResponse = http.execute(request)
    val statusCode:Int = res.getStatusLine.getStatusCode

    try{
      if(200 != statusCode && 404 != statusCode){
        logger.error(EntityUtils.toString(res.getEntity, "UTF-8"))
        if(throwException){
          throw new RuntimeException(EntityUtils.toString(res.getEntity, "UTF-8"))
        }
      }
    }finally {
      EntityUtils.consumeQuietly(res.getEntity)
    }
  }

  trait Action{}
  case class AddAction(add: Map[String, String]) extends Action
  case class RemoveAction(remove: Map[String, String]) extends Action
  case class ActionsRequest(actions: Seq[Action])
}
