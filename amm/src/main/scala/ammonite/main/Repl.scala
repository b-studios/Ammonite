package ammonite.main
import acyclic.file
import java.io.{InputStream, InputStreamReader, OutputStream, PrintStream}

import ammonite.frontend._
import ammonite.interp._
import ammonite.terminal.Filter
import ammonite.util._
import ammonite.util.Util.newLine

import scala.annotation.tailrec

class Repl(input: InputStream,
           output: OutputStream,
           error: OutputStream,
           storage: Storage,
           predef: String,
           wd: ammonite.ops.Path,
           welcomeBanner: Option[String],
           replArgs: Seq[Bind[_]] = Nil) {

  val prompt = Ref("@ ")

  val frontEnd = Ref[FrontEnd](AmmoniteFrontEnd(Filter.empty))

  var history = new History(Vector())

  val (colors, printStream, errorPrintStream, printer) =
    Interpreter.initPrinters(output, error)

  val hardcodedPredef =
    "import ammonite.frontend.ReplBridge.value.{pprintConfig, derefPPrint}"

  val argString = replArgs.zipWithIndex.map{ case (b, idx) =>
    s"""
    val ${b.name} =
      ammonite.frontend.ReplBridge.value.replArgs($idx).value.asInstanceOf[${b.typeTag.tpe}]
    """
  }.mkString(newLine)


  val interp: Interpreter = new Interpreter(
    printer,
    storage,
    predef,
    Seq(
      (hardcodedPredef, Name("HardcodedPredef")),
      (argString, Name("ArgsPredef"))
    ),
    wd
  )

  APIHolder.initBridge(
    interp.evalClassloader,
    "ammonite.frontend.ReplBridge",
    new ReplApiImpl(
      interp,
      frontEnd().width,
      frontEnd().height,
      colors,
      prompt,
      history,
      new SessionApiImpl(interp.eval)
    )
  )


  val reader = new InputStreamReader(input)

  def action() = for{
    (code, stmts) <- frontEnd().action(
      input,
      reader,
      output,
      colors().prompt()(prompt()).render,
      colors(),
      interp.pressy.complete(_, Preprocessor.importBlock(interp.eval.frames.head.imports), _),
      storage.fullHistory(),
      addHistory = (code) => if (code != "") {
        storage.fullHistory() = storage.fullHistory() :+ code
        history = history :+ code
      }
    )
    _ <- Signaller("INT") { interp.mainThread.stop() }
    out <- interp.processLine(code, stmts, s"cmd${interp.eval.getCurrentLine}.sc")
  } yield {
    printStream.println()
    out
  }

  def run(): Any = {
    welcomeBanner.foreach(printStream.println)
    interp.init()
    @tailrec def loop(): Any = {
      val actionResult = action()
      interp.handleOutput(actionResult)

      actionResult match{
        case Res.Exit(value) =>
          printStream.println("Bye!")
          value
        case Res.Failure(ex, msg) => printer.error(msg)
          loop()
        case Res.Exception(ex, msg) =>
          printer.error(
            Repl.showException(ex, colors().error(), fansi.Attr.Reset, colors().literal())
          )
          printer.error(msg)
          loop()
        case _ =>
          loop()
      }
    }
    loop()
  }
}

object Repl{
  def highlightFrame(f: StackTraceElement,
                     error: fansi.Attrs,
                     highlightError: fansi.Attrs,
                     source: fansi.Attrs) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else source(f.getFileName) ++ error(":") ++ source(f.getLineNumber.toString)

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString = prefix.map(_+'.').mkString("")
    val clsNameString = clsName //.replace("$", error("$"))
    val method =
      error(prefixString) ++ highlightError(clsNameString) ++ error(".") ++
        highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }
  def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {
    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString + newLine +
        exception
          .getStackTrace
          .takeWhile(x => !cutoff(x.getMethodName))
          .map(highlightFrame(_, error, highlightError, source))
          .mkString(newLine))
    )
    traces.mkString(newLine)
  }
}