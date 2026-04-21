package retail

// Rule Logger - IMPERATIVE SHELL
// Handles file I/O side effect (logging)
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import scala.util.Try

object RuleLogger {
  def logger(level: String, message: String): Either[String, Unit] =
    Try {
      val timestamp = LocalDateTime.now()
      val logMessage = s"$timestamp $level $message\n"
      
      Files.write(
        Paths.get("rules_engine.log"),
        logMessage.getBytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      () 
    }.toEither.left.map(_.getMessage)
}
