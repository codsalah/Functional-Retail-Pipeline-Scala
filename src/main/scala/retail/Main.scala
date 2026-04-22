package retail

import cats.effect.{ExitCode, IO, IOApp}
import doobie.Transactor
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.update.Update
import fs2.{Stream, text}
import fs2.io.file.{Files}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import java.nio.file.Paths
import java.time.LocalDate

object Main extends IOApp {

  // Logger (implicit for dependency injection)
  implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  // Database transactor - single connection managed by Doobie
  def createTransactor: Transactor[IO] = 
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", 
      "jdbc:postgresql://localhost:5432/ordersdb?reWriteBatchedInserts=true", 
      "docker", 
      "docker", 
      None
    )

  // Initialize database table (IO effect)
  def initDB(xa: Transactor[IO]): IO[Unit] = {
    val createTable = sql"""
      CREATE TABLE IF NOT EXISTS processed_orders (
        timestamp    TEXT,
        product_name TEXT,
        discount     REAL,
        final_price  REAL
      )
    """.update.run

    createTable.transact(xa).void
  }

  // Reads CSV file as a stream of lines, skipping header and empty lines. 
  // Uses fs2 streaming to keep memory usage constant.
  def readLinesStream(path: String): Stream[IO, String] =
    Files[IO].readAll(Paths.get(path), chunkSize = 64 * 1024)
      .through(text.utf8.decode)
      .through(text.lines)
      .filter(_.trim.nonEmpty)
      .drop(1) // Skip CSV header

  // Transforms stream of CSV lines into stream of processed order batches. 
  // Uses parallel evaluation on all available CPU cores.
  def processOrdersStream(lines: Stream[IO, String], chunkSize: Int): Stream[IO, List[ProcessedOrder]] =
    lines.chunkN(chunkSize)
      .parEvalMapUnordered(Runtime.getRuntime.availableProcessors()) { chunk =>
        IO {
          chunk.toList.flatMap { line =>
            val parts = line.split(",")
            if (parts.length >= 7) {
              val order = Order(
                transactionDate = LocalDate.parse(parts(0).substring(0, 10)),
                productName     = parts(1),
                expiryDate      = LocalDate.parse(parts(2)),
                quantity        = parts(3).toInt,
                unitPrice       = parts(4).toDouble,
                channel         = parts(5),
                paymentMethod   = parts(6)
              )
              val discount = RuleEngine.calculateDiscount(order)
              val finalPrice = order.unitPrice * order.quantity * (1 - discount)
              Some(ProcessedOrder(order, discount, finalPrice))
            } else {
              RuleLogger.logger("WARN", s"Skipping malformed line: $line")
              None
            }
          }
        }
      }

  // Inserts a batch of processed orders into the database. Each batch is processed in its own transaction.
  // IO effect for database operations.
  def saveBatchToDb(batch: List[ProcessedOrder], xa: Transactor[IO]): IO[Unit] = {
    val sql = """
      INSERT INTO processed_orders (timestamp, product_name, discount, final_price)
      VALUES (?, ?, ?, ?)
    """
    
    // Map to flat tuple for doobie Write type derivation (String, String, Double, Double)
    val rows = batch.map(po => (
      po.processedAt.toString,
      po.order.productName,
      po.discount,
      po.finalPrice
    ))

    (Update[(String, String, Double, Double)](sql).updateMany(rows).transact(xa).void >>
      logger.info(s"Batch of ${batch.size} orders committed") >>
      IO { RuleLogger.logger("INFO", s"Batch of ${batch.size} orders committed") })
  }

  // Complete processing pipeline combining streaming, parallel processing, and database writes.
  def pipeline(xa: Transactor[IO], path: String, chunkSize: Int, writeParallelism: Int): IO[Unit] =
    (Stream.eval(logger.info("--- Processing Started ---")) >>
      processOrdersStream(readLinesStream(path), chunkSize)
        .parEvalMapUnordered(writeParallelism)(batch => saveBatchToDb(batch, xa))
        .onFinalize(logger.info("--- Processing Completed Successfully ---"))
    ).compile.drain

  override def run(args: List[String]): IO[ExitCode] = {
    val xa = createTransactor
    
    val program = for {
      _   <- initDB(xa)
      _   <- logger.info("Database initialized successfully")
      _   <- IO { RuleLogger.logger("INFO", "Pipeline started") }
      _   <- pipeline(xa, "src/main/resources/TRX10M.csv", 20000, 12)
      _   <- logger.info("Pipeline execution completed")
      _   <- IO { RuleLogger.logger("INFO", "Pipeline completed successfully") }
    } yield ExitCode.Success

    program.handleErrorWith(err => 
      logger.error(s"Fatal error: ${err.getMessage}").as(ExitCode.Error)
    )
  }
}