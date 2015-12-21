package tuktu.nosql.processors.sql

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json.JsObject
import tuktu.api.BaseProcessor
import tuktu.api.DataPacket
import tuktu.api.utils
import tuktu.nosql.util.sql._
import tuktu.nosql.util.stringHandler
import anorm.NamedParameter
import java.sql.Date
import org.joda.time.DateTime

/**
 * Inserts/updates data in an SQL database in bulk
 */
class SQLBulkProcessor(resultName: String) extends BaseProcessor(resultName) {
    var url: String = _
    var user: String = _
    var password: String = _
    var driver: String = _
    
    var table: String = _
    var columns: Option[List[String]] =_
    var fieldsGiven: Option[List[String]] = _
    var fields: List[String] = _
    
    var queryTrail: String = _

    var client: client = _

    override def initialize(config: JsObject) {
        // Get url, username and password for the connection; and the SQL driver (new drivers may have to be added to dependencies) and query
        url = (config \ "url").as[String]
        user = (config \ "user").as[String]
        password = (config \ "password").as[String]
        driver = (config \ "driver").as[String]
        
        table = (config \ "table").as[String]
        columns = (config \ "columns").asOpt[List[String]]
        fieldsGiven = (config \ "fields").asOpt[List[String]]
        
        queryTrail = (config \ "query_trail").asOpt[String].getOrElse("")
    }

    override def processor(): Enumeratee[DataPacket, DataPacket] = Enumeratee.map((data: DataPacket) => {
        // Get connection params from first packet
        val firstPacket = data.data.head
        fields = fieldsGiven match {
            case None => firstPacket.keys.toList
            case Some(f) => f
        }
        
        // Evaluate all
        val evalUrl = tuktu.api.utils.evaluateTuktuString(url, firstPacket)
        val evalUser = tuktu.api.utils.evaluateTuktuString(user, firstPacket)
        val evalPassword = tuktu.api.utils.evaluateTuktuString(password, firstPacket)
        val evalDriver = tuktu.api.utils.evaluateTuktuString(driver, firstPacket)
        
        // Initialize client
        if (client == null)
            client = new client(evalUrl, evalUser, evalPassword, evalDriver)
        // See if we need to update the client
        else if (evalUrl != client.url || evalUser != client.user || evalPassword != client.password || evalDriver != client.driver) {
            client.close
            client = new client(evalUrl, evalUser, evalPassword, evalDriver)
        }
        
        // Create the stament
        val anormStatement = "INSERT INTO " + table + {
            columns match {
                case None => " "
                case Some(cs) => "(" + cs.mkString(",") + ") "
            }
        } + "VALUES (" + ({
            data.data.zipWithIndex.map{ case(datum, offset) => {
                // Get the anorm statement in there
                fields.map(field => "{" + field + "_" + offset + "}").mkString(",")
            }}
        }).mkString("),(") + ")" + queryTrail
        
        // Make parameters
        val parameters = data.data.zipWithIndex.flatMap{ case(datum, i) => {
            fields.map(field => datum(field) match {
                case d: Int => NamedParameter((field + "_" + i), d)
                case d: Long => NamedParameter((field + "_" + i), d)
                case d: Short => NamedParameter((field + "_" + i), d)
                case d: Double => NamedParameter((field + "_" + i), d)
                case d: Float => NamedParameter((field + "_" + i), d)
                case d: java.util.Date => NamedParameter((field + "_" + i), d)
                case d: java.sql.Timestamp => NamedParameter((field + "_" + i), new java.util.Date(d.getTime))
                case d: java.sql.Date => NamedParameter((field + "_" + i), new java.util.Date(d.getTime))
                case d: String => NamedParameter((field + "_" + i), d)
                case d: Any => NamedParameter((field + "_" + i), d.toString)
            })
        }}
        
        // Finally, we insert the whole
        client.bulkQuery(anormStatement, parameters)
        
        data
    }) compose Enumeratee.onEOF(() => if (client != null) client.close)
}