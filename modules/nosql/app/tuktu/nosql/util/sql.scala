package tuktu.nosql.util

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.Savepoint
import java.sql.Statement
import scala.language.reflectiveCalls
import scala.util.control.NonFatal
import com.jolbox.bonecp.BoneCPDataSource
import com.jolbox.bonecp.ConnectionHandle
import com.jolbox.bonecp.PoolUtil
import com.jolbox.bonecp.hooks.AbstractConnectionHook
import anorm.Row
import anorm.SQL
import javax.sql.DataSource
import play.api.Logger
import play.api.Mode
import play.api.Play
import play.api.db.HasInternalConnection
import anorm.NamedParameter
import anorm.SqlParser._

object sql {
    case class connection(
        url: String,
        user: String,
        password: String,
        driver: String)

    val connections = collection.mutable.HashMap[connection, DataSource]()
    
    case class client(url: String, user: String, password: String, driver: String) {

        val datasource = connections.getOrElseUpdate(new connection(url, user, password, driver), {
            createDataSource(new connection(url, user, password, driver))
        })

        def queryResult(query: String) = {
            withConnection { conn =>
                SQL(query).apply()(conn).toList
            }
        }
        
        def query(query: String) = withConnection { conn => 
            SQL(query).execute()(conn) 
        }
        
        def bulkQuery(query: String, parameters: List[NamedParameter]) = withConnection { conn =>
            SQL(query)
                .on(parameters: _ *)
                .executeUpdate()(conn)
        }

        def close() = {
            // we leave this up to the AutoCleanConnection helper
        }

        def withConnection[A](block: Connection => A): A = {
            val conn = new AutoCleanConnection(datasource.getConnection)
            try {
                block(conn)
            } finally {
                conn.close
            }
        }
    }

    /**
     * Turns an SQL row into a Map[String, Any]
     */
    def rowToMap(row: Row) = row.asMap.map(elem => elem._2 match {
        case e: Option[_] => elem._1 -> e.getOrElse("NULL")
        case e: Any       => elem
    })

    /*
     *  The following has been copied or adapted from https://github.com/playframework/playframework/blob/2.3.x/framework/src/play-jdbc/src/main/scala/play/api/db/DB.scala
     *  
     *  Strangely enough these are not all publicly available.
     * 
     */
    
    
    /**
     * @param d Driver class name    
     */
    private def register(d: String): Driver = {
        try {
            val driver = new play.utils.ProxyDriver(
                Class.forName(d).newInstance.asInstanceOf[Driver])

            DriverManager.registerDriver(driver)
            driver
        } catch {
            case NonFatal(e) => throw e
        }
    }

    def createDataSource(connection: connection): DataSource = {

        val datasource = new BoneCPDataSource

        val driver = register(connection.driver)

        val H2DefaultUrl = "^jdbc:h2:mem:.+".r

        connection.url match {
            case url @ H2DefaultUrl() if !url.contains("DB_CLOSE_DELAY") =>
                if (Play.maybeApplication.exists(_.mode == Mode.Dev)) {
                    datasource.setJdbcUrl(s"$url;DB_CLOSE_DELAY=-1")
                } else datasource.setJdbcUrl(url)
            case s: String => datasource.setJdbcUrl(s)
            case _         => throw new Exception
        }

        datasource.setUsername(connection.user)
        datasource.setPassword(connection.password)
        datasource.setDriverClass(connection.driver)
        // disable JMX, not required and can cause weird errors 
        datasource.setDisableJMX(true)
        
        datasource
    }

    /**
     * A connection that automatically releases statements on close
     * 
     */
    private class AutoCleanConnection(connection: Connection) extends Connection with HasInternalConnection {

        private val statements = scala.collection.mutable.ListBuffer.empty[Statement]

        private def registering[T <: Statement](b: => T) = {
            val statement = b
            statements += statement
            statement
        }

        private def releaseStatements() {
            statements.foreach { statement =>
                statement.close()
            }
            statements.clear()
        }

        override def getInternalConnection(): Connection = connection match {
            case bonecpConn: com.jolbox.bonecp.ConnectionHandle =>
                bonecpConn.getInternalConnection
            case x => x
        }

        def createStatement() = registering(connection.createStatement())
        def createStatement(resultSetType: Int, resultSetConcurrency: Int) = registering(connection.createStatement(resultSetType, resultSetConcurrency))
        def createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = registering(connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability))
        def prepareStatement(sql: String) = registering(connection.prepareStatement(sql))
        def prepareStatement(sql: String, autoGeneratedKeys: Int) = registering(connection.prepareStatement(sql, autoGeneratedKeys))
        def prepareStatement(sql: String, columnIndexes: scala.Array[Int]) = registering(connection.prepareStatement(sql, columnIndexes))
        def prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int) = registering(connection.prepareStatement(sql, resultSetType, resultSetConcurrency))
        def prepareStatement(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = registering(connection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability))
        def prepareStatement(sql: String, columnNames: scala.Array[String]) = registering(connection.prepareStatement(sql, columnNames))
        def prepareCall(sql: String) = registering(connection.prepareCall(sql))
        def prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int) = registering(connection.prepareCall(sql, resultSetType, resultSetConcurrency))
        def prepareCall(sql: String, resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int) = registering(connection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability))

        def close() {
            releaseStatements()
            connection.close()
        }

        def clearWarnings() { connection.clearWarnings() }
        def commit() { connection.commit() }
        def createArrayOf(typeName: String, elements: scala.Array[AnyRef]) = connection.createArrayOf(typeName, elements)
        def createBlob() = connection.createBlob()
        def createClob() = connection.createClob()
        def createNClob() = connection.createNClob()
        def createSQLXML() = connection.createSQLXML()
        def createStruct(typeName: String, attributes: scala.Array[AnyRef]) = connection.createStruct(typeName, attributes)
        def getAutoCommit = connection.getAutoCommit
        def getCatalog = connection.getCatalog
        def getClientInfo = connection.getClientInfo
        def getClientInfo(name: String) = connection.getClientInfo(name)
        def getHoldability = connection.getHoldability
        def getMetaData = connection.getMetaData
        def getTransactionIsolation = connection.getTransactionIsolation
        def getTypeMap = connection.getTypeMap
        def getWarnings = connection.getWarnings
        def isClosed = connection.isClosed
        def isReadOnly = connection.isReadOnly
        def isValid(timeout: Int) = connection.isValid(timeout)
        def nativeSQL(sql: String) = connection.nativeSQL(sql)
        def releaseSavepoint(savepoint: Savepoint) { connection.releaseSavepoint(savepoint) }
        def rollback() { connection.rollback() }
        def rollback(savepoint: Savepoint) { connection.rollback(savepoint) }
        def setAutoCommit(autoCommit: Boolean) { connection.setAutoCommit(autoCommit) }
        def setCatalog(catalog: String) { connection.setCatalog(catalog) }
        def setClientInfo(properties: java.util.Properties) { connection.setClientInfo(properties) }
        def setClientInfo(name: String, value: String) { connection.setClientInfo(name, value) }
        def setHoldability(holdability: Int) { connection.setHoldability(holdability) }
        def setReadOnly(readOnly: Boolean) { connection.setReadOnly(readOnly) }
        def setSavepoint() = connection.setSavepoint()
        def setSavepoint(name: String) = connection.setSavepoint(name)
        def setTransactionIsolation(level: Int) { connection.setTransactionIsolation(level) }
        def setTypeMap(map: java.util.Map[String, Class[_]]) { connection.setTypeMap(map) }
        def isWrapperFor(iface: Class[_]) = connection.isWrapperFor(iface)
        def unwrap[T](iface: Class[T]) = connection.unwrap(iface)

        // JDBC 4.1
        def getSchema = {
            connection.asInstanceOf[{ def getSchema: String }].getSchema
        }

        def setSchema(schema: String) {
            connection.asInstanceOf[{ def setSchema(schema: String): Unit }].setSchema(schema)
        }

        def getNetworkTimeout = {
            connection.asInstanceOf[{ def getNetworkTimeout: Int }].getNetworkTimeout
        }

        def setNetworkTimeout(executor: java.util.concurrent.Executor, milliseconds: Int) {
            connection.asInstanceOf[{ def setNetworkTimeout(executor: java.util.concurrent.Executor, milliseconds: Int): Unit }].setNetworkTimeout(executor, milliseconds)
        }

        def abort(executor: java.util.concurrent.Executor) {
            connection.asInstanceOf[{ def abort(executor: java.util.concurrent.Executor): Unit }].abort(executor)
        }

    }

}