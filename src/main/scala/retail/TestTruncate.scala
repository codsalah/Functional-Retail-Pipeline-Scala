package retail

import cats.effect.{ExitCode, IO, IOApp}
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object TestTruncate extends IOApp {

  // Logger (implicit for dependency injection)
  implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  // Database configuration (same as Main.scala)
  def createTransactor: Transactor[IO] = 
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver", 
      "jdbc:postgresql://localhost:5432/ordersdb?reWriteBatchedInserts=true", 
      "docker", 
      "docker", 
      None
    )

  // Truncate the processed_orders table
  def truncateTable(xa: Transactor[IO]): IO[Unit] = {
    val truncateSQL = sql"TRUNCATE TABLE processed_orders"
    
    for {
      _ <- logger.info("Truncating processed_orders table...")
      result <- truncateSQL.update.run.transact(xa)
      _ <- logger.info(s"Table truncated successfully. Rows affected: $result")
    } yield ()
  }

  // Check if table exists and get row count
  def getRowCount(xa: Transactor[IO]): IO[Int] = {
    val countSQL = sql"SELECT COUNT(*) FROM processed_orders"
    countSQL.query[Int].unique.transact(xa)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val xa = createTransactor
    
    val program = for {
      initialCount <- getRowCount(xa)
      _ <- logger.info(s"Initial row count: $initialCount")
      
      _ <- truncateTable(xa)
      
      finalCount <- getRowCount(xa)
      _ <- logger.info(s"Final row count: $finalCount")
      
      _ <- IO { RuleLogger.logger("INFO", s"Table truncated. Initial rows: $initialCount, Final rows: $finalCount") }
      _ <- logger.info("Database truncation completed successfully")
      
    } yield ExitCode.Success

    program.handleErrorWith(err => 
      logger.error(s"Error during truncation: ${err.getMessage}").as(ExitCode.Error)
    )
  }
}
