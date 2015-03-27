package org.ensime.server

import java.io._
import java.net.{ InetAddress, ServerSocket, Socket }
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import com.google.common.base.Charsets
import com.google.common.io.Files
import org.ensime.EnsimeApi
import org.ensime.config._
import org.ensime.core.{ EnsimeEvent, Project }
import org.ensime.server.protocol.swank.SwankProtocol
import org.ensime.server.protocol.{ IncomingMessageEvent, OutgoingMessageEvent, Protocol }
import org.ensime.util._
import org.slf4j._
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.Future
import scala.util.Properties
import scala.util.Properties._

object Server {
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()

  val log = LoggerFactory.getLogger(classOf[Server])

  def main(args: Array[String]): Unit = {
    val ensimeFileStr = propOrNone("ensime.config").getOrElse(
      throw new RuntimeException("ensime.config (the location of the .ensime file) must be set"))

    val ensimeFile = new File(ensimeFileStr)
    if (!ensimeFile.exists() || !ensimeFile.isFile)
      throw new RuntimeException(s".ensime file ($ensimeFile) not found")

    val config = try {
      EnsimeConfigProtocol.parse(Files.toString(ensimeFile, Charsets.UTF_8))
    } catch {
      case e: Throwable =>
        log.error(s"There was a problem parsing $ensimeFile", e)
        throw e
    }

    initialiseServer(config)
  }

  /**
   * Initialise a server based on the given ensime config.
   * @param config The ensime config
   * @return A tuple of Server instance and initialisation future.
   * @see Project.start()
   */
  def initialiseServer(config: EnsimeConfig): (Server, Future[Unit]) = {
    val server = new Server(config, "127.0.0.1", 0,
      (serverInst, actorSystem, peerRef, rpcTarget) => { new SwankProtocol(serverInst, actorSystem, peerRef, rpcTarget) }
    )
    val initFuture = server.start()
    (server, initFuture)
  }
}

class Server(
    val config: EnsimeConfig,
    host: String,
    requestedPort: Int,
    connectionCreator: (Server, ActorSystem, ActorRef, EnsimeApi) => Protocol) extends EventServer {

  import org.ensime.server.Server.log

  // the config file parsing will attempt to create directories that are expected
  require(config.cacheDir.isDirectory, "" + config.cacheDir + " is not a valid cache directory")

  val actorSystem = ActorSystem.create()
  // TODO move this to only be started when we want to receive
  val listener = new ServerSocket(requestedPort, 0, InetAddress.getByName(host))
  val actualPort = listener.getLocalPort

  log.info("ENSIME Server on " + host + ":" + actualPort)
  log.info(Environment.info)

  writePort(config.cacheDir, actualPort)

  val asyncHandler = new ServerEventManager

  val project = new Project(config, actorSystem, Some(asyncHandler.receiveEvent))

  /**
   * Start the server
   * @return A Future representing when the server initialisation is complete.
   */
  def start(): Future[Unit] = {
    val initFuture = project.initProject()
    startSocketListener()
    initFuture
  }

  private val hasShutdownFlag = new AtomicBoolean(false)
  def startSocketListener(): Unit = {
    val t = new Thread(new Runnable() {
      def run(): Unit = {
        try {
          while (!hasShutdownFlag.get()) {
            try {
              val socket = listener.accept()
              log.info("Got connection, creating handler...")
              actorSystem.actorOf(Props(classOf[SocketHandler], socket, project, connectionCreator))
            } catch {
              case e: IOException =>
                if (!hasShutdownFlag.get())
                  log.error("ENSIME Server socket listener error: ", e)
            }
          }
        } finally {
          listener.close()
        }
      }
    })
    t.start()
  }

  def shutdown(): Unit = {
    log.info("Shutting down server")
    hasShutdownFlag.set(true)
    listener.close()
    actorSystem.shutdown()
    log.info("Awaiting actor system shutdown")
    actorSystem.awaitTermination()
    log.info("Shutdown complete")
  }

  private def writePort(cacheDir: File, port: Int): Unit = {
    val portfile = new File(cacheDir, "port")
    if (!portfile.exists()) {
      log.info("creating portfile " + portfile)
      portfile.createNewFile()
    } else if (portfile.length > 0)
      // LEGACY: older clients create an empty file
      throw new IOException(
        "An ENSIME server might be open already for this project. " +
          "If you are sure this is not the case, please delete " +
          portfile.getAbsolutePath + " and try again"
      )

    portfile.deleteOnExit()
    val out = new PrintWriter(portfile)
    try out.println(port)
    finally out.close()
  }

  override def subscribeToEvents(handler: EnsimeEvent => Unit): Boolean = {
    asyncHandler.subscribeToEvents(handler)
  }
}

class ServerEventManager {

  // buffer events until the first client connects
  private var asyncEvents = Vector[EnsimeEvent]()
  private var asyncListeners: List[EnsimeEvent => Unit] = Nil

  def subscribeToEvents(handler: EnsimeEvent => Unit): Boolean = synchronized {
    if (asyncListeners.isEmpty) {
      asyncListeners ::= handler
      asyncEvents.foreach { event => handler(event) }
      asyncEvents = Vector.empty
      true
    } else {
      false
    }
  }
  def receiveEvent(event: EnsimeEvent): Unit = synchronized {
    if (asyncListeners.isEmpty) {
      asyncEvents :+= event
    } else {
      asyncListeners foreach { l =>
        l(event)
      }
    }
  }
}

case object SocketClosed

class SocketReader(socket: Socket, protocol: Protocol, handler: ActorRef) extends Thread {
  val log = LoggerFactory.getLogger(this.getClass)
  val in = new BufferedInputStream(socket.getInputStream)
  val reader = new InputStreamReader(in, "UTF-8")

  override def run(): Unit = {
    try {
      while (true) {
        val msg: WireFormat = protocol.readMessage(reader)
        handler ! IncomingMessageEvent(msg)

      }
    } catch {
      case e: IOException =>
        log.error("Error in socket reader: ", e)
        Properties.envOrNone("ensime.explode.on.disconnect") match {
          case Some(_) =>
            log.warn("tick, tick, tick, tick... boom!")
            System.exit(-1)
          case None =>
            handler ! SocketClosed
        }
    }
  }
}

/**
 * Create a socket handler
 * @param socket The incoming socket
 * @param connectionCreator Function to create protocol instance given actorSystem and the peer (this) ref
 */
class SocketHandler(socket: Socket,
    rpcTarget: EnsimeApi,
    connectionCreator: (ActorSystem, ActorRef, EnsimeApi) => Protocol) extends Actor with ActorLogging {
  val protocol = connectionCreator(context.system, self, rpcTarget)

  val reader = new SocketReader(socket, protocol, self)
  val out = new BufferedOutputStream(socket.getOutputStream)

  def write(value: WireFormat): Unit = {
    try {
      protocol.writeMessage(value, out)
    } catch {
      case e: IOException =>
        log.error(e, "Write to client failed")
        context.stop(self)
    }
  }

  override def preStart(): Unit = {
    reader.start()
  }

  override def receive = {
    case IncomingMessageEvent(message) =>
      protocol.handleIncomingMessage(message)
    case OutgoingMessageEvent(message: WireFormat) =>
      write(message)
    case SocketClosed =>
      log.error("Socket closed, stopping self")
      context.stop(self)
  }
}
