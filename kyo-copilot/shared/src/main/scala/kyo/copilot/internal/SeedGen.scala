package kyo.copilot.internal

import kyo.llm
import kyo.llm.ais._
import kyo._
import kyo.ios._
import kyo.consoles._
import kyo.tries._
import kyo.llm.apps._
import scala.io.Source
import kyo.direct._
import kyo.lists.Lists
import com.eed3si9n.eval.Eval

import dotty.tools.repl.ScriptEngine
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.reporting.Reporter
import java.nio.file.Paths
import scala.util._
import java.io.{StringWriter, PrintWriter}
import java.io.Writer
import zio.Cause.Fail
import scala.util.control.NonFatal
import java.io._

object SeedGen extends App {

  def run(args: List[String]) =
    AIs.configs.let(_.apiKey("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")) {
      cleanReadme.map(eval.run(_))
    }

  def numbered(context: List[String]) =
    context.zipWithIndex.map {
      case (s, i) => s"$i: $s"
    }.mkString("\n")

  def show(t: Throwable): String = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    t.printStackTrace(pw)
    sw.toString
  }

  def cleanReadme =
    IOs {
      val doc =
        Source.fromFile("/Users/fwbrasil/workspace/kyo/README.md")
          .getLines().mkString("\n").replaceAll("\n\n", "\n")
      val pattern = """(?m)^(#+\s*.*$)|(?s)```scala(.*?)```""".r
      pattern.findAllMatchIn(doc).toList.flatMap { m =>
        if (m.group(1) != null) m.group(1).split('\n')
        else if (m.group(2) != null) m.group(2).split('\n')
        else Nil
      }
    }

  object eval {

    case class Result(
        reasonOfPreviousFailures: String,
        strategyToEnsureTheCodeWorks: String,
        relevantDocumentationSections: String,
        @desc("v.map(f) == v.flatMap(f) in Kyo")
        willTheCodeUseOnlyMapAsRecommended: Boolean,
        @desc("must be true")
        noFlatMapUsage: Boolean,
        @desc("Fibers, Resources, Consoles, Tries")
        willTheRunMethodReturnOnlySupportedEffects: Boolean,
        effectsToBeHandledBeforeReturning: String,
        mainKyoAppSourceCode: String
    )

    def run(context: List[String]): Unit > (AIs with Consoles) = {
      def loop(ai: AI, context: List[String], tries: Int): Unit > (AIs with Consoles) =
        for {
          _   <- ai.seed(seed(context), reminder)
          _   <- ai.userMessage("Generate an App implementation using Options and Tries.")
          _   <- ai.assistantMessage("Here is the most relevant sentence in the context:")
          res <- ai.gen[Result]
          r   <- Tries.run(eval(res.mainKyoAppSourceCode))
        } yield {
          r match {
            case Success(_) =>
              if (tries > 0) {
                selfRepair.run(ai, context).map(run(_))
              } else {
                run(context)
              }
            case Failure(ex) =>
              ai.userMessage(
                  s"Code failed, please analyze the error and correct any mistakes: $ex"
              ).andThen {
                loop(ai, context, tries + 1)
              }
          }
        }
      AIs.init.map(loop(_, context, 0))
    }

    def eval(code: String): Unit > IOs =
      IOs {
        println("***************")
        println(code)
        println("***************")
        val app = Eval().evalInfer(code).getValue(getClass.getClassLoader).asInstanceOf[App]
        app.main(Array())
      }

    def seed(context: List[String]) =
      p"""
        You're a CASK (Coding Assistant Specialized in Kyo). Thoroughly analyze the provided 
        documentation summary and generate functional code. The code will be immediatelly evaluated
        and returned with a failure to you if needed so try your best to avoid failing and wasting
        your precious tokens. Be creative, and don't doubt your capacity to perform the task.

        ${numbered(context)}
      """

    val reminder =
      p"""
        - You're a CASK.
        - Analyze the documentation.
        - Generate functional code.
        - Don't forget to include all necessary imports.
        - Always add `import kyo._`.
        - Include `import kyo.direct._` for direct syntax.
        - Kyo doesn't need `flatMap`, use `map` instead.
        - Note the effect rotation with get and run methods:
          val a: Int > Options = Options.get(Some(1))
          val b: Option[Int] > Any = Options.run(a)
        - Be creative.
      """
  }

  object selfRepair {

    case class Fix(
        fixReason: String,
        lineNumber: Int,
        newText: String
    )

    case class Repair(
        failureDescription: String,
        failureCauses: String,
        potentialFixes: String,
        planToFixTheContext: String,
        @desc("Make sure to generate changes that will allow you to succeed on the next try.")
        fixes: List[Fix]
    )

    def run(ai: AI, context: List[String]): List[String] > AIs =
      for {
        _ <- ai.seed(seed, reminder)
        r <- ai.gen[Repair]
      } yield {
        val a = context.toArray
        r.fixes.map {
          case Fix(_, n, t) =>
            a(n) = t
        }
        a.toList
      }

    val seed =
      p"""
        You're an SRA (Self-Reparing Agent). Analyze your interactions, reason about
        the possible causes for the failure, and provide an array of updates to the
        user's provided context.

        Note how the provide context has a number for each line. Use those numbers to
        replace the text in that line with the one provided by you.
        
        At most 3 line changes.
      """

    val reminder =
      p"""
        - You're an SRA
        - Analyze interactions to detect the cause of the failure.
        - Provide line changes to allow you to succeed on the next try.
        - Make sure the number lines match the provided.
      """

  }

  object compress {

    def run: List[String] > AIs =
      AIs.parallelTraverse(chunks)(AIs.gen[List[String]](seed, reminder, _))
        .map(_.flatten.toList)

    def chunks: List[String] > IOs =
      IOs(Source.fromFile("/Users/fwbrasil/workspace/kyo/README.md").getLines().sliding(
          300,
          250
      ).map(
          _.mkString("\n")
      ).toList)

    val seed =
      p"""
        Role
        ====
        You're a KDCA (Kyo documentation compression assistant). Your goal is compress the documentation
        of the Kyo library. The generated results will be consumed by an LLM like you to perform as a
        coding assistant for Kyo.
        
        Method
        ======
        Take the documentation and transform it into a series of short statements that capture all information 
        in a succint way. Please analyze the content, reason about its intricacies, and generate a set of 
        statements that will allow an LLM like you to take the statements and use the library.

        Instructions
        ============
        - Make sure to capture a comprehensive set code examples. Without them, the LLM can't generate code reliably.
        - Think like a coder and capture all information you may need.
        - The LLM keeps failing because of missing import statements in the generated code. Highlight imports in the output.
      """

    val reminder =
      p"""
        - Think like a coder.
        - You're a KDCA (Kyo documentation compression assistant).
        - Comprehensive code snippets to capture all signatures.
        - Highlight the necessary import statements.
      """
  }

}
