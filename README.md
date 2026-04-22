# Functional-Retail-Pipeline-Scala
A flexible, extensible Scala discount calculation pipeline for commercial orders.

A rule-based discount engine for a retail store, built in **pure functional Scala**. The engine reads transaction CSV data, evaluates each order against a set of discount rules, calculates the final price, and persists results to a PostgreSQL database — all while logging every event to a file.

![alt text](Imgs/overview.png)

## Functional Programming Constraints

The entire codebase respects the following FP constraints in the **functional core**:

| Constraint | How it's enforced |
|---|---|
| No `var` | Only `val` used in the functional core; imperative shell isolates necessary mutation |
| No mutable data structures | `Vector` for rules, case classes for data |
| No loops | `parEvalMapUnordered` / `map` / `filter` replaces all iteration |
| Pure functions | `RuleEngine` has zero side effects |
| Total functions | Every function returns a value for every possible input |
| Error handling | `Either[String, Unit]` via `.toEither.left.map(_.getMessage)` |

---

## Architecture — Separation of Concerns

The project follows the **Functional Core / Imperative Shell** pattern:

```
src/main/scala/retail/
├── dataModel.scala     → FUNCTIONAL CORE: Pure immutable data structures
├── ruleEngine.scala    → FUNCTIONAL CORE: Pure discount calculation logic
├── ruleLogger.scala    → IMPERATIVE SHELL: File I/O side effect (logging)
├── Main.scala          → IMPERATIVE SHELL: DB, file I/O, orchestration
├── SimpleQuery.scala   → UTILITY: Plain JDBC query tool for DB inspection
└── TestTruncate.scala  → UTILITY: Table truncation utility via Doobie
```

### Functional Core (Pure, Testable)
- **dataModel.scala**: Immutable case classes with no side effects
- **ruleEngine.scala**: Pure functions that transform `Order` → `Double` (discount)
- No I/O, no external state, no side effects
- Easily testable in isolation (see `RuleEngineSpec.scala`)

### Imperative Shell (Side Effects, Orchestration)
- **Main.scala**: Database operations, file reading, execution orchestration
- **ruleLogger.scala**: File writing (logging side effect)
- Handles all I/O and external interactions
- Calls the functional core to perform business logic

**Benefits:**
- The core logic can be tested without I/O dependencies
- The imperative shell can be swapped or modified independently
- Clear separation makes the code more maintainable and adaptable
- Adding a new rule never touches `Main.scala`. Adding a new field never touches `RuleEngine`.


### Parallelism Architecture

The system achieves maximum throughput through strategic parallelization at multiple levels:

![Parallelism Architecture](Imgs/prallelism.png)

**Parallel Processing Levels:**

1. **CPU-Level Parallelism**: `parEvalMapUnordered(nCPUs)` distributes chunk processing across all available CPU cores
2. **Database-Level Parallelism**: `parEvalMapUnordered(writeParallelism)` enables concurrent batch writes to PostgreSQL
3. **Stream-Level Parallelism**: fs2 streams enable backpressure-aware processing without blocking

**Benefits:**
- **Scalability**: Automatically utilizes all available CPU cores
- **Throughput**: Concurrent database writes prevent I/O bottlenecks
- **Memory Efficiency**: Constant memory footprint regardless of dataset size
- **Resource Utilization**: Maximizes both CPU and database connection pool usage


---

## End-to-End Execution Sequence

The following sequence diagram illustrates the complete execution flow from initialization through order processing to completion:

![alt text](Imgs/sequenceDiagram.png)

**Key Points:**
- **Main** orchestrates the entire workflow, starting with database initialization
- **readLinesStream** reads the CSV as a constant-memory fs2 byte stream decoded to UTF-8 lines
- **chunkN + parEvalMapUnordered** distributes chunk processing across all available CPU cores
- **RuleEngine** is pure — it takes an Order and returns a discount without side effects
- The discount calculation applies all 6 rules, filters positive values, sorts descending, and averages the top 2
- **saveBatchToDb** uses Doobie's `updateMany` for efficient batch inserts, each chunk in its own transaction
- **RuleLogger** is the only component that performs file I/O (writing to the log file)
- All error paths use `Either[String, Unit]` for functional error handling

---

## Data Models

```scala
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
```

---

## Rule Engine Design

### The Core Type Alias

```
DiscountRule ≡ Order → ℝ
```

A discount rule is a pure function from the `Order` algebraic data type to the real numbers (representing discount as a decimal). This type alias is the backbone of the entire engine — it enables **composition** and **aggregation** of rules without modification to the core calculation logic.

**Category Theory Lens:** Each rule is a morphism in the category where objects are types and morphisms are pure functions. The type constructor `Order → ℝ` is an exponential object in the Cartesian closed category of Scala types.

### The Rules Pool

```
rules: Vector[DiscountRule] = [expiryRule, saleRule, specialDateRule, bulkRule, quantityStepRule, visaRule]
```

The rules are accumulated in an immutable `Vector` — a persistent, functional sequence with O(1) append amortized complexity. This enables:
- **Open/Closed Principle**: Extend by adding to the Vector, never modify existing code
- **Referential transparency**: Each rule is a pure, composable value
- **Type safety**: The compiler guarantees all rules conform to `Order → ℝ`

### Discount Calculation Logic

```
calculateDiscount(order: Order): ℝ =
  let applied = rules ∘ (λr. r(order))          // apply every rule
      qualified = filter(> 0, applied)         // keep only qualifying ones
      sorted = sort(descending, qualified)    // sort descending
      top2 = take(2, sorted)                  // extract top 2
  in
      if top2 = ∅ then 0                      // identity element
      else sum(top2) / |top2|                 // average
```

**Functional Composition:** This is a pipeline of higher-order functions:
- `map` (∘): distributes the order across all rules
- `filter`: predicates on the discount semiring
- `sortBy`: orders by the total order on ℝ
- `take`: extracts a fixed-size prefix
- Pattern matching: handles the empty vs non-empty cases algebraically

### Key Logic
- If **no rule** qualifies → 0% discount.
- If **one rule** qualifies → that discount applies directly.
- If **multiple rules** qualify → take the **top 2** and **average** them.

### Rule Engine Flow

The following flowchart visualizes the discount calculation pipeline from order input through rule application to final discount:

![alt text](Imgs/ruleEngine.png)

**Flow Explanation:**
- **Order** enters the system and is distributed to all 6 rules in parallel
- Each rule in the **Vector[DiscountRule]** independently calculates its discount
- **map** applies each rule function to the order, producing a collection of discounts
- **filter** removes zero-valued discounts (rules that didn't qualify)
- **sortBy** orders the remaining discounts in descending order
- **take** extracts only the top 2 highest discounts
- **isEmpty?** checks if any rules qualified
- If no rules qualified, returns **0.0** (identity element)
- Otherwise, calculates the **average** of the top 2 discounts as the final result

---

## All Discount Rules

### 1. Expiry Rule: Linear Decay Function

**Functional Thinking Process:** Map temporal proximity to expiry to a linear discount gradient.

**Equation:**

```
d(expiry, transaction) = 
   0                                         if Δt ≥ 30 or Δt ≤ 0
  (30 - Δt) × 0.01                           otherwise
```
where **Δt = days between transaction and expiry**


| Days Remaining | Discount |
|---|---|
| 29 days | 1% |
| 28 days | 2% |
| 27 days | 3% |
| ... | ... |
| 1 day | 29% |

### 2. Sale Rule: String Pattern Matching

**Functional Thinking Process:** Predicate-based classification via substring containment.

**Equation:**

```
d(productName) = 
  0.10  if "cheese" ∈ productName (case-insensitive)
  0.05  if "wine" ∈ productName (case-insensitive)
  0.00  otherwise
```

| Product | Discount |
|---|---|
| Cheese | 10% |
| Wine | 5% |

### 3. Special Date Rule: Temporal Predicate

**Functional Thinking Process:** A constant function conditioned on a specific temporal predicate.

**Equation:**

```
d(date) = 
  0.50  if month = 3 ∧ day = 23
  0.00  otherwise
```


March 23rd → **50% discount** on all products.

### 4. Bulk Rule: Piecewise Function on ℕ

**Functional Thinking Process:** Quantization of integer quantity into discount tiers via pattern matching.

**Equation:**

```
d(quantity) = 
  0.10  if q ≥ 15
  0.07  if 10 ≤ q < 15
  0.05  if 6 ≤ q < 10
  0.00  otherwise
```

| Quantity | Discount |
|---|---|
| 6–9 units | 5% |
| 10–14 units | 7% |
| 15+ units | 10% |

### 5. App Quantity Step Rule: Continuous Discretization

**Functional Thinking Process:** Map unbounded integer domain to bounded discrete tiers via ceiling division.

**NEW Business requirement:** Encourage App usage by rewarding quantity-based purchases made through the App channel.

**Equation:**

```
d(q, channel) = 
  0.00                          if channel ≠ "app"
  min(⌈q/5⌉, 20) × 0.05     otherwise
```

**Functional Derivation:**
- The division `q/5` converts integer quantity to a continuous ratio
- `⌈·⌉` (ceiling) discretizes to integer tiers
- `min(·, 20)` bounds the output to prevent exceeding 100%
- Multiplication by 0.05 converts tier to *percentage* discount


**Why the cap at 20?** Tier 20 × 5% = 100% so the maximum possible discount. Beyond this, discounts are meaningless.


| Quantity | `⌈q/5⌉` | `min(...,20)` | Discount |
|---|---|---|---|
| 3 | 1 | 1 | 5% |
| 7 | 2 | 2 | 10% |
| 12 | 3 | 3 | 15% |
| 100 | 20 | 20 | 100% (capped) |

### 6. Visa Rule: Predicate Constant

**Functional Thinking Process:** A constant function guarded by an equality predicate on the payment method.

**NEW Business requirement:** Promote paperless payments by giving a minor discount to Visa card users.

**Equation:**
```
d(paymentMethod) = 
  0.05  if paymentMethod = "visa" (case-insensitive)
  0.00  otherwise
```

Flat **5% discount** for all Visa transactions.

---

## Worked Example: Multi-Rule Interaction

**Order:** channel = `"App"`, quantity = `7`, paymentMethod = `"Visa"`, transactionDate = `March 23rd`

| Rule | Fires? | Value |
|---|---|---|
| `expiryRule` | depends on expiry date | — |
| `saleRule` | No | 0% |
| `specialDateRule` | Yes (March 23rd) | 50% |
| `bulkRule` | Yes (6–9 units) | 5% |  
| `quantityStepRule` | Yes (App, qty=7) | 10% |
| `visaRule` | Yes (Visa) | 5% |

After sorting descending: `[0.50, 0.10, 0.07, 0.05]`

Top 2: `0.50` and `0.10`

**Final discount:** `(0.50 + 0.10) / 2 = 0.30` → **30%**


---

## Error Handling: Either[String, Unit]

All I/O operations use `Either` for functional error handling:

```scala
Try { ... }.toEither.left.map(_.getMessage)
```

- `Right(())` → success
- `Left(errorMessage)` → failure with descriptive string

This pattern is used consistently in `logger`, `saveBatchToDb`, `initDB`, and the stream pipeline, making every failure path explicit and type-safe with no unsafe `.get` calls anywhere.

---

## Database

PostgreSQL 16 via Docker, accessed through **Doobie** (functional JDBC wrapper for Cats Effect). The table is created on first run:

```sql
CREATE TABLE IF NOT EXISTS processed_orders (
  timestamp    TEXT,
  product_name TEXT,
  discount     REAL,
  final_price  REAL
)
```

Batch inserts use Doobie's `Update[...].updateMany(rows)` with `reWriteBatchedInserts=true` on the JDBC URL for maximum PostgreSQL throughput.

---

## Testing

### Database Query Tool

A plain JDBC query tool is provided to inspect the processed orders in the PostgreSQL database:

**File:** `src/main/scala/retail/SimpleQuery.scala`

**Usage:**
```bash
# Count all records
~/.local/share/coursier/bin/sbt "runMain retail.SimpleQuery"

# Show last N records (ordered by timestamp DESC)
~/.local/share/coursier/bin/sbt "runMain retail.SimpleQuery show 100"
```

**Features:**
- Count mode: returns total row count from `processed_orders`
- Show mode: prints the last N records formatted in aligned columns
- Handles connection lifecycle safely with `try/finally`

### Table Truncation Utility

**File:** `src/main/scala/retail/TestTruncate.scala`

Truncates `processed_orders` and logs before/after row counts — useful for re-running the pipeline on a clean slate:

```bash
~/.local/share/coursier/bin/sbt "runMain retail.TestTruncate"
```

### Unit Tests

**File:** `src/test/scala/RuleEngineSpec.scala`

Pure function unit tests for the discount rules using munit:

```bash
~/.local/share/coursier/bin/sbt test
```

The test suite verifies:
- Individual rule calculations (expiry, sale, special date, bulk, quantity step, visa)
- Top-2 averaging logic for multiple qualifying rules
- Edge cases (no rules qualify, single rule, multiple rules)
- Boundary conditions (exactly at thresholds)

---

## To Run The Project

### Prerequisites
- Docker and Docker Compose installed
- Scala 2.13+ (automatically handled by sbt)
- PostgreSQL (handled by Docker Compose)

### Quick Start

1. **Start PostgreSQL and Adminer or any other client database tool**
```bash
docker compose up -d
```
This starts two services:
- `orders_postgres` — PostgreSQL 16 on port `5432`
- `orders_adminer` — Adminer web UI on port `8080`

2. **Run the Main Application (Parallel Processing)**
```bash
~/.local/share/coursier/bin/sbt run
```
This will process 10 million records with parallel streaming and batch inserts.

3. **Query the Database**
```bash
# Count all records
~/.local/share/coursier/bin/sbt "runMain retail.SimpleQuery"

# Show last N records
~/.local/share/coursier/bin/sbt "runMain retail.SimpleQuery show 100"
```

### Database Access

**Web Interface:** Adminer at `http://localhost:8080`
- Server: `postgres` (or `localhost` from host machine)
- Username: `docker`
- Password: `docker`
- Database: `ordersdb`

**Command Line:** Use `SimpleQuery` or any standard PostgreSQL client pointed at `localhost:5432`

### Performance Features
- **Parallel Processing**: Uses all available CPU cores
- **Streaming**: Constant memory usage regardless of file size
- **Batch Inserts**: Optimized database writes (20,000 records per batch)
- **PostgreSQL**: High-performance concurrent database operations

### Run Unit Tests
```bash
~/.local/share/coursier/bin/sbt test
```

## Design Principles Applied

**Open/Closed Principle** — The engine is open for extension (add new `val` rules) but closed for modification (never touch `calculateDiscount` or the streaming pipeline to add a rule).

**Pure Core, Impure Shell** — `RuleEngine` is 100% pure with no side effects. All I/O (logging, DB, file reading) lives in `Main` and is clearly marked as impure.

**Pluggable Rule Pool** — `type DiscountRule = Order => Double` means any new business rule is just a new function. The `Vector` accumulates them. The averaging logic is universal.

**Constant-Memory Streaming** — fs2 `Stream` ensures memory usage stays flat regardless of input file size, processing 10M+ rows without loading the dataset into memory.