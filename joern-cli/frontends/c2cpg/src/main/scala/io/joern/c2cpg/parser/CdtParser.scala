package io.joern.c2cpg.parser

import better.files.File
import io.joern.c2cpg.utils.{IOUtils, TimeUtils}
import org.eclipse.cdt.core.dom.ast.gnu.c.GCCLanguage
import org.eclipse.cdt.core.dom.ast.gnu.cpp.GPPLanguage
import org.eclipse.cdt.core.dom.ast.{IASTPreprocessorStatement, IASTTranslationUnit}
import org.eclipse.cdt.core.model.ILanguage
import org.eclipse.cdt.core.parser.{DefaultLogService, ScannerInfo}
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.CPPVisitor
import org.slf4j.LoggerFactory

import java.nio.file.{NoSuchFileException, Path}
import java.util
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object CdtParser {

  private val logger = LoggerFactory.getLogger(classOf[CdtParser])

  case class ParseResult(
      translationUnit: Option[IASTTranslationUnit],
      preprocessorErrorCount: Int = 0,
      problems: Int = 0,
      failure: Option[Throwable] = None,
      duration: Long = 0
  )

}

class CdtParser(parseConfig: ParserConfig, headerFileFinder: HeaderFileFinder)
    extends ParseProblemsLogger
    with PreprocessorStatementsLogger {

  import CdtParser._

  private val definedSymbols: util.Map[String, String] = parseConfig.definedSymbols.asJava
  private val includePaths: Set[String] = parseConfig.includePaths.map(_.toString)
  private val scannerInfo: ScannerInfo = new ScannerInfo(definedSymbols, includePaths.toArray)
  private val log: DefaultLogService = new DefaultLogService
  // enables parsing of code behind disabled preprocessor defines:
  private val opts: Int = ILanguage.OPTION_PARSE_INACTIVE_CODE

  private def createParseLanguage(file: Path): ILanguage = {
    if (FileDefaults.isCPPFile(file.toString)) {
      new GPPLanguage()
    } else {
      new GCCLanguage()
    }
  }

  private def parseInternal(file: Path): ParseResult = {
    val realPath = File(file)
    val (result, duration) = TimeUtils.time {
      if (realPath.isRegularFile) { // handling potentially broken symlinks
        val fileContent = IOUtils.readFileAsFileContent(realPath.path)
        val fileContentProvider = new CustomFileContentProvider(headerFileFinder)
        val lang = createParseLanguage(realPath.path)
        Try(
          lang.getASTTranslationUnit(fileContent, scannerInfo, fileContentProvider, null, opts, log)
        ) match {
          case Failure(e) =>
            ParseResult(None, failure = Some(e))
          case Success(translationUnit) =>
            val problems = CPPVisitor.getProblems(translationUnit)
            if (parseConfig.logProblems) logProblems(problems.toList)
            if (parseConfig.logPreprocessor) logPreprocessorStatements(translationUnit)
            ParseResult(
              Some(translationUnit),
              preprocessorErrorCount = translationUnit.getPreprocessorProblemsCount,
              problems = problems.length
            )
        }
      } else {
        ParseResult(
          None,
          failure = Some(new NoSuchFileException(s"File '$realPath' does not exist. Check for broken symlinks!"))
        )
      }
    }
    result.copy(duration = duration)
  }

  def preprocessorStatements(file: Path): Iterable[IASTPreprocessorStatement] = {
    parse(file).map(t => preprocessorStatements(t)).getOrElse(Iterable.empty)
  }

  def parse(file: Path): Option[IASTTranslationUnit] = {
    val parseResult = parseInternal(file)
    val duration = TimeUtils.pretty(parseResult.duration)
    parseResult match {
      case ParseResult(Some(t), c, p, _, _) =>
        logger.info(s"Parsed '${t.getFilePath}' in $duration ($c preprocessor error(s), $p problems)")
        Some(t)
      case ParseResult(_, _, _, maybeThrowable, _) =>
        logger.warn(s"Failed to parse '$file' (took: $duration) ${maybeThrowable.map(_.getMessage).getOrElse("")}")
        None
    }
  }

}
