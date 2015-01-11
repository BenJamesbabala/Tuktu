package tuktu.processors

import java.io._
import java.lang.reflect.Method
import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import au.com.bytecode.opencsv.CSVWriter
import groovy.util.Eval
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import tuktu.api._
import play.api.libs.iteratee.Iteratee
import akka.actor.Actor
import play.api.libs.iteratee.Concurrent
import java.lang.reflect.Method
import play.api.libs.concurrent.Akka
import akka.actor.ActorRef
import play.api.libs.json.Json
import akka.util.Timeout

/**
 * Invokes a new generator
 */
class GeneratorConfigProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(1 seconds)
    override def processor(config: JsValue): Enumeratee[DataPacket, DataPacket] = Enumeratee.map(data => {
        // Get the name of the config file
        val nextName = (config \ "name").as[String]
        // See if we need to populate the config file
        val fieldsToAdd = (config \ "add_fields").asOpt[List[JsObject]]
        
        // See if we need to add the config or not
        fieldsToAdd match {
            case Some(fields) => {
                // Add fields to our config
                val mapToAdd = (for (field <- fields) yield {
                    // Get source and target name
                    val source = (field \ "source").as[String]
                    val target = (field \ "target").as[String]
                    
                    // Add to our new config, we assume only one element, otherwise this makes little sense
                    target -> data.data.head(source).toString
                }).toMap
                
                // Build the map and turn into JSON config
                val newConfig = (config \ "config").as[JsObject] ++ Json.toJson(mapToAdd).asInstanceOf[JsObject]
                
                // Invoke the new generator with custom config
                try {
                    val fut = Akka.system.actorSelection("user/TuktuDispatcher") ? Identify(None)
                    val dispActor = Await.result(fut.mapTo[ActorIdentity], 2 seconds).getRef
                    dispActor ! new controllers.asyncDispatchRequest(nextName, Some(newConfig), false, false)
                } catch {
                    case e: TimeoutException => {} // skip
                    case e: NullPointerException => {}
                }
            }
            case None => {
                // Invoke the new generator, as-is
                try {
                    val fut = Akka.system.actorSelection("user/TuktuDispatcher") ? Identify(None)
                    val dispActor = Await.result(fut.mapTo[ActorIdentity], 2 seconds).getRef
                    dispActor ! new controllers.asyncDispatchRequest(nextName, None, false, false)
                } catch {
                    case e: TimeoutException => {} // skip
                    case e: NullPointerException => {}
                }
            }
        }
        
        // We can still continue with out data
        data
    })
}

/**
 * This class is used to always have an actor present when data is to be streamed in sync
 */
class SyncStreamForwarder() extends Actor with ActorLogging {
    implicit val timeout = Timeout(5 seconds)
    
    var remoteGenerator: ActorRef = null
    var sync: Boolean = false
    
    def receive() = {
        case setup: (ActorRef, Boolean) => {
            remoteGenerator = setup._1
            sync = setup._2
            sender ! "ok"
        }
        case dp: DataPacket => sync match { 
            case false => remoteGenerator ! dp
            case true => {
                sender ! Await.result((remoteGenerator ? dp).mapTo[DataPacket], timeout.duration)
            }
        }
        case sp: StopPacket => remoteGenerator ! StopPacket()
    }
}

/**
 * Invokes a new generator
 */
class GeneratorStreamProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(5 seconds)
    
    val forwarder = Akka.system.actorOf(Props[SyncStreamForwarder])
    var init = false
    var sync: Boolean = false
    
    override def processor(config: JsValue): Enumeratee[DataPacket, DataPacket] =  Enumeratee.map((data: DataPacket) => {
        if (!init) {
            // Get the name of the config file
            val nextName = (config \ "name").as[String]
            // Node to execute on
            val node = (config \ "node").asOpt[String]
            // Get the processors to send data into
            val next = (config \ "next").as[List[String]]
            // Get the actual config, being a list of processors
            val processors = (config \ "processors").as[List[JsObject]]
            
            sync = (config \ "sync").asOpt[Boolean].getOrElse(false)
            
            // Manipulate config and set up the remote actor
            val customConfig = Json.obj(
                "generators" -> List((Json.obj(
                    "name" -> {
                        sync match {
                            case true => "tuktu.generators.SyncStreamGenerator"
                            case false => "tuktu.generators.AsyncStreamGenerator"
                        }
                    },
                    "result" -> "",
                    "config" -> Json.obj(),
                    "next" -> next
                ) ++ {
                    node match {
                        case Some(n) => Json.obj("node" -> n)
                        case None => Json.obj()
                    }
                })),
                "processors" -> processors
            )
            
            // Send a message to our Dispatcher to create the (remote) actor and return us the actorref
            try {
                val fut = Akka.system.actorSelection("user/TuktuDispatcher") ? Identify(None)
                val dispActor = Await.result(fut.mapTo[ActorIdentity], timeout.duration).getRef
                
                // Set up actor and get ref
                val refFut = sync match {
                    case true => dispActor ? new controllers.syncDispatchRequest(nextName, Some(customConfig), false, true)
                    case false => dispActor ? new controllers.asyncDispatchRequest(nextName, Some(customConfig), false, true)
                }
                val remoteGenerator = Await.result(refFut.mapTo[ActorRef], timeout.duration)
                Await.result(forwarder ? (remoteGenerator, sync), timeout.duration)
            } catch {
                case e: TimeoutException => {} // skip
                case e: NullPointerException => {}
            }
            init = true
        }
        
        // Send the result to the generator
        val newData = sync match {
            case true => {
                // Get the result from the generator
                val dataFut = forwarder ? data
                Await.result(dataFut.mapTo[DataPacket], timeout.duration)
            }
            case false => {
                forwarder ! data
                data
            }
        }
        
        // We can still continue with out data
        newData
    }) compose Enumeratee.onEOF(() => {
        forwarder ! new StopPacket()
        forwarder ! PoisonPill
    })
}

/**
 * Actor that deals with parallel processing
 * 
 */
class ParallelProcessorActor(processor: Enumeratee[DataPacket, DataPacket]) extends Actor with ActorLogging {
    implicit val timeout = Timeout(1 seconds)
    val (enumerator, channel) = Concurrent.broadcast[DataPacket]
    val sinkIteratee: Iteratee[DataPacket, Unit] = Iteratee.ignore
    
    val sendBackEnum: Enumeratee[DataPacket, DataPacket] = Enumeratee.map(dp => {
        // Get the actor ref and acutal data
        val actorRef = dp.data.head("ref").asInstanceOf[ActorRef]
        val newData = new DataPacket(dp.data.drop(1))
        actorRef ! newData
        newData
    })
    enumerator |>> (processor compose sendBackEnum) &>> sinkIteratee
    
    def receive() = {
        case data: DataPacket => {
            // We add the ActorRef to the datapacket because we need it later on
            channel.push(new DataPacket(Map("ref" -> sender)::data.data))
        }
    }
}

/**
 * Executes a number of processor-flows in parallel
 */
class ParallelProcessor(resultName: String) extends BaseProcessor(resultName) {
    implicit val timeout = Timeout(1 seconds)
    
    var actors: List[ActorRef] = null
    var merger: Method = null
    var mergerClass: Any = null
    
    /**
     * Taken from dispatcher; recursively pipes Enumeratees
     */
    def buildEnums (
            nextId: List[String],
            processorMap: Map[String, (Enumeratee[DataPacket, DataPacket], List[String])]
    ): List[Enumeratee[DataPacket, DataPacket]] = {
        /**
         * Function that recursively builds the tree of processors
         */
        def buildEnumsHelper(
                next: List[String],
                accum: List[Enumeratee[DataPacket, DataPacket]],
                iterationCount: Integer
        ): List[Enumeratee[DataPacket, DataPacket]] = {
            if (iterationCount > 500) {
                // Awful lot of enumeratees... cycle?
                throw new Exception("Possible cycle detected in config file. Aborted")
            }
            else {
                next match {
                    case List() => {
                        // We are done, return accumulator
                        accum
                    }
                    case id::List() => {
                        // This is just a pipeline, get the processor
                        val proc = processorMap(id)
                        
                        buildEnumsHelper(proc._2, {
                            if (accum.isEmpty) List(proc._1)
                            else accum.map(enum => enum compose proc._1)
                        }, iterationCount + 1)
                    }
                    case nextList => {
                        // We have a branch here and need to expand the list of processors
                        (for (id <- nextList) yield {
                            val proc = processorMap(id)
                            
                            buildEnumsHelper(proc._2, {
                                if (accum.isEmpty) List(proc._1)
                                else accum.map(enum => enum compose proc._1)
                            }, iterationCount + 1)
                        }).flatten
                    }
                }
            }
        }
        
        // Build the enums
        buildEnumsHelper(nextId, List(), 0)
    }
    
    override def processor(config: JsValue): Enumeratee[DataPacket, DataPacket] = Enumeratee.map(data => {
        // Get hte processors
        if (actors == null) {
            // Process config
            val pipelines = (config \ "processors").as[List[JsObject]]
            
            // Set up the merger
            val mergerProcClazz = Class.forName((config \ "merger").as[String])
            mergerClass = mergerProcClazz.getConstructor().newInstance()
            merger = mergerProcClazz.getDeclaredMethods.filter(m => m.getName == "merge").head
            
            // For each pipeline, build the enumeratee
            actors = for (pipeline <- pipelines) yield {
                val start = (pipeline \ "start").as[String]
                val procs = (pipeline \ "pipeline").as[List[JsObject]]
                val processorMap = (for (processor <- procs) yield {
                    // Get all fields
                    val processorId = (processor \ "id").as[String]
                    val processorName = (processor \ "name").as[String]
                    val processorConfig = (processor \ "config").as[JsObject]
                    val resultName = (processor \ "result").as[String]
                    val next = (processor \ "next").as[List[String]]
                    
                    // Instantiate processor
                    val procClazz = Class.forName(processorName)
                    val iClazz = procClazz.getConstructor(classOf[String]).newInstance(resultName)
                    val method = procClazz.getDeclaredMethods.filter(m => m.getName == "processor").head
                    val proc = method.invoke(iClazz, processorConfig).asInstanceOf[Enumeratee[DataPacket, DataPacket]]
                    
                    // Return map
                    processorId -> (proc, next)
                }).toMap
                
                // Build the processor pipeline for this generator
                val processor = buildEnums(List(start), processorMap).head
                // Set up the actor that will execute this processor
                Akka.system.actorOf(Props(classOf[ParallelProcessorActor], processor))
            }
        }
        
        // Send data to actors
        val futs = for (actor <- actors) yield
            actor ? data
        // Get the results
        val results = for (fut <- futs) yield
            Await.result(fut.mapTo[DataPacket], timeout.duration)
            
        // Apply the merger
        merger.invoke(mergerClass, results).asInstanceOf[DataPacket]
    })
    
}