package tests

import bill.Bill
import bill.Bill.Server
import bill.DeleteRecursively
import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DiagnosticSeverity
import ch.epfl.scala.bsp4j.InitializeBuildParams
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FunSuite
import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class BillSuite extends FunSuite with BeforeAndAfterEach {
  var workspace: Path = _

  override def beforeEach(): Unit = {
    workspace = Files.createTempDirectory("bill")
  }
  override def afterEach(): Unit = {
    DeleteRecursively(workspace)
  }

  test("install") {
    Bill.handleInstall(workspace)
    val json = workspace.resolve(".bsp").resolve("bill.json")
    assert(Files.isRegularFile(json), json)
  }

  test("bsp") {
    import scala.concurrent.ExecutionContext.Implicits.global
    FileLayout.fromString(
      s"""
         |/src/com/App.scala
         |object App {
         |  val x: Int = ""
         |}
       """.stripMargin,
      workspace
    )
    val client = new TestingClient
    val server = new Server()
    server.client = client
    val params = new InitializeBuildParams(
      "Bill Client",
      "1.0.0",
      Bill.bspVersion,
      workspace.toUri.toString,
      new BuildClientCapabilities(List("scala").asJava)
    )

    val future = for {
      _ <- server.buildInitialize(params).toScala
      _ = server.onBuildInitialized()
      targets <- server.workspaceBuildTargets().toScala
      _ <- server
        .buildTargetCompile(
          new CompileParams(targets.getTargets.asScala.map(_.getId).asJava)
        )
        .toScala
    } yield {
      assert(client.publishDiagnostics.size() == 1)
      val file = client.publishDiagnostics.peek()
      assert(file.getDiagnostics.size() == 1)
      val error = file.getDiagnostics.get(0)
      assert(error.getSeverity == DiagnosticSeverity.ERROR)
      assert(
        error.getMessage ==
          """type mismatch;
            | found   : String("")
            | required: Int""".stripMargin
      )
    }
    Await.result(future, Duration("20s"))
  }

}
