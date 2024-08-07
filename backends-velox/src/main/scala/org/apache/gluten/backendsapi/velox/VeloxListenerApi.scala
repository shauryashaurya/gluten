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
package org.apache.gluten.backendsapi.velox

import org.apache.gluten.GlutenConfig
import org.apache.gluten.backendsapi.ListenerApi
import org.apache.gluten.exception.GlutenException
import org.apache.gluten.execution.datasource.{GlutenOrcWriterInjects, GlutenParquetWriterInjects, GlutenRowSplitter}
import org.apache.gluten.expression.UDFMappings
import org.apache.gluten.init.NativeBackendInitializer
import org.apache.gluten.utils._
import org.apache.gluten.vectorized.{JniLibLoader, JniWorkspace}

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.api.plugin.PluginContext
import org.apache.spark.sql.execution.datasources.velox.{VeloxOrcWriterInjects, VeloxParquetWriterInjects, VeloxRowSplitter}
import org.apache.spark.sql.expression.UDFResolver
import org.apache.spark.sql.internal.{GlutenConfigUtil, StaticSQLConf}
import org.apache.spark.util.SparkDirectoryUtil

import org.apache.commons.lang3.StringUtils

import scala.sys.process._

class VeloxListenerApi extends ListenerApi {
  private val ARROW_VERSION = "1500"

  override def onDriverStart(sc: SparkContext, pc: PluginContext): Unit = {
    val conf = pc.conf()
    // sql table cache serializer
    if (conf.getBoolean(GlutenConfig.COLUMNAR_TABLE_CACHE_ENABLED.key, defaultValue = false)) {
      conf.set(
        StaticSQLConf.SPARK_CACHE_SERIALIZER.key,
        "org.apache.spark.sql.execution.ColumnarCachedBatchSerializer")
    }
    initialize(conf, isDriver = true)
  }

  override def onDriverShutdown(): Unit = shutdown()

  override def onExecutorStart(pc: PluginContext): Unit = {
    initialize(pc.conf(), isDriver = false)
  }

  override def onExecutorShutdown(): Unit = shutdown()

  private def getLibraryLoaderForOS(
      systemName: String,
      systemVersion: String,
      system: String): SharedLibraryLoader = {
    if (systemName.contains("Ubuntu") && systemVersion.startsWith("20.04")) {
      new SharedLibraryLoaderUbuntu2004
    } else if (systemName.contains("Ubuntu") && systemVersion.startsWith("22.04")) {
      new SharedLibraryLoaderUbuntu2204
    } else if (systemName.contains("CentOS") && systemVersion.startsWith("8")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("CentOS") && systemVersion.startsWith("7")) {
      new SharedLibraryLoaderCentos7
    } else if (systemName.contains("Alibaba Cloud Linux") && systemVersion.startsWith("3")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("Alibaba Cloud Linux") && systemVersion.startsWith("2")) {
      new SharedLibraryLoaderCentos7
    } else if (systemName.contains("Anolis") && systemVersion.startsWith("8")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("Anolis") && systemVersion.startsWith("7")) {
      new SharedLibraryLoaderCentos7
    } else if (system.contains("tencentos") && system.contains("2.4")) {
      new SharedLibraryLoaderCentos7
    } else if (system.contains("tencentos") && system.contains("3.2")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("Red Hat") && systemVersion.startsWith("9")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("Red Hat") && systemVersion.startsWith("8")) {
      new SharedLibraryLoaderCentos8
    } else if (systemName.contains("Red Hat") && systemVersion.startsWith("7")) {
      new SharedLibraryLoaderCentos7
    } else if (systemName.contains("Debian") && systemVersion.startsWith("11")) {
      new SharedLibraryLoaderDebian11
    } else if (systemName.contains("Debian") && systemVersion.startsWith("12")) {
      new SharedLibraryLoaderDebian12
    } else {
      throw new GlutenException(
        s"Found unsupported OS($systemName, $systemVersion)! Currently, Gluten's Velox backend" +
          " only supports Ubuntu 20.04/22.04, CentOS 7/8, " +
          "Alibaba Cloud Linux 2/3 & Anolis 7/8, tencentos 2.4/3.2, RedHat 7/8, " +
          "Debian 11/12.")
    }
  }

  private def loadLibFromJar(load: JniLibLoader, conf: SparkConf): Unit = {
    val systemName = conf.getOption(GlutenConfig.GLUTEN_LOAD_LIB_OS)
    val loader = if (systemName.isDefined) {
      val systemVersion = conf.getOption(GlutenConfig.GLUTEN_LOAD_LIB_OS_VERSION)
      if (systemVersion.isEmpty) {
        throw new GlutenException(
          s"${GlutenConfig.GLUTEN_LOAD_LIB_OS_VERSION} must be specified when specifies the " +
            s"${GlutenConfig.GLUTEN_LOAD_LIB_OS}")
      }
      getLibraryLoaderForOS(systemName.get, systemVersion.get, "")
    } else {
      val system = "cat /etc/os-release".!!
      val systemNamePattern = "^NAME=\"?(.*)\"?".r
      val systemVersionPattern = "^VERSION=\"?(.*)\"?".r
      val systemInfoLines = system.stripMargin.split("\n")
      val systemNamePattern(systemName) =
        systemInfoLines.find(_.startsWith("NAME=")).getOrElse("")
      val systemVersionPattern(systemVersion) =
        systemInfoLines.find(_.startsWith("VERSION=")).getOrElse("")
      if (systemName.isEmpty || systemVersion.isEmpty) {
        throw new GlutenException("Failed to get OS name and version info.")
      }
      getLibraryLoaderForOS(systemName, systemVersion, system)
    }
    loader.loadLib(load)
  }

  private def loadLibWithLinux(conf: SparkConf, loader: JniLibLoader): Unit = {
    if (
      conf.getBoolean(
        GlutenConfig.GLUTEN_LOAD_LIB_FROM_JAR,
        GlutenConfig.GLUTEN_LOAD_LIB_FROM_JAR_DEFAULT)
    ) {
      loadLibFromJar(loader, conf)
    }
  }

  private def loadLibWithMacOS(loader: JniLibLoader): Unit = {
    // Placeholder for loading shared libs on MacOS if user needs.
  }

  private def initialize(conf: SparkConf, isDriver: Boolean): Unit = {
    SparkDirectoryUtil.init(conf)
    UDFResolver.resolveUdfConf(conf, isDriver = isDriver)
    if (conf.getBoolean(GlutenConfig.GLUTEN_DEBUG_KEEP_JNI_WORKSPACE, defaultValue = false)) {
      val debugDir = conf.get(GlutenConfig.GLUTEN_DEBUG_KEEP_JNI_WORKSPACE_DIR)
      JniWorkspace.enableDebug(debugDir)
    }
    val loader = JniWorkspace.getDefault.libLoader

    val osName = System.getProperty("os.name")
    if (osName.startsWith("Mac OS X") || osName.startsWith("macOS")) {
      loadLibWithMacOS(loader)
    } else {
      loadLibWithLinux(conf, loader)
    }

    // Set the system properties.
    // Use appending policy for children with the same name in a arrow struct vector.
    System.setProperty("arrow.struct.conflict.policy", "CONFLICT_APPEND")

    // Load supported hive/python/scala udfs
    UDFMappings.loadFromSparkConf(conf)

    val libPath = conf.get(GlutenConfig.GLUTEN_LIB_PATH, StringUtils.EMPTY)
    if (StringUtils.isNotBlank(libPath)) { // Path based load. Ignore all other loadees.
      JniLibLoader.loadFromPath(libPath, false)
    } else {
      val baseLibName = conf.get(GlutenConfig.GLUTEN_LIB_NAME, "gluten")
      loader.mapAndLoad(baseLibName, false)
      loader.mapAndLoad(VeloxBackend.BACKEND_NAME, false)
    }

    val parsed = GlutenConfigUtil.parseConfig(conf.getAll.toMap)
    NativeBackendInitializer.initializeBackend(parsed)

    // inject backend-specific implementations to override spark classes
    // FIXME: The following set instances twice in local mode?
    GlutenParquetWriterInjects.setInstance(new VeloxParquetWriterInjects())
    GlutenOrcWriterInjects.setInstance(new VeloxOrcWriterInjects())
    GlutenRowSplitter.setInstance(new VeloxRowSplitter())
  }

  private def shutdown(): Unit = {
    // TODO shutdown implementation in velox to release resources
  }
}

object VeloxListenerApi {}
