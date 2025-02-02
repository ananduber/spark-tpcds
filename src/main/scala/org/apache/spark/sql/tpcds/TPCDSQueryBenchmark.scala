/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.tpcds

import org.apache.spark.sql.SaveMode
//import org.apache.spark.benchmark.Benchmark
import java.util.Locale

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation
import org.apache.spark.sql.catalyst.plans.logical.SubqueryAlias
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.functions._
import org.apache.spark.util.Benchmark

import org.slf4j.LoggerFactory

class TPCDSQueryBenchmarkArguments(val args: Array[String]) {
  var tpcdsDataLocation: String = null
  var outDataLocation: String = ""
  var queryFilter: Set[String] = Set.empty

  parseArgs(args.toList)
  validateArguments()

  private def optionMatch(optionName: String, s: String): Boolean = {
    optionName == s.toLowerCase(Locale.ROOT)
  }

  private def parseArgs(inputArgs: List[String]): Unit = {
    var args = inputArgs

    while (args.nonEmpty) {
      args match {
        case optName :: value :: tail if optionMatch("--tpcds-data-location", optName) =>
          tpcdsDataLocation = value
          args = tail

        case optName :: value :: tail if optionMatch("--out-data-location", optName) =>
          outDataLocation = value
          args = tail

        case optName :: value :: tail if optionMatch("--query-filter", optName) =>
          queryFilter = value.toLowerCase(Locale.ROOT).split(",").map(_.trim).toSet
          args = tail

        case _ =>
          // scalastyle:off println
          System.err.println("Unknown/unsupported param " + args)
          // scalastyle:on println
          printUsageAndExit(1)
      }
    }
  }

  private def printUsageAndExit(exitCode: Int): Unit = {
    // scalastyle:off
    System.err.println("""
                         |Usage: spark-submit --class <this class> <spark sql test jar> [Options]
                         |Options:
                         |  --tpcds-data-location      Path to TPCDS data
                         |  --query-filter             Queries to filter, e.g., q3,q5,q13
                         |  --out-data-location        Path to store query results
                         |
                         |------------------------------------------------------------------------------------------------------------------
                         |In order to run this benchmark, please follow the instructions at
                         |https://github.com/databricks/spark-sql-perf/blob/master/README.md
                         |to generate the TPCDS data locally (preferably with a scale factor of 5 for benchmarking).
                         |Thereafter, the value of <TPCDS data location> needs to be set to the location where the generated data is stored.
                       """.stripMargin)
    // scalastyle:on
    System.exit(exitCode)
  }

  private def validateArguments(): Unit = {
    if (tpcdsDataLocation == null || outDataLocation == null) {
      // scalastyle:off println
      System.err.println("Must specify data location")
      // scalastyle:on println
      printUsageAndExit(-1)
    }
  }
}

/**
  * Benchmark to measure TPCDS query performance.
  * To run this:
  *  spark-submit --class <this class> <spark sql test jar> --tpcds-data-location <TPCDS data location>
  */
object TPCDSQueryBenchmark extends Logging {

  val spark = SparkSession.builder.getOrCreate()
  spark.sparkContext.setLogLevel("Error")

  val tables = Seq("catalog_page", "catalog_returns", "customer", "customer_address",
    "customer_demographics", "date_dim", "household_demographics", "inventory", "item",
    "promotion", "store", "store_returns", "catalog_sales", "web_sales", "store_sales",
    "web_returns", "web_site", "reason", "call_center", "warehouse", "ship_mode", "income_band",
    "time_dim", "web_page")

  def setupTables(dataLocation: String): Map[String, Long] = {
    tables.map { tableName =>
      spark.read.parquet(s"$dataLocation/$tableName").createOrReplaceTempView(tableName)
      tableName -> spark.table(tableName).count()
    }.toMap
  }

  def runTpcdsQueries(queryLocation: String,
                      queries: Seq[String],
                      tableSizes: Map[String, Long],
                      outDataLocation: String = "",
                      nameSuffix: String = ""): Unit = {
    queries.foreach { name =>
      val queryString = resourceToString(s"$queryLocation/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)

      // This is an indirect hack to estimate the size of each query's input by traversing the
      // logical plan and adding up the sizes of all tables that appear in the plan.
      val queryRelations = scala.collection.mutable.HashSet[String]()
      spark.sql(queryString).queryExecution.analyzed.foreach {
        case SubqueryAlias(alias, _: LogicalRelation) =>
          queryRelations.add(alias.identifier)
        case LogicalRelation(_, _, Some(catalogTable), _) =>
          queryRelations.add(catalogTable.identifier.table)
        case HiveTableRelation(tableMeta, _, _) =>
          queryRelations.add(tableMeta.identifier.table)
        case _ =>
      }

      val numRows = queryRelations.map(tableSizes.getOrElse(_, 0L)).sum
      val minNumIters = 1
      val benchmark = new Benchmark(s"TPCDS Snappy", numRows, minNumIters = minNumIters)

      val queryDF = spark.sql(queryString)
      val regex = "[^a-zA-Z0-9]".r
      val finalDF = queryDF.select(queryDF.columns.map(name => col(name).as(regex.replaceAllIn(name, "_"))) : _*)
      finalDF.printSchema()

      benchmark.addCase(s"$name$nameSuffix") { _ =>
        finalDF.cache().take(100)
      }
      println(s"\n\n===== TPCDS QUERY BENCHMARK OUTPUT FOR $name =====\n")
      logInfo(s"\n\n===== TPCDS QUERY BENCHMARK OUTPUT FOR $name =====\n")
      benchmark.run()
      println(s"\n\n===== FINISHED $name =====\n")
      logInfo(s"\n\n===== FINISHED $name =====\n")
      println(s"\n\n===== TPCDS QUERY WRITING OUTPUT  : $outDataLocation / $name$nameSuffix =====\n")
      logInfo(s"\n\n===== TPCDS QUERY WRITING OUTPUT  : $outDataLocation / $name$nameSuffix =====\n")
      finalDF.write.mode(SaveMode.Ignore).parquet(outDataLocation + s"/$name$nameSuffix")
      finalDF.unpersist()
    }
  }

  def filterQueries(origQueries: Seq[String],
                    queryFilter: Set[String]): Seq[String] = {
    if (queryFilter.nonEmpty) {
      origQueries.filter(queryFilter.contains)
    } else {
      origQueries
    }
  }

  def run(queryFilter: Set[String], tpcdsDataLocation: String, outDataLocation: String): Unit = {


    // List of all TPC-DS v1.4 queries q14b q39a q39b q63 q64
    val tpcdsQueries = Seq(
      "q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9", "q10", "q11",
      "q12", "q13", "q14a","q14b" ,"q15", "q16", "q17", "q18", "q19", "q20",
      "q21", "q22", "q23a", "q23b", "q24a", "q24b", "q25", "q26", "q27", "q28", "q29", "q30",
      "q31", "q32", "q33", "q34", "q35", "q36", "q37", "q38","q39a", "q39b", "q40",
      "q41", "q42", "q43", "q44", "q45", "q46", "q47", "q48", "q49", "q50",
      "q51", "q52", "q53", "q54", "q55", "q56", "q57", "q58", "q59", "q60",
      "q61", "q62","q63", "q64" ,"q65", "q66", "q67", "q68", "q69", "q70",
      "q71", "q72", "q73", "q74", "q75", "q76", "q77", "q78", "q79", "q80",
      "q81", "q82", "q83", "q84", "q85", "q86", "q87", "q88", "q89", "q90",
      "q91", "q92", "q93", "q94", "q95", "q96", "q97", "q98", "q99")

    // This list only includes TPC-DS v2.7 queries that are different from v1.4 ones
    val tpcdsQueriesV2_7 = Seq(
      "q11")

    // If `--query-filter` defined, filters the queries that this option selects
    val queriesV1_4ToRun = filterQueries(tpcdsQueries, queryFilter)
    val queriesV2_7ToRun = filterQueries(tpcdsQueriesV2_7, queryFilter)

    if ((queriesV1_4ToRun ++ queriesV2_7ToRun).isEmpty) {
      throw new RuntimeException(
        s"Empty queries to run. Bad query name filter: ${queryFilter}")
    }

    val tableSizes = setupTables(tpcdsDataLocation)

    runTpcdsQueries(queryLocation = "tpcds",
      queries = queriesV1_4ToRun,
      tableSizes=tableSizes,
      outDataLocation = outDataLocation)
    runTpcdsQueries(queryLocation = "tpcds-v2.7.0",
     queries = queriesV2_7ToRun,
      tableSizes=tableSizes,
      nameSuffix = "-v2.7",
      outDataLocation = outDataLocation)
  }

  def main(args: Array[String]): Unit = {
    val benchmarkArgs = new TPCDSQueryBenchmarkArguments(args)
    run(queryFilter = benchmarkArgs.queryFilter,
      tpcdsDataLocation = benchmarkArgs.tpcdsDataLocation,
      outDataLocation = benchmarkArgs.outDataLocation)
  }
}
