package com.risktracker.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BinanceStatementsTest {
    private fun row(
        id: String = "id-1",
        time: Long = 1_700_000_000_000L,
        amount: Double = 10.0
    ) = BinanceStatement(
        id = id,
        time = time,
        category = "Deposits",
        asset = "USDT",
        amount = amount,
        status = "Completed",
        reference = id,
        details = ""
    )

    @Test
    fun statementMergeIsIdempotentAndKeepsDistinctRecords() {
        val existing = listOf(row("old", 100L))
        val incoming = listOf(row("old", 100L), row("new-a", 200L), row("new-b", 300L))
        val (merged, inserted) = mergeBinanceStatementRows(existing, incoming)

        assertEquals(2, inserted)
        assertEquals(listOf("old", "new-a", "new-b"), merged.map { it.id })
        val (reimported, insertedAgain) = mergeBinanceStatementRows(merged, incoming)
        assertEquals(0, insertedAgain)
        assertEquals(merged, reimported)
    }

    @Test
    fun statementMergeRejectsInvalidIncomingRowsBeforeReturningReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            mergeBinanceStatementRows(emptyList(), listOf(row(amount = Double.NaN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeBinanceStatementRows(emptyList(), listOf(row(time = 0L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeBinanceStatementRows(emptyList(), listOf(row(id = " ")))
        }
    }

    @Test
    fun statementAmountParserRejectsMalformedAndNonFiniteValues() {
        assertEquals(12.5, parseBinanceStatementAmount(12.5), 0.0)
        assertEquals(-3.0, parseBinanceStatementAmount("-3.0"), 0.0)
        assertThrows(IllegalArgumentException::class.java) { parseBinanceStatementAmount("12oops") }
        assertThrows(IllegalArgumentException::class.java) { parseBinanceStatementAmount("NaN") }
        assertThrows(IllegalArgumentException::class.java) { parseBinanceStatementAmount(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { parseBinanceStatementAmount(null) }
    }
}
