/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.hive;

import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.CatalogLock;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.flink.DataCatalogTable;
import org.apache.paimon.flink.FlinkCatalog;
import org.apache.paimon.fs.local.LocalFileIO;
import org.apache.paimon.hive.annotation.Minio;
import org.apache.paimon.hive.runner.PaimonEmbeddedHiveRunner;
import org.apache.paimon.s3.MinioTestContainer;
import org.apache.paimon.table.Table;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.annotations.HiveSQL;
import org.apache.flink.core.fs.Path;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotEmptyException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IT cases for using Paimon {@link HiveCatalog} together with Paimon Hive connector. */
@RunWith(PaimonEmbeddedHiveRunner.class)
public abstract class HiveCatalogITCaseBase {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    protected String path;
    protected TableEnvironment tEnv;

    @HiveSQL(files = {})
    protected static HiveShell hiveShell;

    @Minio private static MinioTestContainer minioTestContainer;

    private void before(boolean locationInProperties) throws Exception {
        hiveShell.execute("CREATE DATABASE IF NOT EXISTS test_db");
        hiveShell.execute("USE test_db");
        hiveShell.execute("CREATE TABLE hive_table ( a INT, b STRING )");
        hiveShell.execute("INSERT INTO hive_table VALUES (100, 'Hive'), (200, 'Table')");
        hiveShell.executeQuery("SHOW TABLES");

        Map<String, String> catalogProperties = new HashMap<>();
        catalogProperties.put("type", "paimon");
        catalogProperties.put("metastore", "hive");
        catalogProperties.put("uri", "");
        catalogProperties.put("lock.enabled", "true");
        catalogProperties.put("location-in-properties", String.valueOf(locationInProperties));
        if (locationInProperties) {
            path = minioTestContainer.getS3UriForDefaultBucket() + "/" + UUID.randomUUID();
            catalogProperties.putAll(minioTestContainer.getS3ConfigOptions());
        } else {
            path = folder.newFolder().toURI().toString();
        }
        catalogProperties.put("warehouse", path);

        EnvironmentSettings settings = EnvironmentSettings.newInstance().inBatchMode().build();
        tEnv = TableEnvironmentImpl.create(settings);
        tEnv.executeSql(
                        String.join(
                                "\n",
                                "CREATE CATALOG my_hive WITH (",
                                catalogProperties.entrySet().stream()
                                        .map(
                                                e ->
                                                        String.format(
                                                                "'%s' = '%s'",
                                                                e.getKey(), e.getValue()))
                                        .collect(Collectors.joining(",\n")),
                                ")"))
                .await();
        tEnv.executeSql("USE CATALOG my_hive").await();
        tEnv.executeSql("USE test_db").await();
    }

    private void after() {
        hiveShell.execute("DROP DATABASE IF EXISTS test_db CASCADE");
        hiveShell.execute("DROP DATABASE IF EXISTS test_db2 CASCADE");
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface LocationInProperties {}

    @Rule
    public TestRule environmentRule =
            (base, description) ->
                    new Statement() {
                        @Override
                        public void evaluate() throws Throwable {
                            try {
                                before(
                                        description.getAnnotation(LocationInProperties.class)
                                                != null);
                                base.evaluate();
                            } finally {
                                after();
                            }
                        }
                    };

    @Test
    public void testDatabaseOperations() throws Exception {
        // create database
        tEnv.executeSql("CREATE DATABASE test_db2").await();
        assertThat(collect("SHOW DATABASES"))
                .isEqualTo(Arrays.asList(Row.of("default"), Row.of("test_db"), Row.of("test_db2")));
        tEnv.executeSql("CREATE DATABASE IF NOT EXISTS test_db2").await();

        assertThatThrownBy(() -> tEnv.executeSql("CREATE DATABASE test_db2").await())
                .hasRootCauseInstanceOf(DatabaseAlreadyExistException.class)
                .hasRootCauseMessage("Database test_db2 already exists in Catalog my_hive.");

        // drop database
        tEnv.executeSql("DROP DATABASE test_db2").await();
        assertThat(collect("SHOW DATABASES"))
                .isEqualTo(Arrays.asList(Row.of("default"), Row.of("test_db")));
        tEnv.executeSql("DROP DATABASE IF EXISTS test_db2").await();

        assertThatThrownBy(() -> tEnv.executeSql("DROP DATABASE test_db2").await())
                .hasRootCauseInstanceOf(DatabaseNotExistException.class)
                .hasRootCauseMessage("Database test_db2 does not exist in Catalog my_hive.");

        // drop non-empty database
        tEnv.executeSql("CREATE DATABASE test_db2").await();
        tEnv.executeSql("USE test_db2").await();
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql("INSERT INTO t VALUES (1, 'Hi'), (2, 'Hello')").await();
        Path tablePath = new Path(path, "test_db2.db/t");
        assertThat(tablePath.getFileSystem().exists(tablePath)).isTrue();
        assertThatThrownBy(() -> tEnv.executeSql("DROP DATABASE test_db2").await())
                .hasRootCauseInstanceOf(DatabaseNotEmptyException.class)
                .hasRootCauseMessage("Database test_db2 in catalog my_hive is not empty.");

        tEnv.executeSql("DROP DATABASE test_db2 CASCADE").await();
        assertThat(collect("SHOW DATABASES"))
                .isEqualTo(Arrays.asList(Row.of("default"), Row.of("test_db")));
        assertThat(tablePath.getFileSystem().exists(tablePath)).isFalse();
    }

    @Test
    public void testTableOperations() throws Exception {
        // create table
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql("CREATE TABLE s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        assertThat(collect("SHOW TABLES")).isEqualTo(Arrays.asList(Row.of("s"), Row.of("t")));

        tEnv.executeSql(
                        "CREATE TABLE IF NOT EXISTS s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE s ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                                        .await())
                .hasRootCauseInstanceOf(TableAlreadyExistException.class)
                .hasRootCauseMessage(
                        "Table (or view) test_db.s already exists in Catalog my_hive.");

        // drop table
        tEnv.executeSql("INSERT INTO s VALUES (1, 'Hi'), (2, 'Hello')").await();
        Path tablePath = new Path(path, "test_db.db/s");
        assertThat(tablePath.getFileSystem().exists(tablePath)).isTrue();
        tEnv.executeSql("DROP TABLE s").await();
        assertThat(collect("SHOW TABLES")).isEqualTo(Collections.singletonList(Row.of("t")));
        assertThat(tablePath.getFileSystem().exists(tablePath)).isFalse();
        tEnv.executeSql("DROP TABLE IF EXISTS s").await();
        assertThatThrownBy(() -> tEnv.executeSql("DROP TABLE s").await())
                .isInstanceOf(ValidationException.class)
                .hasMessage("Table with identifier 'my_hive.test_db.s' does not exist.");

        assertThatThrownBy(() -> tEnv.executeSql("DROP TABLE hive_table").await())
                .isInstanceOf(ValidationException.class)
                .hasMessage("Table with identifier 'my_hive.test_db.hive_table' does not exist.");

        // alter table
        tEnv.executeSql("ALTER TABLE t SET ( 'manifest.target-file-size' = '16MB' )").await();
        List<Row> actual = collect("SHOW CREATE TABLE t");
        assertThat(actual.size()).isEqualTo(1);
        assertThat(
                        actual.get(0)
                                .getField(0)
                                .toString()
                                .contains("'manifest.target-file-size' = '16MB'"))
                .isTrue();

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "ALTER TABLE s SET ( 'manifest.target-file-size' = '16MB' )")
                                        .await())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Table `my_hive`.`test_db`.`s` doesn't exist or is a temporary table.");

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "ALTER TABLE hive_table SET ( 'manifest.target-file-size' = '16MB' )")
                                        .await())
                .isInstanceOf(RuntimeException.class)
                .hasMessage(
                        "Table `my_hive`.`test_db`.`hive_table` doesn't exist or is a temporary table.");
    }

    @Test
    public void testCreateExternalTable() throws Exception {
        tEnv.executeSql(
                        String.join(
                                "\n",
                                "CREATE CATALOG my_hive_external WITH (",
                                "  'type' = 'paimon',",
                                "  'metastore' = 'hive',",
                                "  'uri' = '',",
                                "  'warehouse' = '" + path + "',",
                                "  'lock.enabled' = 'true',",
                                "  'table.type' = 'EXTERNAL'",
                                ")"))
                .await();
        tEnv.executeSql("USE CATALOG my_hive_external").await();
        tEnv.executeSql("USE test_db").await();
        tEnv.executeSql("CREATE TABLE t ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                .await();
        assertThat(
                        hiveShell
                                .executeQuery("DESC FORMATTED t")
                                .contains("Table Type:         \tEXTERNAL_TABLE      \tNULL"))
                .isTrue();
        tEnv.executeSql("DROP TABLE t").await();
        Path tablePath = new Path(path, "test_db.db/t");
        assertThat(tablePath.getFileSystem().exists(tablePath)).isFalse();
    }

    @Test
    public void testFlinkWriteAndHiveRead() throws Exception {
        tEnv.executeSql(
                        "CREATE TABLE t ( "
                                + "f0 BOOLEAN, "
                                + "f1 TINYINT, "
                                + "f2 SMALLINT, "
                                + "f3 INT, "
                                + "f4 BIGINT, "
                                + "f5 FLOAT, "
                                + "f6 DOUBLE, "
                                + "f7 DECIMAL(10,2), "
                                + "f8 CHAR(3), "
                                + "f9 VARCHAR(10), "
                                + "f10 STRING, "
                                + "f11 BINARY, "
                                + "f12 VARBINARY, "
                                + "f13 DATE, "
                                + "f14 TIMESTAMP(6), "
                                + "f15 ARRAY<STRING>, "
                                + "f16 Map<STRING, STRING>, "
                                + "f17 ROW<f0 STRING, f1 INT>"
                                + ") WITH ( 'file.format' = 'avro' )")
                .await();
        tEnv.executeSql(
                        "INSERT INTO t VALUES "
                                + "(true, CAST(1 AS TINYINT), CAST(1 AS SMALLINT), 1, 1234567890123456789, 1.23, 3.14159, CAST('1234.56' AS DECIMAL(10, 2)), 'ABC', 'v1', 'Hello, World!', X'010203', X'010203', DATE '2023-01-01', TIMESTAMP '2023-01-01 12:00:00.123', ARRAY['value1', 'value2', 'value3'], MAP['key1', 'value1', 'key2', 'value2'], ROW('v1', 1)), "
                                + "(false, CAST(2 AS TINYINT), CAST(2 AS SMALLINT), 2, 234567890123456789, 2.34, 2.111111, CAST('2345.67' AS DECIMAL(10, 2)), 'DEF', 'v2', 'Apache Paimon', X'040506',X'040506', DATE '2023-02-01', TIMESTAMP '2023-02-01 12:00:00.456', ARRAY['value4', 'value5', 'value6'], MAP['key1', 'value11', 'key2', 'value22'], ROW('v2', 2))")
                .await();
        assertThat(
                        hiveShell.executeQuery(
                                "SELECT f0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, hex(f11), hex(f12), f13, f14, f15, f15[0] as f15a, f16['key1'] as f16a, f16['key2'] as f16b, f17, f17.f0, f17.f1 FROM t ORDER BY f3"))
                .isEqualTo(
                        Arrays.asList(
                                "true\t1\t1\t1\t1234567890123456789\t1.23\t3.14159\t1234.56\tABC\tv1\tHello, World!\t01\t010203\t2023-01-01\t2023-01-01 12:00:00.123\t[\"value1\",\"value2\",\"value3\"]\tvalue1\tvalue1\tvalue2\t{\"f0\":\"v1\",\"f1\":1}\tv1\t1",
                                "false\t2\t2\t2\t234567890123456789\t2.34\t2.111111\t2345.67\tDEF\tv2\tApache Paimon\t04\t040506\t2023-02-01\t2023-02-01 12:00:00.456\t[\"value4\",\"value5\",\"value6\"]\tvalue4\tvalue11\tvalue22\t{\"f0\":\"v2\",\"f1\":2}\tv2\t2"));

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "INSERT INTO hive_table VALUES (1, 'Hi'), (2, 'Hello')")
                                        .await())
                .isInstanceOf(TableException.class)
                .hasMessage(
                        "Cannot find table '`my_hive`.`test_db`.`hive_table`' in any of the catalogs [default_catalog, my_hive], nor as a temporary table.");
    }

    /**
     * Test flink writing and hive reading to compare partitions and non-partitions table results.
     */
    @Test
    public void testFlinkWriteAndHiveReadToCompare() throws Exception {
        // Use flink to create a partitioned table and write data, hive read.
        tEnv.executeSql(
                        "create table students\n"
                                + "(id decimal(20,0)\n"
                                + ",upload_insert TIMESTAMP\n"
                                + ",dt string\n"
                                + ",PRIMARY KEY(id,dt) NOT ENFORCED\n"
                                + ") PARTITIONED BY (dt)\n"
                                + "WITH (\n"
                                + "'bucket' = '-1',\n"
                                + "'file.format' = 'parquet',\n"
                                + "'metastore.partitioned-table' = 'true'\n"
                                + ");")
                .await();
        tEnv.executeSql(
                        "insert into students select cast(1 as decimal(20,0)) as id,to_timestamp('2023-08-01 14:03:00.123456') as upload_insert,'20230801' as dt;")
                .await();
        List<String> partitionedTableResult = hiveShell.executeQuery("SELECT * from students");

        // Use flink to create a non-partitioned table and write data, hive read.
        tEnv.executeSql(
                        "create table students1\n"
                                + "(id decimal(20,0)\n"
                                + ",upload_insert TIMESTAMP\n"
                                + ",dt string\n"
                                + ",PRIMARY KEY(id,dt) NOT ENFORCED\n"
                                + ") PARTITIONED BY (dt)\n"
                                + "WITH (\n"
                                + "'bucket' = '-1',\n"
                                + "'file.format' = 'parquet'\n"
                                + ");")
                .await();
        tEnv.executeSql(
                        "insert into students1 select cast(1 as decimal(20,0)) as id,to_timestamp('2023-08-01 14:03:00.123456') as upload_insert,'20230801' as dt;")
                .await();
        List<String> nonPartitionedTableResult = hiveShell.executeQuery("SELECT * from students1");
        assertThat(partitionedTableResult).containsAll(nonPartitionedTableResult);
    }

    @Test
    public void testHiveCreateAndFlinkRead() throws Exception {
        hiveShell.execute("SET hive.metastore.warehouse.dir=" + path);
        hiveShell.execute(
                "CREATE TABLE hive_test_table ( a INT, b STRING ) "
                        + "STORED BY '"
                        + PaimonStorageHandler.class.getName()
                        + "'");
        hiveShell.execute("INSERT INTO hive_test_table VALUES (1, 'Apache'), (2, 'Paimon')");
        List<Row> actual = collect("SELECT * FROM hive_test_table");
        assertThat(actual).contains(Row.of(1, "Apache"), Row.of(2, "Paimon"));
    }

    @Test
    public void testHiveCreateAndFlinkInsertRead() throws Exception {
        hiveShell.execute("SET hive.metastore.warehouse.dir=" + path);
        hiveShell.execute(
                "CREATE TABLE hive_test_table ( a INT, b STRING ) "
                        + "STORED BY '"
                        + PaimonStorageHandler.class.getName()
                        + "'"
                        + "TBLPROPERTIES ("
                        + "  'primary-key'='a'"
                        + ")");
        tEnv.executeSql("INSERT INTO hive_test_table VALUES (1, 'Apache'), (2, 'Paimon')");
        List<Row> actual = collect("SELECT * FROM hive_test_table");
        assertThat(actual).contains(Row.of(1, "Apache"), Row.of(2, "Paimon"));
    }

    @Test
    public void testCreateTableAs() throws Exception {
        tEnv.executeSql("CREATE TABLE t (a INT)").await();
        tEnv.executeSql("INSERT INTO t VALUES(1)").await();
        tEnv.executeSql("CREATE TABLE t1 AS SELECT * FROM t").await();
        List<Row> result =
                collect(
                        "SELECT schema_id, fields, partition_keys, "
                                + "primary_keys, options, `comment`  FROM t1$schemas s");
        assertThat(result.toString())
                .isEqualTo("[+I[0, [{\"id\":0,\"name\":\"a\",\"type\":\"INT\"}], [], [], {}, ]]");
        List<Row> data = collect("SELECT * FROM t1");
        assertThat(data).contains(Row.of(1));

        // change option
        tEnv.executeSql("CREATE TABLE t_option (a INT)").await();
        tEnv.executeSql("INSERT INTO t_option VALUES(1)").await();
        tEnv.executeSql(
                        "CREATE TABLE t1_option WITH ('file.format' = 'parquet') AS SELECT * FROM t_option")
                .await();
        List<Row> resultOption = collect("SELECT * FROM t1_option$options");
        assertThat(resultOption).containsExactly(Row.of("file.format", "parquet"));
        List<Row> dataOption = collect("SELECT * FROM t1_option");
        assertThat(dataOption).contains(Row.of(1));

        // partition table
        tEnv.executeSql(
                "CREATE TABLE t_p (\n"
                        + "    user_id BIGINT,\n"
                        + "    item_id BIGINT,\n"
                        + "    behavior STRING,\n"
                        + "    dt STRING,\n"
                        + "    hh STRING\n"
                        + ") PARTITIONED BY (dt, hh)");
        tEnv.executeSql("INSERT INTO t_p  SELECT 1,2,'a','2023-02-19','12'").await();
        tEnv.executeSql("CREATE TABLE t1_p WITH ('partition' = 'dt') AS SELECT * FROM t_p").await();
        List<Row> resultPartition =
                collect(
                        "SELECT schema_id, fields, partition_keys, "
                                + "primary_keys, options, `comment`  FROM t1_p$schemas s");
        assertThat(resultPartition.toString())
                .isEqualTo(
                        "[+I[0, [{\"id\":0,\"name\":\"user_id\",\"type\":\"BIGINT\"},{\"id\":1,\"name\":\"item_id\",\"type\":\"BIGINT\"},{\"id\":2,\"name\":\"behavior\",\"type\":\"STRING\"}"
                                + ",{\"id\":3,\"name\":\"dt\",\"type\":\"STRING\"},{\"id\":4,\"name\":\"hh\",\"type\":\"STRING\"}], [\"dt\"], [], {}, ]]");
        List<Row> dataPartition = collect("SELECT * FROM t1_p");
        assertThat(dataPartition.toString()).isEqualTo("[+I[1, 2, a, 2023-02-19, 12]]");

        // primary key
        tEnv.executeSql(
                        "CREATE TABLE t_pk (\n"
                                + "    user_id BIGINT,\n"
                                + "    item_id BIGINT,\n"
                                + "    behavior STRING,\n"
                                + "    dt STRING,\n"
                                + "    hh STRING,\n"
                                + "    PRIMARY KEY (dt, hh, user_id) NOT ENFORCED\n"
                                + ")")
                .await();
        tEnv.executeSql("INSERT INTO t_pk VALUES(1,2,'aaa','2020-01-02','09')").await();
        tEnv.executeSql("CREATE TABLE t_pk_as WITH ('primary-key' = 'dt') AS SELECT * FROM t_pk")
                .await();
        List<Row> resultPk = collect("SHOW CREATE TABLE t_pk_as");
        assertThat(resultPk.toString()).contains("PRIMARY KEY (`dt`)");
        List<Row> dataPk = collect("SELECT * FROM t_pk_as");
        assertThat(dataPk.toString()).isEqualTo("[+I[1, 2, aaa, 2020-01-02, 09]]");

        // primary key + partition
        tEnv.executeSql(
                        "CREATE TABLE t_all (\n"
                                + "    user_id BIGINT,\n"
                                + "    item_id BIGINT,\n"
                                + "    behavior STRING,\n"
                                + "    dt STRING,\n"
                                + "    hh STRING,\n"
                                + "    PRIMARY KEY (dt, hh, user_id) NOT ENFORCED\n"
                                + ") PARTITIONED BY (dt, hh)")
                .await();
        tEnv.executeSql("INSERT INTO t_all VALUES(1,2,'login','2020-01-02','09')").await();
        tEnv.executeSql(
                        "CREATE TABLE t_all_as WITH ('primary-key' = 'dt,hh' , 'partition' = 'dt' ) AS SELECT * FROM t_all")
                .await();
        List<Row> resultAll = collect("SHOW CREATE TABLE t_all_as");
        assertThat(resultAll.toString()).contains("PRIMARY KEY (`dt`, `hh`)");
        assertThat(resultAll.toString()).contains("PARTITIONED BY (`dt`)");
        List<Row> dataAll = collect("SELECT * FROM t_all_as");
        assertThat(dataAll.toString()).isEqualTo("[+I[1, 2, login, 2020-01-02, 09]]");

        // primary key do not exist.
        tEnv.executeSql(
                        "CREATE TABLE t_pk_not_exist (\n"
                                + "    user_id BIGINT,\n"
                                + "    item_id BIGINT,\n"
                                + "    behavior STRING,\n"
                                + "    dt STRING,\n"
                                + "    hh STRING,\n"
                                + "    PRIMARY KEY (dt, hh, user_id) NOT ENFORCED\n"
                                + ")")
                .await();

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t_pk_not_exist_as WITH ('primary-key' = 'aaa') AS SELECT * FROM t_pk_not_exist")
                                        .await())
                .hasRootCauseMessage(
                        "Table column [user_id, item_id, behavior, dt, hh] should include all primary key constraint [aaa]");

        // primary key in option and DDL.
        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t_pk_ddl_option ("
                                                        + "    user_id BIGINT,"
                                                        + "    item_id BIGINT,"
                                                        + "    behavior STRING,"
                                                        + "    dt STRING,"
                                                        + "    hh STRING,"
                                                        + "    PRIMARY KEY (dt, hh, user_id) NOT ENFORCED"
                                                        + ") WITH ('primary-key' = 'dt')")
                                        .await())
                .hasRootCauseMessage(
                        "Cannot define primary key on DDL and table options at the same time.");

        // partition do not exist.
        tEnv.executeSql(
                        "CREATE TABLE t_partition_not_exist (\n"
                                + "    user_id BIGINT,\n"
                                + "    item_id BIGINT,\n"
                                + "    behavior STRING,\n"
                                + "    dt STRING,\n"
                                + "    hh STRING\n"
                                + ") PARTITIONED BY (dt, hh) ")
                .await();

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t_partition_not_exist_as WITH ('partition' = 'aaa') AS SELECT * FROM t_partition_not_exist")
                                        .await())
                .hasRootCauseMessage(
                        "Table column [user_id, item_id, behavior, dt, hh] should include all partition fields [aaa]");

        // partition in option and DDL.
        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t_partition_ddl_option ("
                                                        + "    user_id BIGINT,"
                                                        + "    item_id BIGINT,"
                                                        + "    behavior STRING,"
                                                        + "    dt STRING,"
                                                        + "    hh STRING"
                                                        + ") PARTITIONED BY (dt, hh)  WITH ('partition' = 'dt')")
                                        .await())
                .hasRootCauseMessage(
                        "Cannot define partition on DDL and table options at the same time.");
    }

    @Test
    public void testRenameTable() throws Exception {
        tEnv.executeSql("CREATE TABLE t1 (a INT)").await();
        tEnv.executeSql("CREATE TABLE t2 (a INT)").await();
        tEnv.executeSql("INSERT INTO t1 SELECT 1").await();
        // the source table do not exist.
        assertThatThrownBy(() -> tEnv.executeSql("ALTER TABLE t3 RENAME TO t4"))
                .hasMessage(
                        "Table `my_hive`.`test_db`.`t3` doesn't exist or is a temporary table.");

        // the target table has existed.
        assertThatThrownBy(() -> tEnv.executeSql("ALTER TABLE t1 RENAME TO t2"))
                .hasMessage(
                        "Could not execute ALTER TABLE my_hive.test_db.t1 RENAME TO my_hive.test_db.t2");

        // the target table name has upper case.
        assertThatThrownBy(() -> tEnv.executeSql("ALTER TABLE t1 RENAME TO T1"))
                .hasMessage("Table name [T1] cannot contain upper case in the catalog.");

        tEnv.executeSql("ALTER TABLE t1 RENAME TO t3").await();

        // hive read
        List<String> tables = hiveShell.executeQuery("SHOW TABLES");
        assertThat(tables.contains("t3")).isTrue();
        assertThat(tables.contains("t1")).isFalse();
        List<String> data = hiveShell.executeQuery("SELECT * FROM t3");
        assertThat(data).containsExactlyInAnyOrder("1");

        // flink read
        List<Row> tablesFromFlink = collect("SHOW TABLES");
        assertThat(tablesFromFlink).contains(Row.of("t3"));
        assertThat(tablesFromFlink).doesNotContain(Row.of("t1"));

        List<Row> dataFromFlink = collect("SELECT * FROM t3");
        assertThat(dataFromFlink).contains(Row.of(1));
    }

    @Test
    public void testAlterTable() throws Exception {
        tEnv.executeSql("CREATE TABLE t1 (a INT, b STRING, c TIMESTAMP(3))").await();
        List<String> result =
                collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[a, INT, true, null, null, null]",
                        "+I[b, STRING, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]");

        // Dropping Columns
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 DROP b")).doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[a, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]");

        // Adding New Columns
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 ADD (d BIGINT, e CHAR(5))"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[a, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]",
                        "+I[d, BIGINT, true, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null]");

        // Adding Column Position
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 ADD f INT AFTER a"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[a, INT, true, null, null, null]",
                        "+I[f, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]",
                        "+I[d, BIGINT, true, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null]");

        // Changing Column Position
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 MODIFY f INT AFTER e"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[a, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]",
                        "+I[d, BIGINT, true, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null]",
                        "+I[f, INT, true, null, null, null]");

        // Renaming Column Name
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 RENAME a TO g"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[g, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]",
                        "+I[d, BIGINT, true, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null]",
                        "+I[f, INT, true, null, null, null]");

        // Changing Column Type
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 MODIFY d DOUBLE"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[g, INT, true, null, null, null]",
                        "+I[c, TIMESTAMP(3), true, null, null, null]",
                        "+I[d, DOUBLE, true, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null]",
                        "+I[f, INT, true, null, null, null]");

        // Changing Column Comment
        assertThatCode(() -> tEnv.executeSql("ALTER TABLE t1 MODIFY g INT COMMENT 'test comment'"))
                .doesNotThrowAnyException();
        result = collect("DESC t1").stream().map(Objects::toString).collect(Collectors.toList());
        assertThat(result)
                .containsExactly(
                        "+I[g, INT, true, null, null, null, test comment]",
                        "+I[c, TIMESTAMP(3), true, null, null, null, null]",
                        "+I[d, DOUBLE, true, null, null, null, null]",
                        "+I[e, CHAR(5), true, null, null, null, null]",
                        "+I[f, INT, true, null, null, null, null]");
    }

    @Test
    public void testHiveLock() throws InterruptedException {
        tEnv.executeSql("CREATE TABLE t (a INT)");
        CatalogLock.Factory lockFactory =
                ((FlinkCatalog) tEnv.getCatalog(tEnv.getCurrentCatalog()).get())
                        .catalog()
                        .lockFactory()
                        .get();

        AtomicInteger count = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();
        Callable<Void> unsafeIncrement =
                () -> {
                    int nextCount = count.get() + 1;
                    Thread.sleep(1);
                    count.set(nextCount);
                    return null;
                };
        for (int i = 0; i < 10; i++) {
            Thread thread =
                    new Thread(
                            () -> {
                                CatalogLock lock = lockFactory.create();
                                for (int j = 0; j < 10; j++) {
                                    try {
                                        lock.runWithLock("test_db", "t", unsafeIncrement);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            });
            thread.start();
            threads.add(thread);
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(count.get()).isEqualTo(100);
    }

    @Test
    public void testUpperCase() {
        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE T ( a INT, b STRING ) WITH ( 'file.format' = 'avro' )")
                                        .await())
                .hasRootCauseMessage(
                        String.format(
                                "Table name [%s] cannot contain upper case in the catalog.", "T"));

        assertThatThrownBy(
                        () ->
                                tEnv.executeSql(
                                                "CREATE TABLE t (A INT, b STRING, C STRING) WITH ( 'file.format' = 'avro')")
                                        .await())
                .hasRootCauseMessage(
                        String.format(
                                "Field name %s cannot contain upper case in the catalog.",
                                "[A, C]"));
    }

    @Test
    public void testQuickPathInShowTables() throws Exception {
        collect("CREATE TABLE t ( a INT, b STRING )");
        List<Row> tables = collect("SHOW TABLES");
        assertThat(tables.toString()).isEqualTo("[+I[t]]");

        new LocalFileIO().delete(new org.apache.paimon.fs.Path(path, "test_db.db/t"), true);
        tables = collect("SHOW TABLES");
        assertThat(tables.toString()).isEqualTo("[]");
    }

    @Test
    public void testCatalogOptionsInheritAndOverride() throws Exception {
        tEnv.executeSql(
                        String.join(
                                "\n",
                                "CREATE CATALOG my_hive_options WITH (",
                                "  'type' = 'paimon',",
                                "  'metastore' = 'hive',",
                                "  'uri' = '',",
                                "  'warehouse' = '" + path + "',",
                                "  'lock.enabled' = 'true',",
                                "  'table-default.opt1' = 'value1',",
                                "  'table-default.opt2' = 'value2',",
                                "  'table-default.opt3' = 'value3'",
                                ")"))
                .await();
        tEnv.executeSql("USE CATALOG my_hive_options").await();

        // check inherit
        tEnv.executeSql("CREATE TABLE table_without_options (a INT, b STRING)").await();

        Identifier identifier = new Identifier("default", "table_without_options");
        Catalog catalog =
                ((FlinkCatalog) tEnv.getCatalog(tEnv.getCurrentCatalog()).get()).catalog();
        Map<String, String> tableOptions = catalog.getTable(identifier).options();

        assertThat(tableOptions).containsEntry("opt1", "value1");
        assertThat(tableOptions).containsEntry("opt2", "value2");
        assertThat(tableOptions).containsEntry("opt3", "value3");
        assertThat(tableOptions).doesNotContainKey("lock.enabled");

        // check override
        tEnv.executeSql(
                        "CREATE TABLE table_with_options (a INT, b STRING) WITH ('opt1' = 'new_value')")
                .await();
        identifier = new Identifier("default", "table_with_options");
        tableOptions = catalog.getTable(identifier).options();

        assertThat(tableOptions).containsEntry("opt1", "new_value");
        assertThat(tableOptions).containsEntry("opt2", "value2");
        assertThat(tableOptions).containsEntry("opt3", "value3");
        assertThat(tableOptions).doesNotContainKey("lock.enabled");
    }

    @Test
    public void testAddPartitionsToMetastore() throws Exception {
        prepareTestAddPartitionsToMetastore();

        String sql1 =
                "select v, ptb, k from t where "
                        + "pta >= 2 and ptb <= '3a' and (k % 10) >= 1 and (k % 10) <= 2";
        assertThat(hiveShell.executeQuery(sql1))
                .containsExactlyInAnyOrder(
                        "2001\t2a\t21", "2002\t2a\t22", "3001\t3a\t31", "3002\t3a\t32");

        String sql2 =
                "select v, ptb, k from t where "
                        + "pta >= 2 and ptb <= '3a' and (v % 10) >= 3 and (v % 10) <= 4";
        assertThat(hiveShell.executeQuery(sql2))
                .containsExactlyInAnyOrder(
                        "2003\t2a\t23", "2004\t2a\t24", "3003\t3a\t33", "3004\t3a\t34");
    }

    @Test
    @LocationInProperties
    public void testAddPartitionsToMetastoreLocationInProperties() throws Exception {
        prepareTestAddPartitionsToMetastore();

        String sql1 =
                "select v, ptb, k from t where "
                        + "pta >= 2 and ptb <= '3a' and (k % 10) >= 1 and (k % 10) <= 2";
        assertThat(collect(sql1).stream().map(Objects::toString).collect(Collectors.toList()))
                .containsExactlyInAnyOrder(
                        "+I[2001, 2a, 21]",
                        "+I[2002, 2a, 22]",
                        "+I[3001, 3a, 31]",
                        "+I[3002, 3a, 32]");

        String sql2 =
                "select v, ptb, k from t where "
                        + "pta >= 2 and ptb <= '3a' and (v % 10) >= 3 and (v % 10) <= 4";
        assertThat(collect(sql2).stream().map(Objects::toString).collect(Collectors.toList()))
                .containsExactlyInAnyOrder(
                        "+I[2003, 2a, 23]",
                        "+I[2004, 2a, 24]",
                        "+I[3003, 3a, 33]",
                        "+I[3004, 3a, 34]");
    }

    private void prepareTestAddPartitionsToMetastore() throws Exception {
        tEnv.executeSql(
                String.join(
                        "\n",
                        "CREATE TABLE t (",
                        "    pta INT,",
                        "    k INT,",
                        "    ptb VARCHAR(10),",
                        "    v BIGINT,",
                        "    PRIMARY KEY (k, pta, ptb) NOT ENFORCED",
                        ") PARTITIONED BY (ptb, pta) WITH (",
                        "    'bucket' = '2',",
                        "    'metastore.partitioned-table' = 'true'",
                        ")"));
        List<String> values = new ArrayList<>();
        for (int pta = 1; pta <= 3; pta++) {
            int k = pta * 10;
            int v = pta * 1000;
            for (int ptb = 0; ptb < 2; ptb++) {
                for (int i = 0; i < 5; i++) {
                    values.add(String.format("(%d, %d, '%d%c', %d)", pta, k, pta, 'a' + ptb, v));
                    k++;
                    v++;
                }
            }
        }
        tEnv.executeSql("INSERT INTO t VALUES " + String.join(", ", values)).await();

        assertThat(hiveShell.executeQuery("show partitions t"))
                .containsExactlyInAnyOrder(
                        "ptb=1a/pta=1",
                        "ptb=1b/pta=1",
                        "ptb=2a/pta=2",
                        "ptb=2b/pta=2",
                        "ptb=3a/pta=3",
                        "ptb=3b/pta=3");
    }

    @Test
    public void testAddPartitionsToMetastoreForUnpartitionedTable() throws Exception {
        tEnv.executeSql(
                String.join(
                        "\n",
                        "CREATE TABLE t (",
                        "    k INT,",
                        "    v BIGINT,",
                        "    PRIMARY KEY (k) NOT ENFORCED",
                        ") WITH (",
                        "    'bucket' = '2',",
                        "    'metastore.partitioned-table' = 'true'",
                        ")"));
        tEnv.executeSql("INSERT INTO t VALUES (1, 10), (2, 20)").await();
        assertThat(hiveShell.executeQuery("SELECT * FROM t ORDER BY k"))
                .containsExactlyInAnyOrder("1\t10", "2\t20");
    }

    @Test
    public void testAddPartitionsForTag() throws Exception {
        tEnv.executeSql(
                String.join(
                        "\n",
                        "CREATE TABLE t (",
                        "    k INT,",
                        "    v BIGINT,",
                        "    PRIMARY KEY (k) NOT ENFORCED",
                        ") WITH (",
                        "    'bucket' = '2',",
                        "    'metastore.tag-to-partition' = 'dt'",
                        ")"));
        tEnv.executeSql("INSERT INTO t VALUES (1, 10), (2, 20)").await();

        // TODO modify to CALL after Flink 1.18
        Table table =
                ((DataCatalogTable)
                                tEnv.getCatalog(tEnv.getCurrentCatalog())
                                        .get()
                                        .getTable(new ObjectPath(tEnv.getCurrentDatabase(), "t")))
                        .table();
        table.createTag("2023-10-16", 1);

        assertThat(hiveShell.executeQuery("SHOW PARTITIONS t"))
                .containsExactlyInAnyOrder("dt=2023-10-16");

        assertThat(hiveShell.executeQuery("SELECT k, v FROM t WHERE dt='2023-10-16'"))
                .containsExactlyInAnyOrder("1\t10", "2\t20");

        assertThat(hiveShell.executeQuery("SELECT * FROM t WHERE dt='2023-10-16'"))
                .containsExactlyInAnyOrder("1\t10\t2023-10-16", "2\t20\t2023-10-16");

        // another tag

        tEnv.executeSql("INSERT INTO t VALUES (3, 30), (4, 40)").await();
        table.createTag("2023-10-17", 2);

        assertThat(hiveShell.executeQuery("SELECT * FROM t"))
                .containsExactlyInAnyOrder(
                        "1\t10\t2023-10-16",
                        "2\t20\t2023-10-16",
                        "1\t10\t2023-10-17",
                        "2\t20\t2023-10-17",
                        "3\t30\t2023-10-17",
                        "4\t40\t2023-10-17");
    }

    @Test
    public void testAddPartitionsForTagPreview() throws Exception {
        tEnv.executeSql(
                String.join(
                        "\n",
                        "CREATE TABLE t (",
                        "    k INT,",
                        "    v BIGINT,",
                        "    PRIMARY KEY (k) NOT ENFORCED",
                        ") WITH (",
                        "    'bucket' = '2',",
                        "    'metastore.tag-to-partition' = 'dt',",
                        "    'metastore.tag-to-partition.preview' = 'process-time'",
                        ")"));

        tEnv.executeSql("INSERT INTO t VALUES (1, 10), (2, 20)").await();

        List<String> result = hiveShell.executeQuery("SHOW PARTITIONS t");
        assertThat(result).hasSize(1);
        String tag = result.get(0).split("=")[1];

        assertThat(hiveShell.executeQuery(String.format("SELECT k, v FROM t WHERE dt='%s'", tag)))
                .containsExactlyInAnyOrder("1\t10", "2\t20");

        tEnv.executeSql("INSERT INTO t VALUES (3, 30), (4, 40)").await();
        if (hiveShell.executeQuery("SHOW PARTITIONS t").size() == 1) {
            // no new partition
            assertThat(
                            hiveShell.executeQuery(
                                    String.format("SELECT k, v FROM t WHERE dt='%s'", tag)))
                    .containsExactlyInAnyOrder("1\t10", "2\t20", "3\t30", "4\t40");
        }
    }

    protected List<Row> collect(String sql) throws Exception {
        List<Row> result = new ArrayList<>();
        try (CloseableIterator<Row> it = tEnv.executeSql(sql).collect()) {
            while (it.hasNext()) {
                result.add(it.next());
            }
        }
        return result;
    }
}
