package retail

// Data Models - FUNCTIONAL CORE
// Pure immutable data structures with no side effects
import java.time.{LocalDate, LocalDateTime}

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
