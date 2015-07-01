package org.ensime.core

import akka.event.LoggingReceive
import java.io.{ File, InputStream, InputStreamReader }
import akka.actor._

import com.sun.jdi._
import com.sun.jdi.event._
import com.sun.jdi.request.{ EventRequest, EventRequestManager, StepRequest }

import org.ensime.api._

import org.ensime.config._
import org.ensime.model._
import org.ensime.util._

import scala.collection.mutable.ListBuffer
import scala.collection.{ Iterable, mutable }

import pimpathon.file._

object DebugManager {
  def apply(
    broadcaster: ActorRef
  )(
    implicit
    config: EnsimeConfig
  ): Props = Props(classOf[DebugManager], broadcaster, config)
}
class DebugManager(
    broadcaster: ActorRef,
    config: EnsimeConfig
) extends Actor with ActorLogging {

  def ignoreErr[T](action: => T, orElse: => T): T = {
    try { action } catch { case e: Exception => orElse }
  }

  def locToPos(loc: Location): Option[LineSourcePosition] = {
    try {
      (for (set <- sourceMap.get(loc.sourceName())) yield {
        if (set.size > 1) {
          log.warning("Warning, ambiguous source name: " + loc.sourceName())
        }
        set.headOption.map(f => LineSourcePosition(f, loc.lineNumber))
      }).getOrElse(None)
    } catch {
      case e: AbsentInformationException => None
    }
  }

  // These vals should be vars to make it more obvious that we are managing state
  // Map unqualified file names to sets of fully qualified paths.
  private val sourceMap = mutable.HashMap[String, mutable.HashSet[File]]()
  private val activeBreakpoints = mutable.HashSet[Breakpoint]()
  private val pendingBreaksBySourceName =
    new mutable.HashMap[String, mutable.HashSet[Breakpoint]].withDefault { _ => new mutable.HashSet }
  private var maybeVM: Option[VM] = None

  def rebuildSourceMap(): Unit = {
    sourceMap.clear()
    for (f <- config.sourceFiles) {
      val set = sourceMap.getOrElse(f.getName, mutable.HashSet())
      set.add(f.canon)
      sourceMap(f.getName) = set
    }
  }
  rebuildSourceMap()

  def tryPendingBreaksForSourcename(sourceName: String): Unit = {
    for (breaks <- pendingBreaksBySourceName.get(sourceName)) {
      val toTry = mutable.HashSet() ++ breaks
      for (bp <- toTry) {
        setBreakpoint(bp.file, bp.line)
      }
    }
  }

  def setBreakpoint(file: File, line: Int): Boolean = {
    if ((for (vm <- maybeVM) yield {
      vm.setBreakpoint(file, line)
    }).getOrElse { false }) {
      activeBreakpoints.add(Breakpoint(file, line))
      true
    } else {
      addPendingBreakpoint(Breakpoint(file, line))
      false
    }
  }

  def clearBreakpoint(file: File, line: Int): Unit = {
    val clearBp = Breakpoint(file, line)
    for (bps <- pendingBreaksBySourceName.get(file.getName)) {
      bps.retain { _ != clearBp }
    }
    val toRemove = activeBreakpoints.filter { _ == clearBp }
    for (vm <- maybeVM) {
      vm.clearBreakpoints(toRemove)
    }
    activeBreakpoints --= toRemove
  }

  def clearAllBreakpoints(): Unit = {
    pendingBreaksBySourceName.clear()
    activeBreakpoints.clear()
    for (vm <- maybeVM) {
      vm.clearAllBreakpoints()
    }
  }

  def moveActiveBreaksToPending(): Unit = {
    for (bp <- activeBreakpoints) {
      addPendingBreakpoint(bp)
    }
    activeBreakpoints.clear()
  }

  def addPendingBreakpoint(bp: Breakpoint): Unit = {
    val file = bp.file
    val breaks = pendingBreaksBySourceName.getOrElse(file.getName, mutable.HashSet())
    breaks.add(bp)
    pendingBreaksBySourceName(file.getName) = breaks
  }

  def pendingBreakpoints: List[Breakpoint] = {
    pendingBreaksBySourceName.values.flatten.toList
  }

  def disconnectDebugVM(): Unit = {
    withVM { vm =>
      vm.dispose()
    }
    moveActiveBreaksToPending()
    maybeVM = None
    broadcaster ! DebugVMDisconnectEvent
  }

  def vmOptions(): List[String] = List(
    "-classpath",
    config.runtimeClasspath.mkString("\"", File.pathSeparator, "\"")
  ) ++ config.debugVMArgs

  def withVM[T](action: (VM => T)): Option[T] = {
    maybeVM.synchronized {
      try {
        for (vm <- maybeVM) yield {
          action(vm)
        }
      } catch {
        case e: VMDisconnectedException =>
          log.error(e, "Attempted interaction with disconnected VM:")
          disconnectDebugVM()
          None
        case e: Throwable =>
          log.error(e, "Exception thrown whilst handling vm action")
          None
      }
    }
  }

  private def handleRPCWithVM()(action: (VM => Unit)) = {
    withVM { vm =>
      action(vm)
    }.getOrElse {
      log.warning("Could not access debug VM.")
      sender ! FalseResponse
    }
  }

  private def handleRPCWithVMAndThread(threadId: DebugThreadId)(action: ((VM, ThreadReference) => Unit)) = {
    withVM { vm =>
      (for (thread <- vm.threadById(threadId)) yield {
        action(vm, thread)
      }).getOrElse {
        log.warning("Could not find thread: " + threadId)
        sender ! FalseResponse
      }
    }.getOrElse {
      log.warning("Could not access debug VM")
      sender ! FalseResponse
    }
  }

  def bgMessage(msg: String): Unit = {
    broadcaster ! SendBackgroundMessageEvent(msg)
  }

  // the JVM should have its own actor
  def receive: Receive = LoggingReceive { fromJvm orElse fromUser }

  def fromJvm: Receive = {
    case DebuggerShutdownEvent =>
      withVM { vm =>
        vm.dispose()
      }
      context.stop(self)

    case e: VMDisconnectedException => disconnectDebugVM()

    case e: VMStartEvent =>
      withVM { vm =>
        vm.initLocationMap()
        // by default we will attempt to start in suspended mode so we can attach breakpoints etc
        vm.resume()
      }
      broadcaster ! DebugVMStartEvent
    case e: VMDeathEvent => disconnectDebugVM()
    case e: VMDisconnectEvent => disconnectDebugVM()
    case e: StepEvent =>
      (for (pos <- locToPos(e.location())) yield {
        broadcaster ! DebugStepEvent(DebugThreadId(e.thread().uniqueID()), e.thread().name, pos.file, pos.line)
      }) getOrElse {
        log.warning("Step position not found: " + e.location().sourceName() + " : " + e.location().lineNumber())
      }
    case e: BreakpointEvent =>
      (for (pos <- locToPos(e.location())) yield {
        broadcaster ! DebugBreakEvent(DebugThreadId(e.thread().uniqueID()), e.thread().name, pos.file, pos.line)
      }) getOrElse {
        log.warning("Break position not found: " + e.location().sourceName() + " : " + e.location().lineNumber())
      }
    case e: ExceptionEvent =>
      withVM { vm => vm.remember(e.exception) }

      val pos = if (e.catchLocation() != null) locToPos(e.catchLocation()) else None
      broadcaster ! DebugExceptionEvent(
        e.exception.uniqueID(),
        DebugThreadId(e.thread().uniqueID()),
        e.thread().name,
        pos.map(_.file),
        pos.map(_.line)
      )
    case e: ThreadDeathEvent =>
      broadcaster ! DebugThreadDeathEvent(DebugThreadId(e.thread().uniqueID()))
    case e: ThreadStartEvent =>
      broadcaster ! DebugThreadStartEvent(DebugThreadId(e.thread().uniqueID()))
    case e: AccessWatchpointEvent =>
    case e: ClassPrepareEvent =>
      withVM { vm =>
        log.info("ClassPrepareEvent: " + e.referenceType().name())
      }
    case e: ClassUnloadEvent =>
    case e: MethodEntryEvent =>
    case e: MethodExitEvent =>
  }

  def handleStartupFailure(e: Exception): Unit = {
    maybeVM = None
    log.error(e, "Failure during VM startup")
    val message = e.toString
    sender ! DebugVmError(1, message)
  }

  def fromUser: Receive = {
    case DebugStartReq(commandLine: String) ⇒
      withVM { vm ⇒
        vm.dispose()
      }
      try {
        val vm = new VM(VmStart(commandLine))
        maybeVM = Some(vm)
        vm.start()
        sender ! DebugVmSuccess()
      } catch {
        case e: Exception =>
          log.error(e, "Could not start VM")
          handleStartupFailure(e)
      }
    case DebugAttachReq(hostname, port) ⇒
      withVM { vm ⇒
        vm.dispose()
      }
      try {
        val vm = new VM(VmAttach(hostname, port))
        maybeVM = Some(vm)
        vm.start()
        sender ! DebugVmSuccess()
      } catch {
        case e: Exception =>
          log.error(e, "Could not attach VM")
          handleStartupFailure(e)
      }
    case DebugActiveVmReq =>
      handleRPCWithVM() { vm =>
        sender ! TrueResponse
      }

    case DebugStopReq =>
      handleRPCWithVM() { vm =>
        vm.dispose()
        sender ! TrueResponse
      }

    case DebugRunReq =>
      handleRPCWithVM() { vm =>
        vm.resume()
        sender ! TrueResponse
      }
    case DebugContinueReq(threadId) =>
      handleRPCWithVMAndThread(threadId) {
        (vm, thread) =>
          vm.resume()
          sender ! TrueResponse
      }
    case DebugSetBreakReq(file, line: Int) =>
      log.info(s"sender = ${sender()}")

      if (!setBreakpoint(file, line)) {
        bgMessage("Location not loaded. Set pending breakpoint.")
      }
      sender ! VoidResponse
    case DebugClearBreakReq(file, line: Int) =>
      clearBreakpoint(file, line)
      sender ! VoidResponse

    case DebugClearAllBreaksReq =>
      clearAllBreakpoints()
      sender ! VoidResponse

    case DebugListBreakpointsReq =>
      val breaks = BreakpointList(activeBreakpoints.toList, pendingBreakpoints)
      sender ! breaks

    case DebugNextReq(threadId: DebugThreadId) =>
      handleRPCWithVMAndThread(threadId) {
        (vm, thread) =>
          vm.newStepRequest(
            thread,
            StepRequest.STEP_LINE,
            StepRequest.STEP_OVER
          )
          sender ! TrueResponse
      }

    case DebugStepReq(threadId: DebugThreadId) =>
      handleRPCWithVMAndThread(threadId) {
        (vm, thread) =>
          vm.newStepRequest(
            thread,
            StepRequest.STEP_LINE,
            StepRequest.STEP_INTO
          )
          sender ! TrueResponse
      }

    case DebugStepOutReq(threadId: DebugThreadId) =>
      handleRPCWithVMAndThread(threadId) {
        (vm, thread) =>
          vm.newStepRequest(
            thread,
            StepRequest.STEP_LINE,
            StepRequest.STEP_OUT
          )
          sender ! TrueResponse
      }

    case DebugLocateNameReq(threadId: DebugThreadId, name: String) =>
      handleRPCWithVMAndThread(threadId) {
        (vm, thread) =>
          sender ! vm.locationForName(thread, name)
      }
    case DebugBacktraceReq(threadId: DebugThreadId, index: Int, count: Int) =>
      handleRPCWithVMAndThread(threadId) { (vm, thread) =>
        val bt = vm.backtrace(thread, index, count)
        sender ! bt
      }
    case DebugValueReq(location) =>
      handleRPCWithVM() { (vm) =>
        sender ! vm.debugValueAtLocation(location)
      }
    case DebugToStringReq(threadId, location) =>
      handleRPCWithVM() { (vm) =>
        sender ! vm.debugValueAtLocationToString(threadId, location)
      }

    case DebugSetValueReq(location, newValue) =>
      handleRPCWithVM() { vm =>
        location match {
          case DebugStackSlot(threadId, frame, offset) => vm.threadById(threadId) match {
            case Some(thread) =>
              val status = vm.setStackVar(thread, frame, offset, newValue)
              sender ! status
            case _ =>
          }
          case unknown =>
            log.error("Unsupported location type for debug-set-value.: " + unknown)
            sender ! FalseResponse
        }
      }
  }

  private sealed abstract class VmMode()
  private case class VmAttach(hostname: String, port: String) extends VmMode()
  private case class VmStart(commandLine: String) extends VmMode()

  private class VM(mode: VmMode) {
    import scala.collection.JavaConversions._

    private val vm: VirtualMachine = {
      mode match {
        case VmStart(commandLine) ⇒
          val connector = Bootstrap.virtualMachineManager().defaultConnector()
          val arguments = connector.defaultArguments()

          val opts = arguments.get("options").value
          val allVMOpts = (List(opts) ++ vmOptions).mkString(" ")
          arguments.get("options").setValue(allVMOpts)
          arguments.get("main").setValue(commandLine)
          // set the debugged process into suspend mode so we can catch it and add
          // breakpoints (see vm start  event), otherwise we have a race condition.
          arguments.get("suspend").setValue("true")

          log.info("Using Connector: " + connector.name + " : " + connector.description())
          log.info("Connector class: " + connector.getClass.getName)
          log.info("Debugger VM args: " + allVMOpts)
          log.info("Debugger program args: " + commandLine)
          connector.launch(arguments)
        case VmAttach(hostname, port) ⇒
          log.info("Attach to running vm")

          val vmm = Bootstrap.virtualMachineManager()
          val connector = vmm.attachingConnectors().get(0)

          val env = connector.defaultArguments()
          env.get("port").setValue(port)
          env.get("hostname").setValue(hostname)

          log.info("Using Connector: " + connector.name + " : " + connector.description())
          log.info("Debugger arguments: " + env)
          log.info("Attach to VM")
          val vm = connector.attach(env)
          log.info("VM: " + vm.description + ", " + vm)
          // if the remote VM has been started in suspended state, we need to nudge it
          // if the remote VM has been started in running state, this call seems to be a no-op
          vm.resume()
          vm
      }
    }

    //This flag is useful for debugging but not needed during general use
    // vm.setDebugTraceMode(VirtualMachine.TRACE_EVENTS)
    val evtQ = new VMEventManager(vm.eventQueue())
    val erm: EventRequestManager = vm.eventRequestManager();
    {
      val req = erm.createClassPrepareRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
      req.enable()
    }
    {
      val req = erm.createThreadStartRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_NONE)
      req.enable()
    }
    {
      val req = erm.createThreadDeathRequest()
      req.setSuspendPolicy(EventRequest.SUSPEND_NONE)
      req.enable()
    }
    {
      val req = erm.createExceptionRequest(null, false, true)
      req.setSuspendPolicy(EventRequest.SUSPEND_ALL)
      req.enable()
    }

    private val fileToUnits = mutable.HashMap[String, mutable.HashSet[ReferenceType]]()
    private val process = vm.process()
    private val monitor = mode match {
      case VmAttach(_, _) => Nil
      case VmStart(_) => List(
        new MonitorOutput(process.getErrorStream),
        new MonitorOutput(process.getInputStream)
      )
    }
    private val savedObjects = new mutable.HashMap[DebugObjectId, ObjectReference]()

    def start(): Unit = {
      evtQ.start()
      monitor.map { _.start() }
    }

    def dispose() = try {
      evtQ.finished = true
      vm.dispose()
      monitor.map { _.finished = true }
    } catch {
      case e: VMDisconnectedException =>
    }

    def remember(value: Value): Value = {
      value match {
        case v: ObjectReference => remember(v)
        case _ => value
      }
    }

    def remember(v: ObjectReference): ObjectReference = {
      savedObjects(DebugObjectId(v.uniqueID)) = v
      v
    }

    def resume(): Unit = {
      vm.resume()
    }

    def newStepRequest(thread: ThreadReference, stride: Int, depth: Int): Unit = {
      erm.deleteEventRequests(erm.stepRequests)
      val request = erm.createStepRequest(
        thread,
        stride,
        depth
      )
      request.addCountFilter(1)
      request.enable()
      vm.resume()
    }

    def setBreakpoint(file: File, line: Int): Boolean = {
      val locs = locations(file, line)
      if (locs.nonEmpty) {
        bgMessage("Resolved breakpoint at: " + file + " : " + line)
        bgMessage("Installing breakpoint at locations: " + locs)
        for (loc <- locs) {
          val request = erm.createBreakpointRequest(loc)
          request.setSuspendPolicy(EventRequest.SUSPEND_ALL)
          request.enable()
        }
        true
      } else {
        false
      }
    }

    def clearAllBreakpoints(): Unit = {
      erm.deleteAllBreakpoints()
    }

    def clearBreakpoints(bps: Iterable[Breakpoint]): Unit = {
      for (bp <- bps) {
        for (
          req <- erm.breakpointRequests();
          pos <- locToPos(req.location())
        ) {
          if (pos.file == bp.file && pos.line == bp.line) {
            req.disable()
          }
        }
      }
    }

    def typeAdded(t: ReferenceType): Unit = {
      try {
        val key = t.sourceName
        val types = fileToUnits.getOrElse(key, mutable.HashSet[ReferenceType]())
        types += t
        fileToUnits(key) = types
        tryPendingBreaksForSourcename(key)
      } catch {
        case e: AbsentInformationException =>
          log.info("No location information available for: " + t.name())
      }
    }

    def initLocationMap() = {
      for (t <- vm.allClasses) {
        typeAdded(t)
      }
    }

    def locations(file: File, line: Int): Set[Location] = {

      // Group locations by file and line
      case class LocationClass(loc: Location) {
        override def equals(that: Any): Boolean = that match {
          case that: Location =>
            loc.sourcePath == that.sourcePath &&
              loc.sourceName == that.sourceName &&
              loc.lineNumber == that.lineNumber
          case _ => false
        }
        override def hashCode: Int = loc.lineNumber.hashCode ^ loc.sourceName.hashCode
      }

      val buf = mutable.HashSet[LocationClass]()
      val key = file.getName
      for (types <- fileToUnits.get(key)) {
        for (t <- types) {
          for (m <- t.methods()) {
            try { buf ++= m.locationsOfLine(line).map(LocationClass.apply) } catch {
              case e: AbsentInformationException =>
            }
          }
          try { buf ++= t.locationsOfLine(line).map(LocationClass.apply) } catch {
            case e: AbsentInformationException =>
          }
        }
      }
      buf.map(_.loc).toSet
    }

    def threadById(id: DebugThreadId): Option[ThreadReference] = {
      vm.allThreads().find(t => t.uniqueID == id.id)
    }

    // Helper as Value.toString doesn't give
    // us what we want...
    def valueSummary(value: Value): String = {
      value match {
        case v: BooleanValue => v.value().toString
        case v: ByteValue => v.value().toString
        case v: CharValue => "'" + v.value().toString + "'"
        case v: DoubleValue => v.value().toString
        case v: FloatValue => v.value().toString
        case v: IntegerValue => v.value().toString
        case v: LongValue => v.value().toString
        case v: ShortValue => v.value().toString
        case v: VoidValue => "void"
        case v: StringReference => "\"" + v.value() + "\""
        case v: ArrayReference =>
          val length = v.length()
          if (length > 3)
            "Array[" + v.getValues(0, 3).map(valueSummary).mkString(", ") + ",...]"
          else
            "Array[" + v.getValues.map(valueSummary).mkString(", ") + "]"
        case v: ObjectReference =>
          val tpe = v.referenceType()
          if (tpe.name().matches("^scala\\.runtime\\.[A-Z][a-z]+Ref$")) {
            val elemField = tpe.fieldByName("elem")
            valueSummary(v.getValue(elemField))
          } else "Instance of " + lastNameComponent(v.referenceType().name())
        case _ => "NA"
      }
    }

    private def lastNameComponent(s: String): String = {
      "^.*?\\.([^\\.]+)$".r.findFirstMatchIn(s) match {
        case Some(m) => m.group(1)
        case None => s
      }
    }

    private def makeFields(
      tpeIn: ReferenceType,
      obj: ObjectReference
    ): List[DebugClassField] = {
      tpeIn match {
        case tpeIn: ClassType =>
          var fields = List[DebugClassField]()
          var tpe = tpeIn
          while (tpe != null) {
            var i = -1
            fields = tpe.fields().map { f =>
              i += 1
              val value = obj.getValue(f)
              DebugClassField(
                i, f.name(),
                f.typeName(),
                valueSummary(value)
              )
            }.toList ++ fields
            tpe = tpe.superclass
          }
          fields
        case _ => List.empty
      }
    }

    private def fieldByName(obj: ObjectReference, name: String): Option[Field] = {
      val tpeIn = obj.referenceType
      tpeIn match {
        case tpeIn: ClassType =>
          var result: Option[Field] = None
          var tpe = tpeIn
          while (tpe != null && result.isEmpty) {
            for (f <- tpe.fields()) {
              if (f.name() == name) result = Some(f)
            }
            tpe = tpe.superclass
          }
          result
        case _ => None
      }
    }

    private def makeDebugObj(value: ObjectReference): DebugObjectInstance = {
      DebugObjectInstance(
        valueSummary(value),
        makeFields(value.referenceType(), value),
        value.referenceType().name(),
        DebugObjectId(value.uniqueID())
      )
    }

    private def makeDebugStr(value: StringReference): DebugStringInstance = {
      DebugStringInstance(
        valueSummary(value),
        makeFields(value.referenceType(), value),
        value.referenceType().name(),
        DebugObjectId(value.uniqueID())
      )
    }

    private def makeDebugArr(value: ArrayReference): DebugArrayInstance = {
      DebugArrayInstance(
        value.length,
        value.referenceType().name,
        value.referenceType().asInstanceOf[ArrayType].componentTypeName(),
        DebugObjectId(value.uniqueID)
      )
    }

    private def makeDebugPrim(value: PrimitiveValue): DebugPrimitiveValue = DebugPrimitiveValue(
      valueSummary(value),
      value.`type`().name()
    )

    private def makeDebugNull(): DebugNullValue = DebugNullValue("Null")

    private def makeDebugValue(value: Value): DebugValue = {
      if (value == null) makeDebugNull()
      else {
        value match {
          case v: ArrayReference => makeDebugArr(v)
          case v: StringReference => makeDebugStr(v)
          case v: ObjectReference => makeDebugObj(v)
          case v: PrimitiveValue => makeDebugPrim(v)
        }
      }
    }

    def locationForName(thread: ThreadReference, name: String): Option[DebugLocation] = {
      val stackFrame = thread.frame(0)
      val objRef = stackFrame.thisObject()
      if (name == "this") {
        Some(DebugObjectReference(remember(objRef).uniqueID))
      } else {
        stackSlotForName(thread, name).map({ slot =>
          DebugStackSlot(DebugThreadId(thread.uniqueID), slot._1, slot._2)
        }).orElse(
          fieldByName(objRef, name).flatMap { f =>
            Some(DebugObjectField(DebugObjectId(objRef.uniqueID), f.name))
          }
        )
      }
    }

    private def valueAtLocation(location: DebugLocation): Option[Value] = {
      location match {
        case DebugObjectReference(objId) =>
          valueForId(objId)
        case DebugObjectField(objectId, name) =>
          valueForField(objectId, name)
        case DebugArrayElement(objectId, index) =>
          valueForIndex(objectId, index)
        case DebugStackSlot(threadId, frame, offset) =>
          threadById(threadId) match {
            case Some(thread) =>
              valueForStackVar(thread, frame, offset)
            case None => None
          }
      }
    }

    def debugValueAtLocation(location: DebugLocation): Option[DebugValue] = {
      valueAtLocation(location).map(makeDebugValue)
    }

    private def callMethod(thread: ThreadReference, obj: ObjectReference, name: String, signature: String, args: java.util.List[Value]): Option[Value] = {
      if (!vm.canBeModified) {
        log.info("Sorry, this debug VM is read-only.")
        None
      } else {
        log.info("DebugManager.callMethod(obj = " + obj + " of type " + obj.referenceType + ", name = " +
          name + ", signature = " + signature + ", args = " + args)
        obj.referenceType.methodsByName("toString", "()Ljava/lang/String;").headOption match {
          case Some(m) =>
            log.info("Invoking: " + m)
            Some(obj.invokeMethod(thread, m, args, ObjectReference.INVOKE_SINGLE_THREADED))
          case other =>
            log.error("toString method not found: " + other)
            None
        }
      }
    }

    def debugValueAtLocationToString(threadId: DebugThreadId, location: DebugLocation): Option[String] = {
      valueAtLocation(location) match {
        case Some(arr: ArrayReference) =>
          val quantifier = if (arr.length == 1) "element" else "elements" // TODO: replace with something less naive
          Some("<array of " + arr.length + " " + quantifier + ">")
        case Some(str: StringReference) =>
          Some(str.value)
        case Some(obj: ObjectReference) =>
          threadById(threadId) flatMap { thread =>
            callMethod(thread, obj, "toString", "()Ljava/lang/String;", new java.util.Vector()) match {
              case Some(v: StringReference) =>
                Some(v.value)
              case Some(null) => Some("null")
              case _ => None
            }
          }
        case Some(value) => Some(valueSummary(value))
        case _ =>
          log.info("No value found at location.")
          None
      }
    }

    private def valueForId(objectId: DebugObjectId): Option[ObjectReference] = {
      savedObjects.get(objectId)
    }

    private def valueForField(objectId: DebugObjectId, name: String): Option[Value] = {
      for (
        obj <- savedObjects.get(objectId);
        f <- fieldByName(obj, name)
      ) yield {
        remember(obj.getValue(f))
      }
    }

    private def valueForIndex(objectId: DebugObjectId, index: Int): Option[Value] = {
      savedObjects.get(objectId) match {
        case Some(arr: ArrayReference) => Some(remember(arr.getValue(index)))
        case _ => None
      }
    }

    private def valueForStackVar(
      thread: ThreadReference, frame: Int, offset: Int
    ): Option[Value] = {
      if (thread.frameCount > frame &&
        thread.frame(frame).visibleVariables.length > offset) {
        val stackFrame = thread.frame(frame)
        val value = stackFrame.getValue(stackFrame.visibleVariables.get(offset))
        Some(remember(value))
      } else None
    }

    type StackSlot = (Int, Int) // frame, offset
    private def stackSlotForName(thread: ThreadReference, name: String): Option[StackSlot] = {
      var result: Option[(Int, Int)] = None
      var i = 0
      while (result.isEmpty && i < thread.frameCount) {
        val stackFrame = thread.frame(i)
        val visVars = stackFrame.visibleVariables()
        var j = 0
        while (j < visVars.length) {
          if (visVars(j).name == name) {
            result = Some((i, j))
          }
          j += 1
        }
        i += 1
      }
      result
    }

    private def makeStackFrame(index: Int, frame: StackFrame): DebugStackFrame = {
      val locals = ignoreErr({
        frame.visibleVariables.zipWithIndex.map {
          case (v, i) =>
            DebugStackLocal(i, v.name,
              valueSummary(frame.getValue(v)),
              v.typeName())
        }.toList
      }, List.empty)

      val numArgs = ignoreErr(frame.getArgumentValues.length, 0)
      val methodName = ignoreErr(frame.location.method().name(), "Method")
      val className = ignoreErr(frame.location.declaringType().name(), "Class")
      val pcLocation = locToPos(frame.location).getOrElse(
        LineSourcePosition(
          file(frame.location.sourcePath()).canon,
          frame.location.lineNumber
        )
      )
      val thisObjId = ignoreErr(remember(frame.thisObject()).uniqueID, -1L)
      DebugStackFrame(index, locals, numArgs, className, methodName, pcLocation, DebugObjectId(thisObjId))
    }

    def backtrace(thread: ThreadReference, index: Int, count: Int): DebugBacktrace = {
      val frames = ListBuffer[DebugStackFrame]()
      var i = index
      while (i < thread.frameCount && (count == -1 || i < count)) {
        val stackFrame = thread.frame(i)
        frames += makeStackFrame(i, stackFrame)
        i += 1
      }
      DebugBacktrace(frames.toList, DebugThreadId(thread.uniqueID()), thread.name())
    }

    private def mirrorFromString(tpe: Type, toMirror: String): Option[Value] = {
      val s = toMirror.trim
      if (s.length > 0) {
        tpe match {
          case tpe: BooleanType => Some(vm.mirrorOf(s.toBoolean))
          case tpe: ByteType => Some(vm.mirrorOf(s.toByte))
          case tpe: CharType => Some(vm.mirrorOf(s(0)))
          case tpe: DoubleType => Some(vm.mirrorOf(s.toDouble))
          case tpe: FloatType => Some(vm.mirrorOf(s.toFloat))
          case tpe: IntegerType => Some(vm.mirrorOf(s.toInt))
          case tpe: LongType => Some(vm.mirrorOf(s.toLong))
          case tpe: ShortType => Some(vm.mirrorOf(s.toShort))
          case tpe: ReferenceType if tpe.name == "java.lang.String" =>
            if (s.startsWith("\"") && s.endsWith("\"")) {
              Some(vm.mirrorOf(s.substring(1, s.length - 1)))
            } else Some(vm.mirrorOf(s))
          case _ => None
        }
      } else None
    }

    def setStackVar(thread: ThreadReference, frame: Int, offset: Int,
      newValue: String): Boolean = {
      if (thread.frameCount > frame &&
        thread.frame(frame).visibleVariables.length > offset) {
        val stackFrame = thread.frame(frame)
        val localVar: LocalVariable = stackFrame.visibleVariables.get(offset)
        mirrorFromString(localVar.`type`(), newValue) match {
          case Some(v) =>
            stackFrame.setValue(localVar, v); true
          case None => false
        }
      } else false
    }

  }

  private class VMEventManager(val eventQueue: EventQueue) extends Thread {
    @volatile var finished = false
    override def run(): Unit = {
      while (!finished) {
        try {
          val eventSet = eventQueue.remove()
          val it = eventSet.eventIterator()
          while (it.hasNext) {
            val evt = it.nextEvent()
            evt match {
              case e: VMDisconnectEvent =>
                finished = true
              case e: ClassPrepareEvent =>
                withVM { vm =>
                  vm.typeAdded(e.referenceType())
                }
                eventSet.resume()
              case _ =>
            }
            self ! evt
          }
        } catch {
          case t: VMDisconnectedException =>
            self ! t
            finished = true
          case t: Throwable =>
            log.info("Exception during execution", t)
            finished = true
        }
      }
    }
  }

  private class MonitorOutput(val inStream: InputStream) extends Thread {
    val in = new InputStreamReader(inStream)
    @volatile var finished = false
    override def run(): Unit = {
      try {
        var i = 0
        val buf = new Array[Char](512)
        i = in.read(buf, 0, buf.length)
        while (!finished && i >= 0) {
          broadcaster ! DebugOutputEvent(new String(buf, 0, i))
          i = in.read(buf, 0, buf.length)
        }
      } catch {
        case t: Throwable =>
          log.info("Exception during execution", t)
      }
    }
  }
}
