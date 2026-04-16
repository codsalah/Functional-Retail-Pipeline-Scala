import java.time.{LocalDate, LocalDateTime}
import java.time.temporal.ChronoUnit
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.util.{Try, Success, Failure}
import scala.io.Source

// The Data models
case class Order(
  transactionDate: LocalDate,
  productName: String,
  expiryDate: LocalDate,
  unitPrice: Double,
  quantity: Int,
  channel: String,     
  paymentMethod: String
)

case class ProcessedOrder(
  order: Order,
  discount: Double,
  finalPrice: Double,
  processedAt: LocalDateTime = LocalDateTime.now()
)

// The Rule Engine (Pure Functions)
object RuleEngine {
  type DiscountRule = Order => Double
  
  // Rule: Expiry logic (1% for 29days, 2% for 28, etc)
  val expiryRule: DiscountRule = order => {
    val daysRemaining = ChronoUnit.DAYS.between(order.transactionDate, order.expiryDate)
    if (daysRemaining < 30 && daysRemaining > 0) (30 - daysRemaining) * 0.01 
    else 0.0
  }

  // Rule: Category logic (Cheese 10%, Wine 5%)
  val saleRule: DiscountRule = order => {
    order.productName.toLowerCase match {
      case s if s.contains("cheese") => 0.10
      case s if s.contains("wine")   => 0.05
      case _                         => 0.0
    }
  }
  
  // Rule: Black Date Big Discount (March 23rd --> 50%)
  val specialDateRule: DiscountRule = order => {
    if (order.transactionDate.getMonthValue == 3 && order.transactionDate.getDayOfMonth == 23) 
      0.50 
    else 
      0.0
  }
  
  // Rule: Bulk Discount (10+ items --> 5%)
  val bulkRule: DiscountRule = order => {
    order.quantity match {
      case q if q >= 15 => 0.10 
      case q if q >= 10 => 0.07
      case q if q >= 6  => 0.05
      case _ => 0.0
    }
  }    

  // Vector of all rules 
  val rules: Vector[DiscountRule] = Vector(
    expiryRule,
    saleRule,
    specialDateRule,
    bulkRule
  )
  
  def calculateDiscount(order: Order): Double = {

    // Calculate the average of the top two discounts (separating discounts from averaging)
    val appliedDiscounts = rules
      .map(rule => rule(order))
      .filter(_ > 0)
      .sortBy(-_)

    // Calculate the final discount (The result of this match is returned by the function)
    appliedDiscounts.take(2) match {
      case results if results.isEmpty => 0.0
      case results => results.sum / results.size
    }
  }
}

// The Main Application
object Main {
  def main(args: Array[String]): Unit = {
    // Here lives the side effects (Impure Functions)
    // 1. Logging (TIMESTAMP LOGLEVEL MESSAGE)
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
    
    // 2. Database Operations
    def saveToDB(po: ProcessedOrder): Either[String, Unit] =
      Try {
        val path = Paths.get("ProcessedOrders.csv")
        // Create file with header if it doesn't exist
        if (!Files.exists(path)) {
          val header = "timestamp,product_name,discount,final_price\n"
          Files.write(path, header.getBytes, StandardOpenOption.CREATE)
        }
        
        val row = s"${po.processedAt},${po.order.productName},${po.discount},${po.finalPrice}\n"
        Files.write(path, row.getBytes, StandardOpenOption.APPEND)
        
        // Use .foreach or a match to execute the side-effect without changing the return type
        logger("INFO", s"Successfully saved to ProcessedOrders: ${po.order.productName}")
        () // Return Unit to ensure the type is Either[String, Unit]
      }.toEither.left.map(_.getMessage)
          
    // 3. File I/O (Read CSV, Process)
    def processFile(filePath: String): Either[String, Unit] =
      Try {
        val source = Source.fromFile(filePath)
        
        // Ensure source is closed even if processing fails
        try {
          source.getLines().drop(1).foreach { line =>
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
              
              // Used Either Left/Right for error handling
              saveToDB(processedOrder) match {
                case Left(err) => logger("ERROR", s"Failed to save order ${order.productName}: $err")
                case Right(_)  => // already logged inside saveToDB
              }
            } else {
              logger("WARN", s"Skipping malformed line: $line") // simple error handling for malformed lines
            }
          }
        } finally {
          source.close()
        }
      }.toEither.left.map(_.getMessage)

    // Execution trigger
    val fileName = "src/main/resources/TRX1000.csv" 
    // Used Either Left/Right for error handling
    processFile(fileName) match {
      case Right(_) => logger("INFO", "File processing completed successfully.")
      case Left(e)  => logger("ERROR", s"Critical failure: $e")
    }

    val lineCountAfter: Either[String, Long] =
      Try(Files.lines(Paths.get("rules_engine.log")).count())
        .toEither.left.map(_.getMessage)
  }
}