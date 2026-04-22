package retail

// Rule Logger - IMPERATIVE SHELL
// Handles file I/O side effect (logging) with proper TIMESTAMP LOGLEVEL MESSAGE format
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

object RuleLogger {
  private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
  
  // Initialize logger on first use
  private var initialized = false
  private def ensureInitialized(): Unit = {
    if (!initialized) {
      val timestamp = LocalDateTime.now().format(formatter)
      val logMessage = s"$timestamp INFO Rule Logger initialized\n"
      
      Files.write(
        Paths.get("rules_engine.log"),
        logMessage.getBytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      initialized = true
    }
  }
  
  def logger(level: String, message: String): Either[String, Unit] = {
    ensureInitialized()
    Try {
      val timestamp = LocalDateTime.now().format(formatter)
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
}
