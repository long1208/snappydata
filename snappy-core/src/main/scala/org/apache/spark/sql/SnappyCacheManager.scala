/*
 * Copyright (c) 2010-2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql

import org.apache.spark.sql.columnar.InMemoryAppendableRelation
import org.apache.spark.sql.execution.SparkPlan

import scala.collection.mutable

import org.apache.spark.sql.store.ExternalStore
import org.apache.spark.storage.StorageLevel

/**
 * Snappy CacheManager extension to allow for appending data to cache.
 */
private[sql] class SnappyCacheManager extends execution.CacheManager {

  /**
   * Caches the data produced by the logical representation of the given
   * schema rdd. Unlike `RDD.cache()`, the default storage level is set to be
   * `MEMORY_AND_DISK` because recomputing the in-memory columnar representation
   * of the underlying table is expensive.
   */
  override private[sql] def cacheQuery(query: DataFrame,
      tableName: Option[String] = None,
      storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK) = writeLock {

    val alreadyCached = lookupCachedData(query.logicalPlan)
    if (alreadyCached.nonEmpty) {
      logWarning("SnappyCacheManager: asked to cache already cached data.")
    } else {

      val sqlContext = query.sqlContext

      cachedData += execution.CachedData(query.logicalPlan,
        getRelation(sqlContext, storageLevel, query.queryExecution.executedPlan, tableName, query)
        )
    }
  }

  private[sql] def cacheQuery_ext(query: DataFrame, tableName: Option[String],
      jdbcSource: ExternalStore) = writeLock {
    val alreadyCached = lookupCachedData(query.logicalPlan)
    if (alreadyCached.nonEmpty) {
      logWarning("SnappyCacheManager: asked to cache already cached data.")
    } else {
      val sqlContext = query.sqlContext

      cachedData += execution.CachedData(query.logicalPlan,
        columnar.ExternalStoreRelation(
          sqlContext.conf.useCompression,
          sqlContext.conf.columnBatchSize,
          StorageLevel.MEMORY_AND_DISK, // soubhik: RDD[UUID] should spill to disk. No ?
          // StorageLevel.NONE, // storage level is meaningless in external store. set anything
          query.queryExecution.executedPlan,
          // all the properties including url should be in props
          tableName, jdbcSource))
    }
  }

  private val stores = new mutable.HashMap[String, Map[String, Any]]()

  def registerExternalStore(name: String, connProps: Map[String, Any]) = {
    if (stores.contains(name)) {
      throw new IllegalArgumentException(s"a store already registered with this name $name")
    }
    stores.put(name, connProps)
  }


  def getRelation(sqlContext: SQLContext, storageLevel: StorageLevel,
                  executedPlan : SparkPlan, tableName: Option[String], query: DataFrame): InMemoryAppendableRelation =
    columnar.InMemoryAppendableRelation(
      sqlContext.conf.useCompression,
      sqlContext.conf.columnBatchSize,
      storageLevel,
      executedPlan,
      tableName)


}
