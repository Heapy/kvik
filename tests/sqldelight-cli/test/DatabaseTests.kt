package fixture

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import fixture.database.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatabaseTests {
    @Test fun typedSqliteQueries() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(driver)
            database.personQueries.insertPerson(2, "Grace")
            database.personQueries.insertPerson(1, "Ada")
            assertEquals(listOf("Ada", "Grace"), database.personQueries.selectNames().executeAsList())
        } finally {
            driver.close()
        }
    }
}
