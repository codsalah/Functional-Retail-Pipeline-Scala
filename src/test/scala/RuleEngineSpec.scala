//> using dep org.scalameta:munit_3:1.0.0

package retail

import munit.FunSuite
import java.time.{LocalDate, LocalDateTime}

class RuleEngineSpec extends FunSuite {

  // Test Order fixtures
  val baseOrder: Order = Order(
    transactionDate = LocalDate.of(2024, 4, 15),
    productName = "Test Product",
    expiryDate = LocalDate.of(2024, 5, 15),
    unitPrice = 10.0,
    quantity = 5,
    channel = "store",
    paymentMethod = "cash"
  )

  test("Expiry Rule: return 1% discount when 29 days remaining") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 4, 15),
      expiryDate = LocalDate.of(2024, 5, 14)
    )
    assertEquals(RuleEngine.expiryRule(order), 0.01)
  }

  test("Expiry Rule: return 29% discount when 1 day remaining") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 4, 15),
      expiryDate = LocalDate.of(2024, 4, 16)
    )
    assertEquals(RuleEngine.expiryRule(order), 0.29)
  }

  test("Expiry Rule: return 0% discount when 30 or more days remaining") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 4, 15),
      expiryDate = LocalDate.of(2024, 5, 16)
    )
    assertEquals(RuleEngine.expiryRule(order), 0.0)
  }

  test("Expiry Rule: return 0% discount when expiry date is in the past") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 4, 15),
      expiryDate = LocalDate.of(2024, 4, 14)
    )
    assertEquals(RuleEngine.expiryRule(order), 0.0)
  }

  test("Sale Rule: return 10% discount for cheese products") {
    val order = baseOrder.copy(productName = "Aged Cheddar Cheese")
    assertEquals(RuleEngine.saleRule(order), 0.10)
  }

  test("Sale Rule: return 5% discount for wine products") {
    val order = baseOrder.copy(productName = "Red Wine")
    assertEquals(RuleEngine.saleRule(order), 0.05)
  }

  test("Sale Rule: return 0% discount for other products") {
    val order = baseOrder.copy(productName = "Bread")
    assertEquals(RuleEngine.saleRule(order), 0.0)
  }

  test("Sale Rule: be case-insensitive") {
    val order = baseOrder.copy(productName = "CHEESE")
    assertEquals(RuleEngine.saleRule(order), 0.10)
  }

  test("Special Date Rule: return 50% discount on March 23rd") {
    val order = baseOrder.copy(transactionDate = LocalDate.of(2024, 3, 23))
    assertEquals(RuleEngine.specialDateRule(order), 0.50)
  }

  test("Special Date Rule: return 0% discount on other dates") {
    val order = baseOrder.copy(transactionDate = LocalDate.of(2024, 4, 15))
    assertEquals(RuleEngine.specialDateRule(order), 0.0)
  }

  test("Bulk Rule: return 10% discount for 15+ items") {
    val order = baseOrder.copy(quantity = 15)
    assertEquals(RuleEngine.bulkRule(order), 0.10)
  }

  test("Bulk Rule: return 7% discount for 10-14 items") {
    val order = baseOrder.copy(quantity = 12)
    assertEquals(RuleEngine.bulkRule(order), 0.07)
  }

  test("Bulk Rule: return 5% discount for 6-9 items") {
    val order = baseOrder.copy(quantity = 7)
    assertEquals(RuleEngine.bulkRule(order), 0.05)
  }

  test("Bulk Rule: return 0% discount for less than 6 items") {
    val order = baseOrder.copy(quantity = 5)
    assertEquals(RuleEngine.bulkRule(order), 0.0)
  }

  test("Quantity Step Rule: return 5% discount for app channel with 3 items") {
    val order = baseOrder.copy(channel = "app", quantity = 3)
    assertEquals(RuleEngine.quantityStepRule(order), 0.05)
  }

  test("Quantity Step Rule: return 10% discount for app channel with 7 items") {
    val order = baseOrder.copy(channel = "app", quantity = 7)
    assertEquals(RuleEngine.quantityStepRule(order), 0.10)
  }

  test("Quantity Step Rule: return 15% discount for app channel with 12 items") {
    val order = baseOrder.copy(channel = "app", quantity = 12)
    assertEquals(RuleEngine.quantityStepRule(order), 0.15)
  }

  test("Quantity Step Rule: cap at 100% for very large quantities") {
    val order = baseOrder.copy(channel = "app", quantity = 100)
    assertEquals(RuleEngine.quantityStepRule(order), 1.0)
  }

  test("Quantity Step Rule: return 0% discount for non-app channels") {
    val order = baseOrder.copy(channel = "store", quantity = 10)
    assertEquals(RuleEngine.quantityStepRule(order), 0.0)
  }

  test("Quantity Step Rule: be case-insensitive for channel") {
    val order = baseOrder.copy(channel = "APP", quantity = 5)
    assertEquals(RuleEngine.quantityStepRule(order), 0.05)
  }

  test("Visa Rule: return 5% discount for Visa payments") {
    val order = baseOrder.copy(paymentMethod = "visa")
    assertEquals(RuleEngine.visaRule(order), 0.05)
  }

  test("Visa Rule: return 0% discount for other payment methods") {
    val order = baseOrder.copy(paymentMethod = "mastercard")
    assertEquals(RuleEngine.visaRule(order), 0.0)
  }

  test("Visa Rule: be case-insensitive") {
    val order = baseOrder.copy(paymentMethod = "VISA")
    assertEquals(RuleEngine.visaRule(order), 0.05)
  }

  test("calculateDiscount: return 0 when no rules qualify") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 4, 15),
      productName = "Bread",
      quantity = 3,
      channel = "store",
      paymentMethod = "cash"
    )
    assertEquals(RuleEngine.calculateDiscount(order), 0.0)
  }

  test("calculateDiscount: return the discount when only one rule qualifies") {
    val order = baseOrder.copy(
      productName = "Cheese",
      quantity = 3,
      channel = "store",
      paymentMethod = "cash"
    )
    assertEquals(RuleEngine.calculateDiscount(order), 0.10)
  }

  test("calculateDiscount: average the top 2 discounts when multiple rules qualify") {
    val order = baseOrder.copy(
      transactionDate = LocalDate.of(2024, 3, 23), // specialDateRule: 50%
      productName = "Cheese", // saleRule: 10%
      quantity = 15, // bulkRule: 10%
      channel = "store",
      paymentMethod = "visa" // visaRule: 5%
    )
    // Top 2: 0.50 and 0.10, average = 0.30
    assertEquals(RuleEngine.calculateDiscount(order), 0.30)
  }

  test("Bulk Rule: handle boundary at exactly 6 items") {
    val order = baseOrder.copy(quantity = 6)
    assertEquals(RuleEngine.bulkRule(order), 0.05)
  }

  test("Bulk Rule: handle boundary at exactly 10 items") {
    val order = baseOrder.copy(quantity = 10)
    assertEquals(RuleEngine.bulkRule(order), 0.07)
  }

  test("Bulk Rule: handle boundary at exactly 15 items") {
    val order = baseOrder.copy(quantity = 15)
    assertEquals(RuleEngine.bulkRule(order), 0.10)
  }

  test("Quantity Step Rule: handle boundary at exactly 5 items") {
    val order = baseOrder.copy(channel = "app", quantity = 5)
    assertEquals(RuleEngine.quantityStepRule(order), 0.05)
  }

  test("Quantity Step Rule: handle boundary at exactly 10 items") {
    val order = baseOrder.copy(channel = "app", quantity = 10)
    assertEquals(RuleEngine.quantityStepRule(order), 0.10)
  }
}
