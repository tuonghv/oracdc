/**
 * Copyright (c) 2018-present, http://a2-solutions.eu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package eu.solutions.a2.cdc.oracle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import eu.solutions.a2.cdc.oracle.standalone.SendMethodIntf;
import eu.solutions.a2.cdc.oracle.standalone.avro.AvroSchema;
import eu.solutions.a2.cdc.oracle.standalone.avro.Envelope;
import eu.solutions.a2.cdc.oracle.standalone.avro.Payload;
import eu.solutions.a2.cdc.oracle.standalone.avro.Source;
import eu.solutions.a2.cdc.oracle.utils.ExceptionUtils;

public class OraTable implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(OraTable.class);

	@SuppressWarnings("serial")
	private static final Map<Integer, String> MYSQL_MAPPING =
			Collections.unmodifiableMap(new HashMap<Integer, String>() {{
				put(Types.BOOLEAN, "tinyint");
				put(Types.TINYINT, "tinyint");
				put(Types.SMALLINT, "smallint");
				put(Types.INTEGER, "int");
				put(Types.BIGINT, "bigint");
				put(Types.FLOAT, "float");
				put(Types.DOUBLE, "double");
				put(Types.DATE, "datetime");
				put(Types.TIMESTAMP, "timestamp");
				put(Types.VARCHAR, "varchar(4002)");
				put(Types.BINARY, "varbinary(8002)");
			}});
	@SuppressWarnings("serial")
	private static final Map<Integer, String> POSTGRESQL_MAPPING =
			Collections.unmodifiableMap(new HashMap<Integer, String>() {{
				put(Types.BOOLEAN, "boolean");
				put(Types.TINYINT, "smallint");
				put(Types.SMALLINT, "smallint");
				put(Types.INTEGER, "integer");
				put(Types.BIGINT, "bigint");
				put(Types.FLOAT, "real");
				put(Types.DOUBLE, "double precision");
				put(Types.DATE, "timestamp");
				put(Types.TIMESTAMP, "timestamp");
				put(Types.VARCHAR, "text");
				put(Types.BINARY, "bytea");
			}});
	private static Map<Integer, String> dataTypes = null;

	private static final ObjectWriter writer = new ObjectMapper()
//			.enable(SerializationFeature.INDENT_OUTPUT)
			.writer();

	private int batchSize;
	private SendMethodIntf sendMethod;
	private final String tableOwner;
	private final String masterTable;
	private String masterTableSelSql;
	private String snapshotLog;
	private String snapshotLogSelSql;
	private String snapshotLogDelSql;
	private final List<OraColumn> allColumns = new ArrayList<>();
	private final HashMap<String, OraColumn> pkColumns = new LinkedHashMap<>();
	private AvroSchema schema;
	private Schema keySchema;
	private Schema valueSchema;

	private final SimpleDateFormat iso8601DateFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
	private final SimpleDateFormat iso8601TimestampFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
	private boolean ready4Ops = false;

	private String sinkInsertSql = null;
	private String sinkUpdateSql = null;
	private String sinkDeleteSql = null;
	private PreparedStatement sinkInsert = null;
	private PreparedStatement sinkUpdate = null;
	private PreparedStatement sinkDelete = null;
	// Only for Kafka connect mode
	private String kafkaConnectTopic = null;

	public OraTable(
			final String tableOwner, final String masterTable, final String snapshotLog,
			final int batchSize) throws SQLException {
		this.batchSize = batchSize;
		this.tableOwner = tableOwner;
		this.masterTable = masterTable;
		this.snapshotLog = snapshotLog;
		final String snapshotFqn = "\"" + this.tableOwner + "\"" + ".\"" + this.snapshotLog + "\"";
		this.snapshotLogDelSql = "delete from " + snapshotFqn + " where ROWID=?";

		try (Connection connection = OraPoolConnectionFactory.getConnection()) {
			PreparedStatement statement = connection.prepareStatement(OraDictSqlTexts.COLUMN_LIST);
			statement.setString(1, this.snapshotLog);
			statement.setString(2, this.tableOwner);
			statement.setString(3, this.masterTable);
			ResultSet rsColumns = statement.executeQuery();
			StringBuilder masterSelect = new StringBuilder(512);
			boolean masterFirstColumn = true;
			masterSelect.append("select ");
			StringBuilder masterWhere = new StringBuilder(128);
			StringBuilder mViewSelect = new StringBuilder(128);
			boolean mViewFirstColumn = true;
			mViewSelect.append("select ");
			final String tableNameWithOwner = this.tableOwner + "." + this.masterTable;
			// Schema
			AvroSchema schemaBefore = null;
			AvroSchema schemaAfter = null;
			SchemaBuilder keySchemaBuilder = null;
			SchemaBuilder valueSchemaBuilder = null;
			if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
				schemaBefore = AvroSchema.STRUCT_OPTIONAL();
				schemaBefore.setName(tableNameWithOwner + ".PK");
				schemaBefore.setField("before");
				schemaBefore.initFields();
				schemaAfter = AvroSchema.STRUCT_OPTIONAL();
				schemaAfter.setName(tableNameWithOwner + ".Data");
				schemaAfter.setField("after");
				schemaAfter.initFields();
			} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
				keySchemaBuilder = SchemaBuilder
						.struct()
						.required()
						.name(tableNameWithOwner + ".key")
						.version(1);
				valueSchemaBuilder = SchemaBuilder
						.struct()
						.required()
						.name(tableNameWithOwner + ".value")
						.version(1);
			}

			while (rsColumns .next()) {
				OraColumn column = null;
				if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
					column = new OraColumn(rsColumns);
					schemaAfter.getFields().add(column.getAvroSchema());
				} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
					column = new OraColumn(rsColumns, keySchemaBuilder, valueSchemaBuilder);
				}
				allColumns.add(column);

				if (masterFirstColumn) {
					masterFirstColumn = false;
				} else {
					masterSelect.append(", ");
				}
				masterSelect.append("\"");
				masterSelect.append(column.getColumnName());
				masterSelect.append("\"");

				if (column.isPartOfPk()) {
					pkColumns.put(column.getColumnName(), column);
					if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
						// Only for SCHEMA_TYPE_STANDALONE!!!
						schemaBefore.getFields().add(column.getAvroSchema());
					} 
					if (mViewFirstColumn) {
						mViewFirstColumn = false;
					} else {
						mViewSelect.append(", ");
						masterWhere.append(" and ");
					}
					mViewSelect.append("\"");
					mViewSelect.append(column.getColumnName());
					mViewSelect.append("\"");
					masterWhere.append("\"");
					masterWhere.append(column.getColumnName());
					masterWhere.append("\"=?");
				}
			}
			rsColumns.close();
			rsColumns = null;
			statement.close();
			statement = null;
			// Schema
			if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
				final AvroSchema op = AvroSchema.STRING_MANDATORY();
				op.setField("op");
				final AvroSchema ts_ms = AvroSchema.INT64_MANDATORY();
				ts_ms.setField("ts_ms");
				schema = AvroSchema.STRUCT_MANDATORY();
				schema.setName(tableNameWithOwner + ".Envelope");
				schema.initFields();
				schema.getFields().add(schemaBefore);
				schema.getFields().add(schemaAfter);
				schema.getFields().add(Source.schema());
				schema.getFields().add(op);
				schema.getFields().add(ts_ms);
			} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
				keySchema = keySchemaBuilder.build(); 
				valueSchema = valueSchemaBuilder.build(); 
			}

			masterSelect.append(", ORA_ROWSCN, SYSTIMESTAMP at time zone 'GMT' as TIMESTAMP$$ from ");
			masterSelect.append("\"");
			masterSelect.append(this.tableOwner);
			masterSelect.append("\".\"");
			masterSelect.append(this.masterTable);
			masterSelect.append("\" where ");
			masterSelect.append(masterWhere);
		
			this.masterTableSelSql = masterSelect.toString();

			mViewSelect.append(", SEQUENCE$$, case DMLTYPE$$ when 'I' then 'c' when 'U' then 'u' else 'd' end as OPTYPE$$, ORA_ROWSCN, SYSTIMESTAMP at time zone 'GMT' as TIMESTAMP$$, ROWID from ");
			mViewSelect.append(snapshotFqn);
			mViewSelect.append(" order by SEQUENCE$$");
			this.snapshotLogSelSql = mViewSelect.toString();
		} catch (SQLException sqle) {
			LOGGER.error("Unable to get table information.");
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
		}
	}

	/**
	 * 
	 * This constructor is used only for standalone Kafka/Kinesis producer mode
	 * 
	 * @param tableOwner
	 * @param masterTable
	 * @param snapshotLog
	 * @param batchSize
	 * @param sendMethod
	 * @throws SQLException
	 */
	public OraTable(
			final String tableOwner, final String masterTable, final String snapshotLog,
			final int batchSize, final SendMethodIntf sendMethod) throws SQLException {
		this(tableOwner, masterTable, snapshotLog, batchSize);
		this.sendMethod = sendMethod;
	}

	public OraTable(final Source source, final AvroSchema tableSchema, boolean autoCreateTable) {
		this.tableOwner = source.getOwner();
		this.masterTable = source.getTable();
		int pkColCount = 0;
		for (AvroSchema columnSchema : tableSchema.getFields().get(0).getFields()) {
			final OraColumn column = new OraColumn(columnSchema, true);
			pkColumns.put(column.getColumnName(), column);
			pkColCount++;
		}
		// Only non PK columns!!!
		for (AvroSchema columnSchema : tableSchema.getFields().get(1).getFields()) {
			if (!pkColumns.containsKey(columnSchema.getField())) {
				final OraColumn column = new OraColumn(columnSchema, false);
				allColumns.add(column);
			}
		}
		// Prepare UPDATE/INSERT/DELETE statements...
		final StringBuilder sbDelUpdWhere = new StringBuilder(128);
		sbDelUpdWhere.append(" where ");

		final StringBuilder sbInsSql = new StringBuilder(256);
		sbInsSql.append("insert into ");
		sbInsSql.append(this.masterTable);
		sbInsSql.append("(");
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int pkColumnNo = 0;
		while (iterator.hasNext()) {
			final String columnName = iterator.next().getValue().getColumnName();

			if (pkColumnNo > 0) {
				sbDelUpdWhere.append(" and ");
			}
			sbDelUpdWhere.append(columnName);
			sbDelUpdWhere.append("=?");

			sbInsSql.append(columnName);
			if (pkColumnNo < pkColCount - 1) {
				sbInsSql.append(",");
			}
			pkColumnNo++;
		}

		final StringBuilder sbUpdSql = new StringBuilder(256);
		sbUpdSql.append("update ");
		sbUpdSql.append(this.masterTable);
		sbUpdSql.append(" set ");
		final int nonPkColumnCount = allColumns.size();
		for (int i = 0; i < nonPkColumnCount; i++) {
			sbInsSql.append(",");
			sbInsSql.append(allColumns.get(i).getColumnName());

			sbUpdSql.append(allColumns.get(i).getColumnName());
			if (i < nonPkColumnCount - 1) {
				sbUpdSql.append("=?,");
			} else {
				sbUpdSql.append("=?");
			}
		}
		sbInsSql.append(") values(");
		final int totalColumns = nonPkColumnCount + pkColCount;
		for (int i = 0; i < totalColumns; i++) {
			if (i < totalColumns - 1) {
				sbInsSql.append("?,");
			} else {
				sbInsSql.append("?)");
			}
		}

		final StringBuilder sbDelSql = new StringBuilder(128);
		sbDelSql.append("delete from ");
		sbDelSql.append(this.masterTable);
		sbDelSql.append(sbDelUpdWhere);

		sbUpdSql.append(sbDelUpdWhere);

		// Check for table existence
		try (Connection connection = HikariPoolConnectionFactory.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			String tableName = masterTable;
			if (HikariPoolConnectionFactory.getDbType() == HikariPoolConnectionFactory.DB_TYPE_POSTGRESQL) {
				// PostgreSQL specific...
				// Also look at https://stackoverflow.com/questions/43111996/why-postgresql-does-not-like-uppercase-table-names
				tableName = tableName.toLowerCase();
			}
			ResultSet resultSet = metaData.getTables(null, null, tableName, null);
			if (resultSet.next()) {
				ready4Ops = true;
			}
			resultSet.close();
			resultSet = null;
		} catch (SQLException sqle) {
			ready4Ops = false;
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
		}
		if (!ready4Ops && autoCreateTable) {
			// Create table in target database
			String createTableSqlText = createTableSql();
			try (Connection connection = HikariPoolConnectionFactory.getConnection()) {
				Statement statement = connection.createStatement();
				statement.executeUpdate(createTableSqlText);
				connection.commit();
				ready4Ops = true;
			} catch (SQLException sqle) {
				ready4Ops = false;
				LOGGER.error("Create table failed! Failed creation statement:");
				LOGGER.error(createTableSqlText);
				LOGGER.error(ExceptionUtils.getExceptionStackTrace(sqle));
			}
		}

		sinkInsertSql = sbInsSql.toString(); 
		sinkUpdateSql = sbUpdSql.toString(); 
		sinkDeleteSql = sbDelSql.toString(); 
	}

	private String createTableSql() {
		final StringBuilder sbCreateTable = new StringBuilder(256);
		final StringBuilder sbPrimaryKey = new StringBuilder(64);

		if (dataTypes == null) {
			if (HikariPoolConnectionFactory.getDbType() == HikariPoolConnectionFactory.DB_TYPE_POSTGRESQL) {
				dataTypes = POSTGRESQL_MAPPING;
			} else {
				//TODO - more types required
				dataTypes = MYSQL_MAPPING;
			}
		}

		sbCreateTable.append("create table ");
		sbCreateTable.append(this.masterTable);
		sbCreateTable.append("(\n");

		sbPrimaryKey.append(",\nconstraint ");
		sbPrimaryKey.append(this.masterTable);
		sbPrimaryKey.append("_PK primary key(");
		
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			OraColumn column = iterator.next().getValue();
			sbCreateTable.append("  ");
			sbCreateTable.append(column.getColumnName());
			sbCreateTable.append(" ");
			sbCreateTable.append(dataTypes.get(column.getJdbcType()));
			sbCreateTable.append(" not null");

			sbPrimaryKey.append(column.getColumnName());

			if (iterator.hasNext()) {
				sbCreateTable.append(",\n");
				sbPrimaryKey.append(",");
			}
		}
		sbPrimaryKey.append(")");

		final int nonPkColumnCount = allColumns.size();
		for (int i = 0; i < nonPkColumnCount; i++) {
			OraColumn column = allColumns.get(i);
			sbCreateTable.append(",\n  ");
			sbCreateTable.append(column.getColumnName());
			sbCreateTable.append(" ");
			sbCreateTable.append(dataTypes.get(column.getJdbcType()));
			if (!column.isNullable()) {
				sbCreateTable.append(" not null");
			}
		}

		sbCreateTable.append(sbPrimaryKey);
		sbCreateTable.append("\n)");
		return sbCreateTable.toString();
	}

	/**
	 * Standalone mode run
	 * 
	 */
	public void run() {
		// Poll data (standalone mode)
		try (Connection connection = OraPoolConnectionFactory.getConnection()) {
			poll(connection);
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error(ExceptionUtils.getExceptionStackTrace(e));
		}
	}

	public List<SourceRecord> poll(final Connection connection) throws SQLException {
		PreparedStatement stmtLog = connection.prepareStatement(snapshotLogSelSql);
		PreparedStatement stmtMaster = connection.prepareStatement(masterTableSelSql);
		PreparedStatement stmtDeleteLog = connection.prepareStatement(snapshotLogDelSql);

		final List<RowId> logRows2Delete = new ArrayList<>();
		final List<SourceRecord> result = new ArrayList<>();
		//TODO - SEQUENCE$$ for start!!!
		// Read materialized view log and get PK values
		ResultSet rsLog = stmtLog.executeQuery();
		int recordCount = 0;
		while (rsLog.next() && recordCount < batchSize) {
			recordCount++;
			final String opType = rsLog.getString("OPTYPE$$");
			final boolean deleteOp = "d".equals(opType);
			Map<String, Object> columnValues = null;
			Envelope envelope = null;
			Struct keyStruct = null;
			Struct valueStruct = null;
			final Source recordSource = new Source(tableOwner, masterTable);
			if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
				// process primary key information from materialized view log
				columnValues = new LinkedHashMap<>();
				processPkColumns(deleteOp, rsLog, columnValues, stmtMaster);
				envelope = new Envelope(
						this.getSchema(),
						new Payload(recordSource, opType));
			} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
				
				keyStruct = new Struct(keySchema);
				valueStruct = new Struct(valueSchema);
				processPkColumns(deleteOp, rsLog, keyStruct, stmtMaster);
			}
			// Add ROWID to list for delete after sending data to queue
			logRows2Delete.add(rsLog.getRowId("ROWID"));

			boolean success = true;

			if (deleteOp) {
				if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
					// For DELETE we have only "before" data
					envelope.getPayload().setBefore(columnValues);
					// For delete we need to get TS & SCN from snapshot log
					envelope.getPayload().getSource().setTs_ms(
						rsLog.getTimestamp("TIMESTAMP$$").getTime());
					envelope.getPayload().getSource().setScn(
						rsLog.getBigDecimal("ORA_ROWSCN").toBigInteger());
				}
			} else {
				// Get data from master table
				ResultSet rsMaster = stmtMaster.executeQuery();
				// We're working with PK
				if (rsMaster.next()) {
					if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
						processAllColumns(rsMaster, columnValues);
						// For INSERT/UPDATE  we have only "after" data 
						envelope.getPayload().setAfter(columnValues);
						// For delete we need to get TS & SCN from snapshot log
						envelope.getPayload().getSource().setTs_ms(
							rsMaster.getTimestamp("TIMESTAMP$$").getTime());
						envelope.getPayload().getSource().setScn(
							rsMaster.getBigDecimal("ORA_ROWSCN").toBigInteger());
					} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
						processAllColumns(rsMaster, valueStruct);
					}
				} else {
					success = false;
					LOGGER.error("Primary key = {} not found in {}.{}", nonExistentPk(rsLog), tableOwner, masterTable);
					LOGGER.error("\twhile executing{}\n\t\t", masterTableSelSql);
				}
				// Close unneeded ResultSet
				rsMaster.close();
				rsMaster = null;
			}
			// Ready to process message
			if (success) {
				if (sendMethod != null) {
					//TODO
					//TODO - batch!!!!
					//TODO
					// We're in standalone mode
					final StringBuilder messageKey = new StringBuilder(64);
					messageKey.append(tableOwner);
					messageKey.append(".");
					messageKey.append(masterTable);
					messageKey.append("-");
					messageKey.append(rsLog.getLong("SEQUENCE$$"));
						sendMethod.sendData(messageKey.toString(), envelope);
				} else {
					if (Source.schemaType() == Source.SCHEMA_TYPE_STANDALONE) {
						try {
							envelope.getPayload().setTs_ms(System.currentTimeMillis());
							final SourceRecord sourceRecord = new SourceRecord(null, null,
								kafkaConnectTopic,
								Schema.STRING_SCHEMA,
								writer.writeValueAsString(envelope));
							result.add(sourceRecord);
						} catch (JsonProcessingException jpe) {
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(jpe));
						}
					} else if (Source.schemaType() == Source.SCHEMA_TYPE_KAFKA_CONNECT_STD) {
						if (deleteOp) {
							final SourceRecord sourceRecord = new SourceRecord(
									null,
									null,
									kafkaConnectTopic,
									keySchema,
									keyStruct,
									null,
									null);
							result.add(sourceRecord);
						} else {
							final SourceRecord sourceRecord = new SourceRecord(
									null,
									null,
									kafkaConnectTopic,
									keySchema,
									keyStruct,
									valueSchema,
									valueStruct);
							result.add(sourceRecord);
						}
					}
				}
			}
		}
		rsLog.close();
		rsLog = null;
		// Perform deletion
		//TODO - success check of send (standalone mode)!!!
		for (RowId rowId : logRows2Delete) {
			stmtDeleteLog.setRowId(1, rowId);
			stmtDeleteLog.executeUpdate();
		}

		stmtLog.close(); stmtLog = null;
		stmtMaster.close(); stmtMaster = null;
		stmtDeleteLog.close(); stmtDeleteLog = null;

		if (sendMethod != null) {
			return null;
		} else {
			return result;
		}
	}


	public AvroSchema getSchema() {
		return schema;
	}

	public String getMasterTable() {
		return masterTable;
	}

	public void setKafkaConnectTopic(String kafkaConnectTopic) {
		this.kafkaConnectTopic = kafkaConnectTopic;
	}

	public void putData(Connection connection, Payload payload) throws SQLException {
		switch (payload.getOp()) {
		case "c":
			processInsert(connection, payload.getAfter());
			break;
		case "u":
			processUpdate(connection, payload.getAfter());
			break;
		case "d":
			processDelete(connection, payload.getBefore());
			break;
		}
	}


	public void closeCursors() throws SQLException {
		if (sinkInsert != null) {
			sinkInsert.close();
			sinkInsert = null;
		}
		if (sinkUpdate != null) {
			sinkUpdate.close();
			sinkUpdate = null;
		}
		if (sinkDelete != null) {
			sinkDelete.close();
			sinkDelete = null;
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder(128);
		if (this.snapshotLog != null) {
			sb.append("\"");
			sb.append(this.tableOwner);
			sb.append("\".\"");
			sb.append(this.snapshotLog);
			sb.append("\" on ");
		}
		sb.append("\"");
		sb.append(this.tableOwner);
		sb.append("\".\"");
		sb.append(this.masterTable);
		sb.append("\"");
		return sb.toString();
	}

	private void processPkColumns(final boolean deleteOp, ResultSet rsLog,
			final Map<String, Object> columnValues, PreparedStatement stmtMaster) throws SQLException {
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int bindNo = 1;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			final String columnName = oraColumn.getColumnName();
			switch (oraColumn.getJdbcType()) {
			case Types.DATE:
				//TODO Timezone support!!!!
				columnValues.put(columnName, rsLog.getDate(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setDate(bindNo, rsLog.getDate(columnName));
				break;
			case Types.TINYINT:
				columnValues.put(columnName, rsLog.getByte(columnName));
				if (!deleteOp)
					stmtMaster.setByte(bindNo, rsLog.getByte(columnName));
				break;
			case Types.SMALLINT:
				columnValues.put(columnName, rsLog.getShort(columnName));
				if (!deleteOp)
					stmtMaster.setShort(bindNo, rsLog.getShort(columnName));
				break;
			case Types.INTEGER:
				columnValues.put(columnName, rsLog.getInt(columnName));
				if (!deleteOp)
					stmtMaster.setInt(bindNo, rsLog.getInt(columnName));
				break;
			case Types.BIGINT:
				columnValues.put(columnName, rsLog.getLong(columnName));
				if (!deleteOp)
					stmtMaster.setLong(bindNo, rsLog.getLong(columnName));
				break;
			case Types.BINARY:
				columnValues.put(columnName, rsLog.getBytes(columnName));
				if (!deleteOp)
					stmtMaster.setBytes(bindNo, rsLog.getBytes(columnName));
				break;
			case Types.CHAR:
			case Types.VARCHAR:
				columnValues.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			case Types.NCHAR:
			case Types.NVARCHAR:
				columnValues.put(columnName, rsLog.getNString(columnName));
				if (!deleteOp)
					stmtMaster.setNString(bindNo, rsLog.getNString(columnName));
				break;
			case Types.TIMESTAMP:
				//TODO Timezone support!!!!
				columnValues.put(columnName, rsLog.getTimestamp(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setTimestamp(bindNo, rsLog.getTimestamp(columnName));
				break;
			default:
				// Types.FLOAT, Types.DOUBLE, Types.BLOB, Types.CLOB 
				// TODO - is it possible?
				columnValues.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			}
			bindNo++;
		}
	}

	private void processPkColumns(final boolean deleteOp, ResultSet rsLog,
			final Struct keyStruct, PreparedStatement stmtMaster) throws SQLException {
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int bindNo = 1;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			final String columnName = oraColumn.getColumnName();
			switch (oraColumn.getJdbcType()) {
			case Types.DATE:
				//TODO Timezone support!!!!
				keyStruct.put(columnName, rsLog.getDate(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setDate(bindNo, rsLog.getDate(columnName));
				break;
			case Types.TINYINT:
				keyStruct.put(columnName, rsLog.getByte(columnName));
				if (!deleteOp)
					stmtMaster.setByte(bindNo, rsLog.getByte(columnName));
				break;
			case Types.SMALLINT:
				keyStruct.put(columnName, rsLog.getShort(columnName));
				if (!deleteOp)
					stmtMaster.setShort(bindNo, rsLog.getShort(columnName));
				break;
			case Types.INTEGER:
				keyStruct.put(columnName, rsLog.getInt(columnName));
				if (!deleteOp)
					stmtMaster.setInt(bindNo, rsLog.getInt(columnName));
				break;
			case Types.BIGINT:
				keyStruct.put(columnName, rsLog.getLong(columnName));
				if (!deleteOp)
					stmtMaster.setLong(bindNo, rsLog.getLong(columnName));
				break;
			case Types.DOUBLE:
				keyStruct.put(columnName, rsLog.getDouble(columnName));
				if (!deleteOp)
					stmtMaster.setDouble(bindNo, rsLog.getDouble(columnName));
				break;
			case Types.DECIMAL:
				BigDecimal bdValue = rsLog.getBigDecimal(columnName).setScale(oraColumn.getDataScale());
				keyStruct.put(columnName, bdValue);
				if (!deleteOp)
					stmtMaster.setBigDecimal(bindNo, bdValue);
				break;
			case Types.BINARY:
				keyStruct.put(columnName, rsLog.getBytes(columnName));
				if (!deleteOp)
					stmtMaster.setBytes(bindNo, rsLog.getBytes(columnName));
				break;
			case Types.CHAR:
			case Types.VARCHAR:
				keyStruct.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			case Types.NCHAR:
			case Types.NVARCHAR:
				keyStruct.put(columnName, rsLog.getNString(columnName));
				if (!deleteOp)
					stmtMaster.setNString(bindNo, rsLog.getNString(columnName));
				break;
			case Types.TIMESTAMP:
				//TODO Timezone support!!!!
				keyStruct.put(columnName, rsLog.getTimestamp(columnName).getTime());
				if (!deleteOp)
					stmtMaster.setTimestamp(bindNo, rsLog.getTimestamp(columnName));
				break;
			default:
				// Types.FLOAT, Types.BLOB, Types.CLOB 
				// TODO - is it possible?
				keyStruct.put(columnName, rsLog.getString(columnName));
				if (!deleteOp)
					stmtMaster.setString(bindNo, rsLog.getString(columnName));
				break;
			}
			bindNo++;
		}
	}

	private void processAllColumns(
			ResultSet rsMaster, final Map<String, Object> columnValues) throws SQLException {
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			final String columnName = oraColumn.getColumnName();
			if (!pkColumns.containsKey(columnName)) {
				// Don't process PK value again
				switch (oraColumn.getJdbcType()) {
				case Types.DATE:
					//TODO Timezone support!!!!
					Date dateColumnValue = rsMaster.getDate(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, dateColumnValue.getTime());
					break;
				case Types.TINYINT:
					final byte byteColumnValue = rsMaster.getByte(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, byteColumnValue);									
					break;
				case Types.SMALLINT:
					final short shortColumnValue = rsMaster.getShort(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, shortColumnValue);
					break;
				case Types.INTEGER:
					final int intColumnValue = rsMaster.getInt(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, intColumnValue);
					break;
				case Types.BIGINT:
					final long longColumnValue = rsMaster.getLong(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, longColumnValue);
					break;
				case Types.BINARY:
					final byte[] binaryColumnValue = rsMaster.getBytes(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, binaryColumnValue);
					break;
				case Types.CHAR:
				case Types.VARCHAR:
					final String charColumnValue = rsMaster.getString(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, charColumnValue);
					break;
				case Types.NCHAR:
				case Types.NVARCHAR:
					final String nCharColumnValue = rsMaster.getNString(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, nCharColumnValue);
					break;
				case Types.TIMESTAMP:
					//TODO Timezone support!!!!
					Timestamp tsColumnValue = rsMaster.getTimestamp(columnName);
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, tsColumnValue.getTime());
					break;
				case Types.FLOAT:
					final float floatColumnValue = rsMaster.getFloat(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, floatColumnValue);
					break;
				case Types.DOUBLE:
					final double doubleColumnValue = rsMaster.getDouble(columnName); 
					if (rsMaster.wasNull())
						columnValues.put(columnName, null);
					else
						columnValues.put(columnName, doubleColumnValue);
					break;
				case Types.BLOB:
					final Blob blobColumnValue = rsMaster.getBlob(columnName);
					if (rsMaster.wasNull() || blobColumnValue.length() < 1) {
						columnValues.put(columnName, null);
					} else {
						try (InputStream is = blobColumnValue.getBinaryStream();
							ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
							final byte[] data = new byte[16384];
							int bytesRead;
							while ((bytesRead = is.read(data, 0, data.length)) != -1) {
								baos.write(data, 0, bytesRead);
							}
							columnValues.put(columnName, baos.toByteArray());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing BLOB column {}.{}({})", 
									 tableOwner, masterTable, columnName);
							LOGGER.error("\twhile executing\n\t\t{}", masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				case Types.CLOB:
					final Clob clobColumnValue = rsMaster.getClob(columnName);
					if (rsMaster.wasNull() || clobColumnValue.length() < 1) {
						columnValues.put(columnName, null);
					} else {
						try (Reader reader = clobColumnValue.getCharacterStream()) {
							final char[] data = new char[8192];
							StringBuilder sbClob = new StringBuilder(8192);
							int charsRead;
							while ((charsRead = reader.read(data, 0, data.length)) != -1) {
								sbClob.append(data, 0, charsRead);
							}
							columnValues.put(columnName, sbClob.toString());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing CLOB column {}.{}({})", 
									 tableOwner, masterTable, columnName);
							LOGGER.error("\twhile executing\n\t\t{}", masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				default:
					break;
				}
			}
		}
	}

	private void processAllColumns(
			ResultSet rsMaster, final Struct valueStruct) throws SQLException {
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			final String columnName = oraColumn.getColumnName();
			if (!pkColumns.containsKey(columnName)) {
				// Don't process PK value again
				switch (oraColumn.getJdbcType()) {
				// We do not have dates in this case
//				case Types.DATE:
//					//TODO Timezone support!!!!
//					Date dateColumnValue = rsMaster.getDate(columnName);
//					if (rsMaster.wasNull())
//						valueStruct.put(columnName, null);
//					else
//						valueStruct.put(columnName, new java.util.Date(dateColumnValue.getTime()));
//					break;
				case Types.TINYINT:
					final byte byteColumnValue = rsMaster.getByte(columnName);
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, byteColumnValue);									
					break;
				case Types.SMALLINT:
					final short shortColumnValue = rsMaster.getShort(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, shortColumnValue);
					break;
				case Types.INTEGER:
					final int intColumnValue = rsMaster.getInt(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, intColumnValue);
					break;
				case Types.BIGINT:
					final long longColumnValue = rsMaster.getLong(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, longColumnValue);
					break;
				case Types.BINARY:
					final byte[] binaryColumnValue = rsMaster.getBytes(columnName);
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, binaryColumnValue);
					break;
				case Types.CHAR:
				case Types.VARCHAR:
					final String charColumnValue = rsMaster.getString(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, charColumnValue);
					break;
				case Types.NCHAR:
				case Types.NVARCHAR:
					final String nCharColumnValue = rsMaster.getNString(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, nCharColumnValue);
					break;
				case Types.TIMESTAMP:
					//TODO Timezone support!!!!
					if (oraColumn.isOracleDate()) {
						Date dateTsColumnValue = rsMaster.getDate(columnName);
						if (rsMaster.wasNull())
							valueStruct.put(columnName, null);
						else
							valueStruct.put(columnName, new Timestamp(dateTsColumnValue.getTime()));
					} else {
						Timestamp tsColumnValue = rsMaster.getTimestamp(columnName);
						if (rsMaster.wasNull())
							valueStruct.put(columnName, null);
						else
							valueStruct.put(columnName, tsColumnValue);
					}
					break;
				case Types.FLOAT:
					final float floatColumnValue = rsMaster.getFloat(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, floatColumnValue);
					break;
				case Types.DOUBLE:
					final double doubleColumnValue = rsMaster.getDouble(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, doubleColumnValue);
					break;
				case Types.DECIMAL:
					final BigDecimal bdColumnValue = rsMaster.getBigDecimal(columnName); 
					if (rsMaster.wasNull())
						valueStruct.put(columnName, null);
					else
						valueStruct.put(columnName, bdColumnValue.setScale(oraColumn.getDataScale()));
					break;
				case Types.BLOB:
					final Blob blobColumnValue = rsMaster.getBlob(columnName);
					if (rsMaster.wasNull() || blobColumnValue.length() < 1) {
						valueStruct.put(columnName, null);
					} else {
						try (InputStream is = blobColumnValue.getBinaryStream();
							ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
							final byte[] data = new byte[16384];
							int bytesRead;
							while ((bytesRead = is.read(data, 0, data.length)) != -1) {
								baos.write(data, 0, bytesRead);
							}
							valueStruct.put(columnName, baos.toByteArray());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing BLOB column {}.{}({})", 
									 tableOwner, masterTable, columnName);
							LOGGER.error("\twhile executing\n\t\t{}", masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				case Types.CLOB:
					final Clob clobColumnValue = rsMaster.getClob(columnName);
					if (rsMaster.wasNull() || clobColumnValue.length() < 1) {
						valueStruct.put(columnName, null);
					} else {
						try (Reader reader = clobColumnValue.getCharacterStream()) {
							final char[] data = new char[8192];
							StringBuilder sbClob = new StringBuilder(8192);
							int charsRead;
							while ((charsRead = reader.read(data, 0, data.length)) != -1) {
								sbClob.append(data, 0, charsRead);
							}
							valueStruct.put(columnName, sbClob.toString());
						} catch (IOException ioe) {
							LOGGER.error("IO Error while processing CLOB column {}.{}({})", 
									 tableOwner, masterTable, columnName);
							LOGGER.error("\twhile executing\n\t\t{}", masterTableSelSql);
							LOGGER.error(ExceptionUtils.getExceptionStackTrace(ioe));
						}
					}
					break;
				default:
					break;
				}
			}
		}
	}

	private String nonExistentPk(ResultSet rsLog) throws SQLException {
		StringBuilder sbPrimaryKey = new StringBuilder(128);
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int i = 0;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			final String columnName = oraColumn.getColumnName();
			if (i > 0)
				sbPrimaryKey.append(" and ");
			sbPrimaryKey.append(columnName);
			sbPrimaryKey.append("=");
			switch (oraColumn.getJdbcType()) {
			case Types.DATE:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						iso8601DateFmt.format(new Date(rsLog.getDate(columnName).getTime())));
				sbPrimaryKey.append("'");
				break;
			case Types.TINYINT:
				sbPrimaryKey.append(rsLog.getByte(columnName));
				break;
			case Types.SMALLINT:
				sbPrimaryKey.append(rsLog.getShort(columnName));
				break;
			case Types.INTEGER:
				sbPrimaryKey.append(rsLog.getInt(columnName));
				break;
			case Types.BIGINT:
				sbPrimaryKey.append(rsLog.getLong(columnName));
				break;
			case Types.DECIMAL:
				sbPrimaryKey.append(rsLog.getBigDecimal(columnName));
				break;
			case Types.BINARY:
				// Encode binary to Base64
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						Base64.getEncoder().encodeToString(rsLog.getBytes(columnName)));
				sbPrimaryKey.append("'");
				break;
			case Types.CHAR:
			case Types.VARCHAR:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getString(columnName));
				sbPrimaryKey.append("'");
				break;
			case Types.NCHAR:
			case Types.NVARCHAR:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getNString(columnName));
				sbPrimaryKey.append("'");
				break;
			case Types.TIMESTAMP:
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(
						iso8601TimestampFmt.format(new Date(rsLog.getTimestamp(columnName).getTime())));
				sbPrimaryKey.append("'");
				break;
			default:
				// Types.FLOAT, Types.DOUBLE, Types.BLOB, Types.CLOB
				// TODO - is it possible?
				sbPrimaryKey.append("'");
				sbPrimaryKey.append(rsLog.getString(columnName));
				sbPrimaryKey.append("'");
				break;
			}
			i++;
		}
		return sbPrimaryKey.toString();
	}

	private void processInsert(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkInsert == null) {
			sinkInsert = connection.prepareStatement(sinkInsertSql);
		}
		int columnNo = 1;
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkInsert, columnNo, data);
			columnNo++;
		}
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			oraColumn.bindWithPrepStmt(sinkInsert, columnNo, data);
			columnNo++;
		}
		sinkInsert.executeUpdate();
	}

	private void processUpdate(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkUpdate == null) {
			sinkUpdate = connection.prepareStatement(sinkUpdateSql);
		}
		int columnNo = 1;
		for (int i = 0; i < allColumns.size(); i++) {
			final OraColumn oraColumn = allColumns.get(i);
			oraColumn.bindWithPrepStmt(sinkUpdate, columnNo, data);
			columnNo++;
		}
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkUpdate, columnNo, data);
			columnNo++;
		}
		final int recordCount = sinkUpdate.executeUpdate();
		if (recordCount == 0) {
			LOGGER.warn("Primary key not found, executing insert");
			processInsert(connection, data);
		}
	}

	private void processDelete(
			final Connection connection, final Map<String, Object> data) throws SQLException {
		if (sinkDelete == null) {
			sinkDelete = connection.prepareStatement(sinkDeleteSql);
		}
		Iterator<Entry<String, OraColumn>> iterator = pkColumns.entrySet().iterator();
		int columnNo = 1;
		while (iterator.hasNext()) {
			final OraColumn oraColumn = iterator.next().getValue();
			oraColumn.bindWithPrepStmt(sinkDelete, columnNo, data);
			columnNo++;
		}
		sinkDelete.executeUpdate();
	}

}