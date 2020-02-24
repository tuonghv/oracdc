# oracdc

**oracdc** is a software package for near real-time data integration and replication in heterogeneous IT environments. **oracdc** consist of two  [Apache Kafka](http://kafka.apache.org/) Source Connector's and JDBC sink connector. **oracdc** provides data integration, [transactional change data capture](https://en.wikipedia.org/wiki/Change_data_capture), and data replication between operational and analytical IT systems. 
Starting from Oracle RDBMS 12c various Oracle tools for [CDC](https://en.wikipedia.org/wiki/Change_data_capture) and/or replication are [depreciated and desupported](https://docs.oracle.com/database/121/UPGRD/deprecated.htm) and replaced by [Oracle Golden Gate](https://www.oracle.com/middleware/technologies/goldengate.html). This project is not intended to be 100% replacement of [expensive](https://www.oracle.com/assets/technology-price-list-070617.pdf) Oracle Golden Gate licenses however may help in many practical cases. Project was tested using [Oracle E-Business Suite](https://www.oracle.com/applications/ebusiness/) customer instance for transferring various information (mostly INV, ONT, WSH, GL & XLA tables) to further reporting and analytics in [PostgreSQL](https://www.postgresql.org/) database. We tested both short transactions (human data entry) and long transactions (various accounting programs) changing millions of rows in dozens of tables (description of some tables used: [WSH_NEW_DELIVERIES](https://docs.oracle.com/cd/E51367_01/scmop_gs/OEDSC/WSH_NEW_DELIVERIES_tbl.htm), [WSH_DELIVERY_ASSIGNMENTS](https://docs.oracle.com/cd/E51367_01/scmop_gs/OEDSC/WSH_DELIVERY_ASSIGNMENTS_tbl.htm), [WSH_DELIVERY_DETAILS](https://docs.oracle.com/cd/E51367_01/scmop_gs/OEDSC/WSH_DELIVERY_DETAILS_tbl.htm)).
**oracdc** Source Connector's compatible with [Oracle RDBMS](https://www.oracle.com/database/index.html) versions 10g, 11g, 12c, 18c, and 19c. If you need support for Oracle Database 9i and please send us an email at [oracle@a2-solutions.eu](mailto:oracle@a2-solutions.eu).

## eu.solutions.a2.cdc.oracle.OraCdcLogMinerConnector
This Source Connector uses [Oracle LogMiner](https://docs.oracle.com/en/database/oracle/oracle-database/12.2/sutil/oracle-logminer-utility.html) as source for data changes. Connector is designed to minimize the side effects of using Oracle LogMiner, even for Oracle RDBMS versions with **DBMS_LOGMNR.CONTINUOUS_MINE** feature support **oracdc** does not use it. Instead, **oracdc** reads **V$LOGMNR_CONTENTS** and saves information with **V$LOGMNR_CONTENTS.OPERATION in ('INSERT', 'DELETE', 'UPDATE')** in Java off-heap memory structures provided by [Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue). This approach minimizes the load on the Oracle database server, but requires additional disk space on the server with **oracdc** installed.
**oracdc**'s _eu.solutions.a2.cdc.oracle.OraCdcLogMinerConnector_ connects to the following configurations of Oracle RDBMS:
1. Standalone instance, or Primary Database of Oracle DataGuard Cluster/Oracle Active DataGuard Cluster, i.e. **V$DATABASE.OPEN_MODE = READ WRITE** 
2. Physical Standby Database of Oracle **Active DataGuard** cluster, i.e. **V$DATABASE.OPEN_MODE = READ ONLY**
3. Physical Standby Database of Oracle **DataGuard** cluster, i.e. **V$DATABASE.OPEN_MODE = MOUNTED**. This option is being tested now (planned for release APR-2020) and if you want to participate in the testing please send us an email at [oracle@a2-solutions.eu](mailto:oracle@a2-solutions.eu).

### Monitoring
_eu.solutions.a2.cdc.oracle.OraCdcLogMinerConnector_ publishes a number of metrics about the connector’s activities that can be monitored through JMX. For complete list of metrics please refer to [LOGMINER-METRICS.md](doc/LOGMINER-METRICS.md)

### Known issues
1. We use V$LOGMNR_CONTENTS.SCN and tuple (V$LOGMNR_CONTENTS.RS_ID, V$LOGMNR_CONTENTS.SSN) as stored Kafka Connect offset. Due to transaction tracking on the Kafka Connect side there may be a situation when last recorded SCN in offset file is behind of SCN's of uncommitted rows. When the connector stops, we track this and print a message in the connector's log file. There are two workarounds at moment: or stop **oracdc** only afyter stopping Oracle RDBMS, or record unprocessed SCN value from connector's log file and set value of `a2.first.change` accordingly to override stored offset. We're working on this issue. 
2. Persistance of [Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) objects between Kafka Connect restarts. We're working on this issue.
3. Only "classical" Oracle data types are supported. We are working on adding support for **BINARY_FLOAT/BINARY_DOUBLE/INTERVAL** data types
4. Initial data load - we're strongly recommend usage of bulk load methods.

### Performance tips
1. If you do not use archivelogs as a source of database user activity audit information, consider setting Oracle RDBMS hidden parameter **_transaction_auditing** to **false** after consulting a [Oracle Support Services](https://www.oracle.com/support/)
2. Always try to set up **supplemental logging** at the table level, and not for all database objects
3. Proper file system parameters and sizing for path where [Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) objects resides.
4. Proper open files hard and soft limits for OS user running **oracdc** 

## eu.solutions.a2.cdc.oracle.OraCdcSourceConnector
This Source Connector uses Oracle RDBMS [materialized view log's](https://docs.oracle.com/en/database/oracle/oracle-database/12.2/sqlrf/CREATE-MATERIALIZED-VIEW-LOG.html) as source for data changes and materializes Oracle RDBMS materialized view log at heterogeneous database system. No materialized view should consume information from materialized view log's which are used by **oracdc**. Unlike _eu.solutions.a2.cdc.oracle.OraCdcLogMinerConnector_ this SourceConnector works with BLOB, and CLOB data types. If you need support for Oracle Database _LONG_, and/or _LONG RAW_ data types please send us an email at [oracle@a2-solutions.eu](mailto:oracle@a2-solutions.eu).


# Getting Started

These instructions will get you a copy of the project up and running on any platform with JDK8+ support.

## Prerequisites

Before using **oracdc** please check that required Java8+ is installed with

```
echo "Checking Java version"
java -version
```

## Installing

Build with

```
mvn install
```

### Oracle JDBC drivers
Unfortunately due the binary license there is no public repository with the Oracle JDBC Driver and Oracle UCP. You can copy drivers from Oracle RDBMS server

```
cp $ORACLE_HOME/jdbc/lib/ojdbc8.jar <JDBC directory> 
cp $ORACLE_HOME/ucp/lib/ucp.jar <JDBC directory> 
```
or download drivers `ojdbc[JAVA-VERSION].jar` and `ucp.jar` from [JDBC and UCP Downloads page](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html)

### Oracle Wallet
If you will use	Oracle Wallet for storing Oracle Database credentials you need three more jar files: `oraclepki.jar`, `osdt_core.jar`, and `osdt_cert.jar`

```
cp $ORACLE_HOME/jlib/oraclepki.jar <JDBC directory>
cp $ORACLE_HOME/jlib/osdt_core.jar <JDBC directory>
cp $ORACLE_HOME/jlib/osdt_cert.jar <JDBC directory>
```

or download them from [JDBC and UCP Downloads page](https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html)
Please refer to [Oracle Database documentation](https://docs.oracle.com/en/database/) for instructions how to create and manage the Oracle Wallet and tnsnames.ora file. Oracle recommends that you create and manage the Wallet in a database environment using `mkstore` command

```
mkstore -wrl $A2_CDC_HOME/wallets/ -create
mkstore -wrl $A2_CDC_HOME/wallets/ -createCredential JDK8 SCOTT
```

# Running 

## Oracle LogMiner as CDC source (eu.solutions.a2.cdc.oracle.OraCdcLogMinerConnector)
The following steps need to be performed in order to prepare the Oracle database so the **oracdc** Connector can be used.
### Enabling Oracle RDBMS ARCHIVELOG mode
Log in to SQL*Plus as SYSDBA and check results of query
 
 ```
 select LOG_MODE from V$DATABASE
 ```
If the query returns **ARCHIVELOG**, it is enabled. Skip ahead to **Enabling supplemental log data**.
If the query returns **NOARCHIVELOG** :

```
shutdown immediate
startup mount
alter database archivelog;
alter database open;
```
To verify that **ARCHIVELOG** has been enabled run again
 
 ```
 select LOG_MODE from V$DATABASE
 ```
This time it should return **ARCHIVELOG**

### Enabling supplemental logging
Log in to SQL*Plus as SYSDBA, if you like to enable supplemental logging for whole database:

```
alter database add supplemental log data (ALL) columns;
```
Alternatively, to enable only for selected tables (recommended):

```
alter table <OWNER>.<TABLE_NAME> alter database add supplemental log data (ALL) columns; 
```
If using Amazon RDS for Oracle please see [AWS Amazon Relational Database Service User Guide](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.Oracle.CommonDBATasks.Log.html#Appendix.Oracle.CommonDBATasks.AddingSupplementalLogging) about **rdsadmin.rdsadmin_util.alter_supplemental_logging** procedure.

To verify **supplemental logging** settings at database level:

```
select SUPPLEMENTAL_LOG_DATA_MIN, SUPPLEMENTAL_LOG_DATA_PK, SUPPLEMENTAL_LOG_DATA_UI, SUPPLEMENTAL_LOG_DATA_FK, SUPPLEMENTAL_LOG_DATA_ALL
from V$DATABASE;
```
To verify **supplemental logging** settings at table level:

```
select LOG_GROUP_NAME, TABLE_NAME, DECODE(ALWAYS, 'ALWAYS', 'Unconditional', NULL, 'Conditional') ALWAYS
from DBA_LOG_GROUPS;
```

### Creating Oracle ROLE with LogMiner privileges
Log in as sysdba and enter the following commands to create a role with the privileges required for running **oracdc** with LogMiner as CDC source:

```
create role REPLICATOR;
grant CREATE SESSION to REPLICATOR;
grant SELECT ANY TRANSACTION to REPLICATOR;
grant SELECT ANY DICTIONARY to REPLICATOR;
grant EXECUTE_CATALOG_ROLE to REPLICATOR;
```
For Oracle RDBMS versions 12c+ additional privilege required:

```
grant LOGMINING to REPLICATOR;
```
Assign **REPLICATOR** role to the Oracle RDBMS user.

## Materialized View logs as CDC source (eu.solutions.a2.cdc.oracle.OraCdcSourceConnector)

Create materialized view log's over replicated tables. These materialized view logs _must_ be created _with_ following options - **with primary key**, _and/or_ **with rowid**, **sequence**, **excluding new values** and _without_ **commit scn** option. You do not need to specify _column list_ while creating materialized view log for using with **oracdc**. **oracdc** reads from materialized view log only primary key value and/or rowid of row in master table.  Table below describes how **oracdc** operates depending on the materialized view log settings

|SNAPSHOT LOG WITH    |Master table access|Key for External system            |
|:--------------------|:------------------|:----------------------------------|
|Primary key          |Primary key        |Primary key                        |
|Primary key and ROWID|ROWID              |Primary key                        |
|ROWID                |ROWID              |ROWID (String, key name ORA_ROW_ID)|


```
connect scott/tiger
CREATE TABLE DEPT
       (DEPTNO NUMBER(2) CONSTRAINT PK_DEPT PRIMARY KEY,
        DNAME VARCHAR2(14) ,
        LOC VARCHAR2(13) );

CREATE TABLE EMP
       (EMPNO NUMBER(4) CONSTRAINT PK_EMP PRIMARY KEY,
        ENAME VARCHAR2(10),
        JOB VARCHAR2(9),
        MGR NUMBER(4),
        HIREDATE DATE,
        SAL NUMBER(7,2),
        COMM NUMBER(7,2),
        DEPTNO NUMBER(2) CONSTRAINT FK_DEPTNO REFERENCES DEPT);

create materialized view log on DEPT
  with primary key, sequence
  excluding new values;

create materialized view log on EMP
  with primary key, sequence
  excluding new values;

```

## oracdc Connector's parameters
For instructions **oracdc** Connector's parameters please read [KAFKA-CONNECT.md](doc/KAFKA-CONNECT.md).

## Message format

Every message is envelope has two parts: a *schema* and *payload*. The schema describes the structure of the payload, while the payload contains the actual data. Message format is similar to format used by [Debezium](https://debezium.io/) with two important differences:
1) *before* part of *schema* and *payload* used only for **DELETE** operation
2) for **DELETE** operation *schema* and *payload* contain only primary key definition and value due to limit of information from materialized view log
3) for **INSERT** and **UPDATE** only *after* part of payload contain values due to absense of row previous state in materialized view log's

### Message examples
See the [MESSAGE-SAMPLES-DBZM-STYLE.md](doc/MESSAGE-SAMPLES-DBZM-STYLE.md) for details

## Oracle RDBMS specific information
*source* structure of payload contains various information about Oracle RDBMS instance from V$INSTANCE and V$DATABASE views.
When using mayrialized view log as CDC source *scn* field for **INSERT** and **UPDATE** operations contains actual *ORA_ROWSCN* pseudocolumn value for given row in master table, for **DELETE** operation -  *ORA_ROWSCN* pseudocolumn value for given row in materialized view log.

### Oracle RDBMS Type mapping

|Oracle RDBMS Type|JSON Type|Comment                                                       |
|:----------------|:--------|:-------------------------------------------------------------|
|DATE             |int32    |org.apache.kafka.connect.data.Date                            |
|TIMESTAMP        |int64    |org.apache.kafka.connect.data.Timestamp                       |
|NUMBER           |int8     |NUMBER(1,0) & NUMBER(2,0)                                     |
|NUMBER           |int16    |NUMBER(3,0) & NUMBER(4,0)                                     |
|NUMBER           |int32    |NUMBER(5,0) & NUMBER(6,0) & NUMBER(7,0) & NUMBER(8,0)         |
|NUMBER           |int64    |Other Integers between billion and 1,000,000,000,000,000,000  |
|NUMBER           |float64  |Oracle NUMBER without specified SCALE and PRECISION           |
|NUMBER           |bytes    |org.apache.kafka.connect.data.Decimal - all other numerics    |
|FLOAT            |float64  |                                                              |
|RAW              |bytes    |                                                              |
|BLOB             |bytes    |                                                              |
|CHAR             |string   |                                                              |
|NCHAR            |string   |                                                              |
|VARCHAR2         |string   |                                                              |
|NVARCHAR2        |string   |                                                              |

## Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## TODO
* [Oracle Log Miner](https://docs.oracle.com/en/database/oracle/oracle-database/19/sutil/oracle-logminer-utility.html) support for Oracle DataGuard
* Oracle LOB handler: convert Oracle BLOB/CLOB/BFILE to link on object file system and send ref to instead of large data

## Version history

#####0.9.0 (OCT-2019)
Initial release

#####0.9.1 (NOV-2019)
Oracle Wallet support for storing database credentials

#####0.9.2 (DEC-2019)
"with ROWID" materialized view log support

#####0.9.3 (FEB-2020)
[Oracle Log Miner](https://docs.oracle.com/en/database/oracle/oracle-database/12.2/sutil/oracle-logminer-utility.html) as CDC source
Removed AWS Kinesis support
New class hierarchy

## Authors

* **Aleksej Veremeev** - *Initial work* - [A2 Rešitve d.o.o.](http://a2-solutions.eu/)

## License

This project is licensed under the Apache License - see the [LICENSE](LICENSE) file for details

