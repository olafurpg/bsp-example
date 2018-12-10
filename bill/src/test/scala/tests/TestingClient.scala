package tests

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import java.util.concurrent.ConcurrentLinkedDeque

class TestingClient extends BuildClient {
  val showMessages = new ConcurrentLinkedDeque[ShowMessageParams]()
  val logMessages = new ConcurrentLinkedDeque[LogMessageParams]()
  val taskStart = new ConcurrentLinkedDeque[TaskStartParams]()
  val taskProgress = new ConcurrentLinkedDeque[TaskProgressParams]()
  val taskFinish = new ConcurrentLinkedDeque[TaskFinishParams]()
  val publishDiagnostics = new ConcurrentLinkedDeque[PublishDiagnosticsParams]()
  val didChangeBuildTarget = new ConcurrentLinkedDeque[DidChangeBuildTarget]()

  override def onBuildShowMessage(params: ShowMessageParams): Unit =
    showMessages.add(params)
  override def onBuildLogMessage(params: LogMessageParams): Unit =
    logMessages.add(params)
  override def onBuildTaskStart(params: TaskStartParams): Unit =
    taskStart.add(params)
  override def onBuildTaskProgress(params: TaskProgressParams): Unit =
    taskProgress.add(params)
  override def onBuildTaskFinish(params: TaskFinishParams): Unit =
    taskFinish.add(params)
  override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit =
    publishDiagnostics.add(params)
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit =
    didChangeBuildTarget.add(params)
}
