package org.ensime.core

import java.io.File

import akka.actor.ActorRef
import akka.pattern.ask
import org.ensime.EnsimeApi
import org.ensime.model._
import org.ensime.server.ConnectionInfo
import org.ensime.server.protocol.ProtocolConst
import org.ensime.util._
import shapeless.Typeable
import shapeless.syntax.typeable._

import scala.concurrent.Await
import scala.concurrent.duration._

trait ProjectEnsimeApiImpl extends EnsimeApi { self: Project =>

  val defaultMaxWait: FiniteDuration = 30.seconds

  def callVoidRPC(target: ActorRef, request: RPCRequest, maxWait: FiniteDuration = defaultMaxWait): Unit = {
    callRPC[VoidResponse.type](target, request, maxWait)
  }

  def callRPC[R](target: ActorRef, request: RPCRequest, maxWait: FiniteDuration = defaultMaxWait)(implicit typ: Typeable[R]): R = {
    val future = target.ask(request)(maxWait)
    val result = Await.result(future, maxWait)
    result match {
      case e: RPCError =>
        throw e
      case r =>
        val castRes = r.cast[R]
        if (castRes.isDefined)
          castRes.get
        else {
          val msg = "Error, incorrect type found in RPC call, expected: " + typ + " got " + r.getClass
          val ex = RPCError(ProtocolConst.ErrExceptionInRPC, msg)
          log.error(msg, ex)
          throw ex
        }
    }
  }

  override def rpcConnectionInfo(): ConnectionInfo = {
    new ConnectionInfo()
  }

  override def rpcShutdownServer(): Unit = {
    shutdownServer()
  }

  override def rpcPeekUndo(): Option[Undo] = {
    peekUndo()
  }

  override def rpcExecUndo(undoId: Int): Either[String, UndoResult] = {
    execUndo(undoId)
  }

  override def rpcReplConfig(): ReplConfig = {
    new ReplConfig(config.runtimeClasspath)
  }

  override def rpcSymbolDesignations(f: String, start: Int, end: Int,
    requestedTypes: Set[SourceSymbol]): SymbolDesignations = {
    val file: File = new File(f)
    callRPC[SymbolDesignations](getAnalyzer, SymbolDesignationsReq(file, start, end, requestedTypes))
  }

  override def rpcDebugStartVM(commandLine: String): DebugVmStatus = {
    callRPC[DebugVmStatus](acquireDebugger, DebugStartVMReq(commandLine))
  }

  override def rpcDebugAttachVM(hostname: String, port: String): DebugVmStatus = {
    callRPC[DebugVmStatus](acquireDebugger, DebugAttachVMReq(hostname, port))
  }

  override def rpcDebugStopVM(): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugStopVMReq)
  }

  override def rpcDebugRun(): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugRunReq)
  }

  override def rpcDebugContinue(threadId: DebugThreadId): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugContinueReq(threadId))
  }

  override def rpcDebugSetBreakpoint(file: String, line: Int): Unit = {
    callVoidRPC(acquireDebugger, DebugSetBreakpointReq(file, line))
  }

  override def rpcDebugClearBreakpoint(file: String, line: Int): Unit = {
    callVoidRPC(acquireDebugger, DebugClearBreakpointReq(file, line))
  }

  override def rpcDebugClearAllBreakpoints(): Unit = {
    callVoidRPC(acquireDebugger, DebugClearAllBreakpointsReq)
  }

  override def rpcDebugListBreakpoints(): BreakpointList = {
    callRPC[BreakpointList](acquireDebugger, DebugListBreakpointsReq)
  }

  override def rpcDebugNext(threadId: DebugThreadId): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugNextReq(threadId))
  }

  override def rpcDebugStep(threadId: DebugThreadId): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugStepReq(threadId))
  }

  override def rpcDebugStepOut(threadId: DebugThreadId): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugStepOutReq(threadId))
  }

  override def rpcDebugLocateName(threadId: DebugThreadId, name: String): Option[DebugLocation] = {
    callRPC[Option[DebugLocation]](acquireDebugger, DebugLocateNameReq(threadId, name))
  }

  override def rpcDebugValue(loc: DebugLocation): Option[DebugValue] = {
    callRPC[Option[DebugValue]](acquireDebugger, DebugValueReq(loc))
  }

  override def rpcDebugToString(threadId: DebugThreadId, loc: DebugLocation): Option[String] = {
    callRPC[Option[String]](acquireDebugger, DebugToStringReq(threadId, loc))
  }

  override def rpcDebugSetValue(loc: DebugLocation, newValue: String): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugSetValueReq(loc, newValue))
  }

  override def rpcDebugBacktrace(threadId: DebugThreadId, index: Int, count: Int): DebugBacktrace = {
    callRPC[DebugBacktrace](acquireDebugger, DebugBacktraceReq(threadId, index, count))
  }

  override def rpcDebugActiveVM(): Boolean = {
    callRPC[Boolean](acquireDebugger, DebugActiveVMReq)
  }

  override def rpcPatchSource(f: String, edits: List[PatchOp]): Unit = {
    val file: File = new File(f)
    callVoidRPC(getAnalyzer, PatchSourceReq(file, edits))
  }

  override def rpcTypecheckFiles(fs: List[SourceFileInfo], async: Boolean): Unit = {
    callVoidRPC(getAnalyzer, ReloadFilesReq(fs, async))
  }

  override def rpcRemoveFile(f: String): Unit = {
    val file: File = new File(f)
    callVoidRPC(getAnalyzer, RemoveFileReq(file))
  }

  override def rpcUnloadAll(): Unit = {
    callVoidRPC(getAnalyzer, UnloadAllReq)
  }

  override def rpcTypecheckAll(): Unit = {
    callVoidRPC(getAnalyzer, ReloadAllReq)
  }

  override def rpcCompletionsAtPoint(fileInfo: SourceFileInfo, point: Int, maxResults: Int,
    caseSens: Boolean): CompletionInfoList = {

    callRPC[CompletionInfoList](getAnalyzer, CompletionsReq(fileInfo, point, maxResults, caseSens))
  }

  override def rpcPackageMemberCompletion(path: String, prefix: String): List[CompletionInfo] = {
    callRPC[List[CompletionInfo]](getAnalyzer, PackageMemberCompletionReq(path, prefix))
  }

  override def rpcInspectTypeAtPoint(f: String, range: OffsetRange): Option[TypeInspectInfo] = {
    callRPC[Option[TypeInspectInfo]](getAnalyzer, InspectTypeReq(new File(f), range))
  }

  override def rpcInspectTypeById(id: Int): Option[TypeInspectInfo] = {
    callRPC[Option[TypeInspectInfo]](getAnalyzer, InspectTypeByIdReq(id))
  }

  override def rpcInspectTypeByName(typeFQN: String): Option[TypeInspectInfo] = {
    callRPC[Option[TypeInspectInfo]](getAnalyzer, InspectTypeByNameReq(typeFQN))
  }

  override def rpcSymbolAtPoint(fileName: String, point: Int): Option[SymbolInfo] = {
    callRPC[Option[SymbolInfo]](getAnalyzer, SymbolAtPointReq(new File(fileName), point))
  }

  override def rpcTypeById(typeId: Int): Option[TypeInfo] = {
    callRPC[Option[TypeInfo]](getAnalyzer, TypeByIdReq(typeId))
  }

  override def rpcTypeByName(name: String): Option[TypeInfo] = {
    callRPC[Option[TypeInfo]](getAnalyzer, TypeByNameReq(name))
  }

  override def rpcTypeByNameAtPoint(name: String, f: String, range: OffsetRange): Option[TypeInfo] = {
    callRPC[Option[TypeInfo]](getAnalyzer, TypeByNameAtPointReq(name, new File(f), range))
  }

  override def rpcCallCompletion(typeId: Int): Option[CallCompletionInfo] = {
    callRPC[Option[CallCompletionInfo]](getAnalyzer, CallCompletionReq(typeId))
  }

  override def rpcImportSuggestions(f: String, point: Int, names: List[String], maxResults: Int): ImportSuggestions = {
    callRPC[ImportSuggestions](indexer, ImportSuggestionsReq(new File(f), point, names, maxResults))
  }

  override def rpcDocSignatureAtPoint(f: String, range: OffsetRange): Option[DocSigPair] = {
    callRPC[Option[DocSigPair]](getAnalyzer, DocSignatureAtPointReq(new File(f), range))
  }

  override def rpcDocSignatureForSymbol(typeFullName: String, memberName: Option[String], signatureString: Option[String]): Option[DocSigPair] = {
    callRPC[Option[DocSigPair]](getAnalyzer, DocSignatureForSymbolReq(typeFullName, memberName, signatureString))
  }

  override def rpcSymbolByName(typeFullName: String, memberName: Option[String], signatureString: Option[String]): Option[SymbolInfo] = {
    callRPC[Option[SymbolInfo]](getAnalyzer, SymbolByNameReq(typeFullName, memberName, signatureString))
  }

  override def rpcDocUriAtPoint(f: String, range: OffsetRange): Option[String] = {
    rpcDocSignatureAtPoint(f, range).flatMap { sig => callRPC[Option[String]](docServer, DocUriReq(sig)) }
  }

  override def rpcDocUriForSymbol(typeFullName: String, memberName: Option[String], signatureString: Option[String]): Option[String] = {
    rpcDocSignatureForSymbol(typeFullName, memberName, signatureString).flatMap { sig => callRPC[Option[String]](docServer, DocUriReq(sig)) }
  }

  override def rpcPublicSymbolSearch(names: List[String], maxResults: Int): SymbolSearchResults = {
    callRPC[SymbolSearchResults](indexer, PublicSymbolSearchReq(names, maxResults))
  }

  override def rpcUsesOfSymAtPoint(f: String, point: Int): List[ERangePosition] = {
    callRPC[List[ERangePosition]](getAnalyzer, UsesOfSymAtPointReq(new File(f), point))
  }

  override def rpcTypeAtPoint(f: String, range: OffsetRange): Option[TypeInfo] = {
    callRPC[Option[TypeInfo]](getAnalyzer, TypeAtPointReq(new File(f), range))
  }

  override def rpcInspectPackageByPath(path: String): Option[PackageInfo] = {
    callRPC[Option[PackageInfo]](getAnalyzer, InspectPackageByPathReq(path))
  }

  override def rpcPrepareRefactor(procId: Int, refactorDesc: RefactorDesc): Either[RefactorFailure, RefactorEffect] = {
    callRPC[Either[RefactorFailure, RefactorEffect]](getAnalyzer, RefactorPrepareReq(procId, refactorDesc))
  }

  override def rpcExecRefactor(procId: Int, refactorType: Symbol): Either[RefactorFailure, RefactorResult] = {
    callRPC[Either[RefactorFailure, RefactorResult]](getAnalyzer, RefactorExecReq(procId, refactorType))
  }

  override def rpcCancelRefactor(procId: Int): Unit = {
    callVoidRPC(getAnalyzer, RefactorCancelReq(procId))
  }

  override def rpcExpandSelection(filename: String, start: Int, stop: Int): FileRange = {
    callRPC[FileRange](getAnalyzer, ExpandSelectionReq(filename, start, stop))
  }

  override def rpcFormatFiles(filenames: List[String]): Unit = {
    callVoidRPC(getAnalyzer, FormatFilesReq(filenames))
  }

  override def rpcFormatFile(fileInfo: SourceFileInfo): String = {
    callRPC[String](getAnalyzer, FormatFileReq(fileInfo))
  }

}
