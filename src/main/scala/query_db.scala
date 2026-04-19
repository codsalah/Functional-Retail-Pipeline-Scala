//> using dep org.xerial:sqlite-jdbc:3.53.0.0
import java.sql.DriverManager

@main def runQuery(input: String = "10") = {
  val conn = DriverManager.getConnection("jdbc:sqlite:processed_orders.db")
  
  // Decide if input is a limit (number) or a full SQL query
  val sql = if (input.forall(_.isDigit)) {
    s"SELECT * FROM processed_orders LIMIT $input"
  } else {
    input
  }

  try {
    val stmt = conn.createStatement()
    val rs = stmt.executeQuery(sql)
    val meta = rs.getMetaData
    val colCount = meta.getColumnCount

    // Print Headers
    for (i <- 1 to colCount) {
      print(f"${meta.getColumnName(i)}%-30s | ")
    }
    println("\n" + "-" * (colCount * 33))

    // Print Rows
    while (rs.next()) {
      for (i <- 1 to colCount) {
        val value = rs.getObject(i)
        val display = if (value == null) "NULL" else value.toString
        print(f"$display%-30s | ")
      }
      println()
    }
  } catch {
    case e: Exception => println(s"Error executing query: ${e.getMessage}")
  } finally {
    conn.close()
  }
}
