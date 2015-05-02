package org.deepdive.datastore

import java.io.{File, Reader, Writer, FileReader, FileWriter, BufferedReader, BufferedWriter, PrintWriter, InputStream, InputStreamReader}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.util.{Try, Success, Failure}
import au.com.bytecode.opencsv.CSVWriter
import scala.language.postfixOps
import org.deepdive.Logging
import org.deepdive.Context
import java.sql.Connection
import play.api.libs.json._
import scalikejdbc._
import com.typesafe.config._
import org.deepdive.helpers.Helpers

trait ImpalaDataStoreComponent extends JdbcDataStoreComponent {
  def dataStore = new ImpalaDataStore
}

/* Helper object for working with Postgres */
class ImpalaDataStore extends JdbcDataStore with Logging {

  override def init() : Unit = {

  // copy UDF jar to hdfs
  val jarFile = "hive-contrib-0.13.1-cdh5.3.3.jar"
  if (Helpers.executeCmdWithExitCode(s"hdfs dfs -test -f /tmp/${jarFile}") != 0)
    Helpers.executeCmd(s"hdfs dfs -put ${Context.deepdiveHome}/udf/${jarFile} /tmp")

  val rowSequenceForImpala = s"""
    DROP FUNCTION IF EXISTS row_sequence();
    CREATE FUNCTION row_sequence() returns BIGINT location '/tmp/hive-contrib-0.13.1-cdh5.3.3.jar'
      symbol='org.apache.hadoop.hive.contrib.udf.UDFRowSequence';
    """
    executeSqlQueries(rowSequenceForImpala)    
  }

  // Executes a "COPY FROM STDIN" statement using raw data */
  def copyBatchData(sqlStatement: String, dataReader: Reader)
    (implicit connection: Connection) : Unit = {
      val del = new org.apache.commons.dbcp.DelegatingConnection(connection)
      // TODO zifei
      val pg_conn = del.getInnermostDelegate().asInstanceOf[org.postgresql.core.BaseConnection]
      val cm = new org.postgresql.copy.CopyManager(pg_conn)
      cm.copyIn(sqlStatement, dataReader)
      dataReader.close()
    }

  /**
   * input: iterator (of what?)  
   * 
   * - Create a temp CSV file
   * - run writeCopyData to write   
   */
  override def addBatch(result: Iterator[JsObject], outputRelation: String) : Unit = {
    val file = File.createTempFile(s"deepdive_$outputRelation", ".csv", new File(Context.outputDir))
    log.debug(s"Writing data of to file=${file.getCanonicalPath}")
    val writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true)))
    // Write the dataset to the file for the relation
    writeCopyData(result, writer)
    writer.close()
    val columnNames = scalikejdbc.DB.getColumnNames(outputRelation).toSet
    val copySQL = buildCopySql(outputRelation, columnNames)
    log.debug(s"Copying batch data to postgres. sql='${copySQL}'" +
      s"file='${file.getCanonicalPath}'")
    withConnection { implicit connection =>
      Try(copyBatchData(copySQL, new BufferedReader(new FileReader(file)))) match {
        case Success(_) => 
          log.debug("Successfully copied batch data to postgres.") 
          file.delete()
        case Failure(ex) => 
          log.error(s"Error during copy: ${ex}")
          log.error(s"Problematic CSV file can be found at file=${file.getCanonicalPath}")
          throw ex
      }
    } 
  }

  /* Builds a COPY statement for a given relation and column names */
  def buildCopySql(relationName: String, keys: Set[String]) = {
    // Zifei: do not fill ID any more
    // val fields = List("id") ++ keys.filterNot(_ == "id").toList.sorted
    val fields = keys.filterNot(_ == "id").toList.sorted
    s"""COPY ${relationName}(${fields.mkString(", ")}) FROM STDIN CSV"""
  }

  /* Builds a CSV dat astring for given JSON data and column names */
  def writeCopyData(data: Iterator[JsObject], fileWriter: Writer) : Unit = {
    val writer = new CSVWriter(fileWriter)
    for (obj <- data) { 
      val dataList = obj.value.filterKeys(_ != "id").toList.sortBy(_._1)
      val strList = dataList.map (x => jsValueToString(x._2))
      // // We get a unique id for the record
      // val id = variableIdCounter.getAndIncrement().toString
      // writer.writeNext((Seq(id) ++ strList)toArray)
      writer.writeNext((strList)toArray)
    }
  }

  /* Translates a JSON value to a String that can be insert using COPY statement */
  private def jsValueToString(x: JsValue) : String = x match {
    case JsString(x) => x.replace("\\", "\\\\")
    case JsNumber(x) => x.toString
    case JsNull => null
    case JsBoolean(x) => x.toString
    case JsArray(x) => 
      val innerData = x.map {
        case JsString(x) => 
          val convertedStr = jsValueToString(JsString(x))
          val escapedStr = convertedStr.replace("\"", "\\\"")
          s""" "${escapedStr}" """ 
        case x: JsValue => jsValueToString(x)
      }.mkString(",")
      val arrayStr = s"{${innerData}}"
      arrayStr
    case x : JsObject => Json.stringify(x)
    case _ =>
      log.warning(s"Could not convert JSON value ${x} to String")
      ""
  }


  /**
   * Drop and create a sequence, based on database type.
   */
  override def createSequenceFunction(seqName: String): String =
    s""""""
    //s"""DROP SEQUENCE IF EXISTS ${seqName} CASCADE;
    //    CREATE SEQUENCE ${seqName} MINVALUE -1 START 0;"""

  /**
   * Cast an expression to a type
   */
  override def cast(expr: Any, toType: String): String =
    s"""cast(${expr.toString()} as ${toType})"""

  /**
   * Given a string column name, Get a quoted version dependent on DB.
   *          if psql, return "column"
   *          if mysql, return `column`
   */
  override def quoteColumn(column: String): String =
    '"' + column + '"'
    
  /**
   * Generate random number in [0,1] in psql
   */
  override def randomFunction: String = "RAND()"

  /**
   * Concatinate strings using "||" in psql/GP, adding user-specified
   * delimiter in between
   */
  override def concat(list: Seq[String], delimiter: String): String = {
    delimiter match {
      case null => list.mkString(s" || ")
      case "" => list.mkString(s" || ")
      case _ => list.mkString(s" || '${delimiter}' || ")
    }
  }
  
  override def analyzeTable(table: String) = s"" //s"ANALYZE ${table}"

  // assign senquential ids to table's id column
  override def assignIds(table: String, startId: Long, sequence: String) : Long = {

    val columns = ArrayBuffer[String]()
    executeSqlQueryWithCallback(s"DESCRIBE ${table}") { rs =>
      val name = rs.getString(1)
      columns += name
    }

    val values = ArrayBuffer[String]()
    for (i <- 0 until columns.size)
      //values += { if (columns(i).equals("id")) s"(${startId} + (row_number() over(ORDER BY id)))" else columns(i) }
      values += { if (columns(i).equals("id")) s"${startId} - 2 + row_sequence()" else columns(i) }

    executeSqlQueries(s"INSERT OVERWRITE TABLE ${table} (" + columns.mkString(", ") +
      ") SELECT " + values.mkString(", ") + s" FROM ${table} ")

//    if (isUsingGreenplum()) {
//      executeSqlQueries(s"SELECT fast_seqassign('${table.toLowerCase()}', ${startId});");
//    } else {
//      executeSqlQueries(s"UPDATE ${table} SET id = nextval('${sequence}');")
//    }
    var count : Long = 0
    executeSqlQueryWithCallback(s"""SELECT COUNT(*) FROM ${table};""") { rs =>
      count = rs.getLong(1)
    }
    return count
  }
  

  /**
   * Drops a table if it exists, and then create it
   * Ensures we are only dropping tables inside the DeepDive namespace.
   */
  override def dropAndCreateTable(name: String, schema: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""DROP TABLE IF EXISTS ${name};""")
    executeSqlQueries(s"""CREATE TABLE ${name} (${schema});""")
  }

  /**
   * Drops a table if it exists, and then create it using the given query
   * Ensures we are only dropping tables inside the DeepDive namespace.
   */
  override def dropAndCreateTableAs(name: String, query: String) = {
    checkTableNamespace(name)
    executeSqlQueries(s"""DROP TABLE IF EXISTS ${name};""")
    executeSqlQueries(s"""CREATE TABLE ${name} AS ${query};""")
  }

}