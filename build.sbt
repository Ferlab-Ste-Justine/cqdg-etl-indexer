name := "cqdg-etl-indexer"

version := "0.1"

scalaVersion := "2.11.12"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

val spark_version = "2.4.6"

/* Runtime */
libraryDependencies +=  "org.apache.spark" %% "spark-sql" % spark_version % Provided
libraryDependencies +=  "org.apache.hadoop" % "hadoop-common" % "2.10.1" % Provided
libraryDependencies +=  "org.apache.hadoop" % "hadoop-client" % "2.10.1" % Provided
libraryDependencies +=  "org.apache.hadoop" % "hadoop-aws" % "2.10.1" % Provided
libraryDependencies += "org.elasticsearch" %% "elasticsearch-spark-20" % "7.8.1" % Provided
libraryDependencies += "commons-httpclient" % "commons-httpclient" % "3.1"
libraryDependencies += "com.amazonaws" % "aws-java-sdk-bom" % "1.11.975"
libraryDependencies += "com.amazonaws" % "aws-java-sdk-s3" % "1.11.975"
/* Test */
libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % "test"

test in assembly := {}

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.last
  case "META-INF/native/libnetty_transport_native_epoll_x86_64.so" => MergeStrategy.last
  case "META-INF/DISCLAIMER" => MergeStrategy.last
  case "mozilla/public-suffix-list.txt" => MergeStrategy.last
  case "overview.html" => MergeStrategy.last
  case "git.properties" => MergeStrategy.discard
  case "mime.types" => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
assemblyJarName in assembly := "cqdg-etl-indexer.jar"
