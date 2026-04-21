package retail

import java.time.temporal.ChronoUnit

// The Rule Engine - FUNCTIONAL CORE
// All functions here are pure: no side effects, no I/O, only data transformation
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

  //Rule: Quantity Step Discount (5 items = 5%, 10 items = 10%, etc.) 
  val quantityStepRule: DiscountRule = order => {
    if (order.channel.equalsIgnoreCase("app")) math.min(math.ceil(order.quantity / 5.0), 20.0) * 5.0 / 100.0 
    else 0.0
  }

  // Rule: Payment with Visa (5%)
  val visaRule: DiscountRule = order => {
    if (order.paymentMethod.equalsIgnoreCase("visa")) 0.05 else 0.0
  }
  
  // Vector of all rules 
  val rules: Vector[DiscountRule] = Vector(
    expiryRule,
    saleRule,
    specialDateRule,
    bulkRule,
    quantityStepRule,
    visaRule
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
