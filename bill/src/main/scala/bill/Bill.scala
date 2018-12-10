package bill

import ch.epfl.scala.{bsp4j => b}
import ch.epfl.scala.bsp4j._
import com.geirsson.coursiersmall.CoursierSmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.io.PrintWriter
import java.net.URI
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.stream.Collectors
import org.eclipse.lsp4j.jsonrpc.Launcher
import scala.reflect.internal.{util => r}
import scala.reflect.internal.util.BatchSourceFile
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.reflect.io.AbstractFile
import scala.reflect.io.VirtualFile
import scala.tools.nsc
import scala.tools.nsc.reporters.StoreReporter
import scala.util.Properties
import scala.util.control.NonFatal

object Bill {
  class Server extends BuildServer {
    val languages = Collections.singletonList("scala")
    var client: BuildClient = _
    var workspace: Path = Paths.get(".").toAbsolutePath.normalize()
    def src: Path = workspace.resolve("src")
    def scalaJars: util.List[String] =
      myClasspath.map(_.toUri.toString).toList.asJava
    val target: BuildTarget = {
      val scalaTarget = new ScalaBuildTarget(
        "org.scala-lang",
        Properties.versionNumberString,
        "2.12",
        ScalaPlatform.JVM,
        scalaJars
      )
      val id = new BuildTargetIdentifier("id")
      val capabilities = new BuildTargetCapabilities(true, false, false)
      val result = new BuildTarget(
        id,
        Collections.singletonList("tag"),
        languages,
        Collections.emptyList(),
        capabilities
      )
      result.setData(scalaTarget)
      result
    }
    val reporter = new StoreReporter
    val out = workspace.resolve("out")
    lazy val g = {
      val settings = new nsc.Settings()
      settings.classpath.value = myClasspath.map(_.toString).mkString(File.pathSeparator)
      settings.Yrangepos.value = true
      settings.d.value = out.toString
      new nsc.Global(settings, reporter)
    }

    override def buildInitialize(
        params: InitializeBuildParams
    ): CompletableFuture[InitializeBuildResult] = {
      CompletableFuture.completedFuture {
        workspace = Paths.get(URI.create(params.getRootUri))
        val capabilities = new BuildServerCapabilities
        capabilities.setCompileProvider(new CompileProvider(languages))
        new InitializeBuildResult("Bill", "1.0", "2.0.0-M2", capabilities)
      }
    }
    override def onBuildInitialized(): Unit = {}
    override def buildShutdown(): CompletableFuture[AnyRef] = {
      CompletableFuture.completedFuture(null)
    }
    override def onBuildExit(): Unit = {
      System.exit(0)
    }
    override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] = {
      CompletableFuture.completedFuture {
        new WorkspaceBuildTargetsResult(Collections.singletonList(target))
      }
    }
    override def buildTargetSources(
        params: SourcesParams
    ): CompletableFuture[SourcesResult] = {
      Files.createDirectories(src)
      CompletableFuture.completedFuture {
        new SourcesResult(
          List(
            new SourcesItem(
              target.getId,
              List(
                new SourceItem(src.toUri.toString, false)
              ).asJava
            )
          ).asJava
        )
      }
    }
    override def buildTargetInverseSources(
        params: InverseSourcesParams
    ): CompletableFuture[InverseSourcesResult] = ???
    override def buildTargetDependencySources(
        params: DependencySourcesParams
    ): CompletableFuture[DependencySourcesResult] = {
      CompletableFuture.completedFuture {
        val sources = CoursierSmall.fetch(
          new Settings().withDependencies(
            List(new Dependency("org.scala-lang", "scala-library", Properties.versionNumberString))
          )
        )
        new DependencySourcesResult(
          List(
            new DependencySourcesItem(target.getId, sources.map(_.toUri.toString).asJava)
          ).asJava
        )
      }
    }
    override def buildTargetResources(
        params: ResourcesParams
    ): CompletableFuture[ResourcesResult] = ???

    private val hasError = mutable.Set.empty[AbstractFile]
    def publishDiagnostics(): Unit = {
      val byFile = reporter.infos.groupBy(_.pos.source.file)
      val fixedErrors = hasError.filterNot(byFile.contains)
      fixedErrors.foreach { file =>
        client.onBuildPublishDiagnostics(
          new PublishDiagnosticsParams(
            new TextDocumentIdentifier(file.name),
            target.getId,
            List().asJava,
            true
          )
        )
      }
      hasError --= fixedErrors
      byFile.foreach {
        case (file, infos) =>
          def toBspPos(pos: r.Position, offset: Int): b.Position = {
            val line = pos.source.offsetToLine(offset) + 1
            val column0 = pos.source.lineToOffset(line)
            val column = offset - column0
            new b.Position(line, column)
          }
          val diagnostics = infos.iterator
            .filter(_.pos.isRange)
            .map { info =>
              val start = toBspPos(info.pos, info.pos.start)
              val end = toBspPos(info.pos, info.pos.end)
              val diagnostic = new Diagnostic(new b.Range(start, end), info.msg)
              val severity = info.severity match {
                case reporter.ERROR => DiagnosticSeverity.ERROR
                case reporter.WARNING => DiagnosticSeverity.WARNING
                case reporter.INFO => DiagnosticSeverity.INFORMATION
                case _ => DiagnosticSeverity.HINT
              }
              diagnostic.setSeverity(severity)
              diagnostic
            }
            .toList
          val uri = file.name
          client.onBuildPublishDiagnostics(
            new PublishDiagnosticsParams(
              new TextDocumentIdentifier(uri),
              target.getId,
              diagnostics.asJava,
              true
            )
          )
          hasError += file
      }
    }

    override def buildTargetCompile(
        params: CompileParams
    ): CompletableFuture[CompileResult] = {
      CompletableFuture.completedFuture {
        reporter.reset()
        val run = new g.Run()
        val sources: List[BatchSourceFile] =
          if (Files.isDirectory(src)) {
            Files
              .walk(src)
              .collect(Collectors.toList())
              .asScala
              .iterator
              .filter(_.getFileName.toString.endsWith(".scala"))
              .map(path => {
                val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                val chars = text.toCharArray
                new BatchSourceFile(new VirtualFile(path.toUri.toString), chars)
              })
              .toList
          } else {
            Nil
          }
        run.compileSources(sources)
        publishDiagnostics()
        val exit =
          if (reporter.hasErrors) StatusCode.ERROR
          else StatusCode.OK
        new CompileResult(exit)
      }
    }
    override def buildTargetTest(
        params: TestParams
    ): CompletableFuture[TestResult] = ???
    override def buildTargetRun(
        params: RunParams
    ): CompletableFuture[RunResult] = ???
    override def buildTargetCleanCache(
        params: CleanCacheParams
    ): CompletableFuture[CleanCacheResult] = {
      CompletableFuture.completedFuture {
        val count = DeleteRecursively(out)
        val plural = if (count != 1) "s" else ""
        new CleanCacheResult(s"deleted $count file$plural", true)
      }
    }
  }

  val bspVersion = "2.0.0-M2"
  val version = "1.0.0"

  def myClassLoader: URLClassLoader = this.getClass.getClassLoader.asInstanceOf[URLClassLoader]
  def myClasspath: Iterator[Path] =
    myClassLoader.getURLs.iterator
      .map(url => Paths.get(url.toURI))
      .filter(_.getFileName.toString.startsWith("scala-"))

  def cwd: Path = Paths.get(System.getProperty("user.dir"))

  def handleBsp(): Unit = {
    val stdout = System.out
    val stdin = System.in
    val home = Paths.get(".bill")
    val log = home.resolve("bill.log")
    val trace = home.resolve("bill.trace.json")
    val logStream = new PrintStream(Files.newOutputStream(log, StandardOpenOption.APPEND))
    System.setOut(logStream)
    System.setErr(logStream)
    val server = new Server()
    val traceWrites = new PrintWriter(
      Files.newOutputStream(
        trace,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
    )
    val executor = Executors.newCachedThreadPool()
    val launcher = new Launcher.Builder[BuildClient]()
      .traceMessages(traceWrites)
      .setOutput(stdout)
      .setInput(stdin)
      .setLocalService(server)
      .setRemoteInterface(classOf[BuildClient])
      .setExecutorService(executor)
      .create()
    server.client = launcher.getRemoteProxy
    try {
      launcher.startListening().get()
    } catch {
      case NonFatal(e) =>
        e.printStackTrace(stdout)
        System.exit(1)
    } finally {
      executor.shutdown()
    }
  }

  def handleInstall(workspace: Path): Unit = {
    val java = Paths.get(System.getProperty("java.home")).resolve("bin").resolve("java")
    val classpath = myClasspath.mkString(File.pathSeparator)
    val argv = new JsonArray
    argv.add(java.toString)
    argv.add("-classpath")
    argv.add(classpath)
    argv.add("bill.Bill")
    argv.add("bsp")
    val json =
      s"""{
         |  "name": "Bill",
         |  "version": "$version",
         |  "bspVersion": "$bspVersion",
         |  "languages": ["scala"],
         |  "argv": $argv
         |}
         |""".stripMargin
    val bsp = workspace.resolve(".bsp")
    val bspJson = bsp.resolve("bill.json")
    Files.createDirectories(bsp)
    Files.write(bspJson, json.getBytes(StandardCharsets.UTF_8))
    println(s"installed: $bspJson")
  }

  def handleHelp(): Unit = {
    println(
      s"""bill v$version
         |usage: bill install (setup server discovery files in .bsp/ directory)
         |       bill bsp     (start BSP server)
         |       bill help    (print this help)
       """.stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    args.toList match {
      case List("help" | "--help" | "-help" | "-h") => handleHelp()
      case List("install") => handleInstall(cwd)
      case List("bsp") => handleBsp()
      case unknown =>
        println(s"Invalid arguments: ${unknown.mkString(" ")}")
        handleHelp()
        System.exit(1)
    }
  }
}
