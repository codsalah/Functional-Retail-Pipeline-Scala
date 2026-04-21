//> using dep org.xerial:sqlite-jdbc:3.53.0.0
package retail

import java.time.{LocalDate, LocalDateTime}
import java.sql.{Connection, DriverManager, PreparedStatement}
import scala.util.{Try, Success, Failure}
import scala.io.Source

// The Main Application - Imperative Shell
// This file contains all side effects (I/O, DB, logging) and orchestrates the pure functional core
object Main {
  def main(args: Array[String]): Unit = {
    // IMPERATIVE SHELL: All side effects live here

    // Database Operations - Pure DB operation logic (returns Either for error handling)
    // The DB operation itself is a side effect, but the function is pure in terms of its logic
    def saveToDB(po: ProcessedOrder, conn: Connection): Either[String, Unit] =
      Try {
        val sql = "INSERT INTO processed_orders (timestamp, product_name, discount, final_price) VALUES (?, ?, ?, ?)"
        val stmt: PreparedStatement = conn.prepareStatement(sql)
        stmt.setString(1, po.processedAt.toString)
        stmt.setString(2, po.order.productName)
        stmt.setDouble(3, po.discount)
        stmt.setDouble(4, po.finalPrice)
        stmt.executeUpdate()
        stmt.close()
        () // Return Unit to ensure the type is Either[String, Unit]
      }.toEither.left.map(_.getMessage)

    // Helper to initialise the DB and create the table if it doesn't exist
    def initDB(): Either[String, Connection] =
      Try {
        val conn = DriverManager.getConnection("jdbc:sqlite:processed_orders.db")
        val stmt = conn.createStatement()
        stmt.execute(
          """CREATE TABLE IF NOT EXISTS processed_orders (
            |  timestamp    TEXT,
            |  product_name TEXT,
            |  discount     REAL,
            |  final_price  REAL
            |)""".stripMargin
        )
        stmt.close()
        conn
      }.toEither.left.map(_.getMessage)
          
    // 3. File I/O (Read CSV, Process)
    def processFile(filePath: String, conn: Connection): Either[String, Unit] =
      Try {
        val source = Source.fromFile(filePath)
        
        // Ensure source is closed even if processing fails
        try {
          // foldLeft replaces foreach: accumulates Unit, preserving functional style (no loops)
          source.getLines().drop(1).foldLeft(()) { (_, line) =>
            val parts = line.split(",")
            if (parts.length >= 7) {
              val order = Order(
                // Extracting date from timestamp (e.g., 2023-04-18)
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
              val processedOrder = ProcessedOrder(order, discount, finalPrice)
              
              // Used Either Left/Right for error handling - logging is handled by caller (imperative shell)
              saveToDB(processedOrder, conn) match {
                case Left(err) =>
                  RuleLogger.logger("ERROR", s"Failed to save order ${order.productName}: $err")
                case Right(_) =>
                  RuleLogger.logger("INFO", s"Successfully saved to ProcessedOrders: ${order.productName}")
              }
            } else {
              RuleLogger.logger("WARN", s"Skipping malformed line: $line") // simple error handling for malformed lines
            }
          }
        } finally {
          source.close()
        }
      }.toEither.left.map(_.getMessage)

    // Execution trigger
    val fileName = "src/main/resources/TRX1000.csv"

    // Initialise DB, process file, then close the connection
    // Used Either Left/Right for error handling
    initDB() match {
      case Left(e)     => RuleLogger.logger("ERROR", s"DB initialisation failed: $e")
      case Right(conn) =>
        processFile(fileName, conn) match {
          case Right(_) => RuleLogger.logger("INFO", "File processing completed successfully.")
          case Left(e)  => RuleLogger.logger("ERROR", s"Critical failure: $e")
        }
        conn.close()
    }
  }
}