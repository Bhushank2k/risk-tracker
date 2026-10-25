package com.risktracker.app

enum class LedgerSource { MANUAL, BINANCE_API, BINANCE_CSV, JOURNAL_ESTIMATE, ADJUSTMENT }
enum class LedgerEventType { REALIZED_PNL, FEE, FUNDING, DEPOSIT, WITHDRAWAL, ADJUSTMENT }

/** Provenance envelope for future ledger-backed reporting; it does not replace existing stored records. */
data class LedgerRecord(
    val id: String,
    val date: String,
    val amount: Double,
    val currency: String,
    val source: LedgerSource,
    val sourceRecordId: String? = null,
    val importBatchId: String? = null,
    val eventType: LedgerEventType = LedgerEventType.REALIZED_PNL,
    val estimated: Boolean = false
)

data class LedgerDuplicate(val source: LedgerSource, val sourceRecordId: String, val recordIds: List<String>)
data class LedgerSourceTotal(val source: LedgerSource, val currency: String, val eventType: LedgerEventType, val total: Double, val recordCount: Int)
data class LedgerAuditReport(val invalidRecordIds: List<String>, val duplicateSourceRecords: List<LedgerDuplicate>, val totalsBySource: List<LedgerSourceTotal>)

/**
 * Audits provenance without merging records from different sources. In particular, journal estimates
 * and exchange-imported P&L remain separate so the same trade cannot be silently counted twice.
 */
fun auditLedgerRecords(records: List<LedgerRecord>): LedgerAuditReport {
    val invalid = records.filter { it.id.isBlank() || it.date.isBlank() || it.currency.isBlank() || !it.amount.isFinite() }
        .map { it.id.ifBlank { "<missing-id>" } }
    val duplicates = records.filter { !it.sourceRecordId.isNullOrBlank() }
        .groupBy { it.source to it.sourceRecordId!! }
        .filterValues { it.size > 1 }
        .map { (key, rows) -> LedgerDuplicate(key.first, key.second, rows.map { it.id }) }
    val totals = records.filter { it.amount.isFinite() && it.currency.isNotBlank() }
        .groupBy { Triple(it.source, it.currency.uppercase(), it.eventType) }
        .map { (key, rows) -> LedgerSourceTotal(key.first, key.second, key.third, rows.sumOf { it.amount }, rows.size) }
        .sortedWith(compareBy({ it.source.name }, { it.currency }, { it.eventType.name }))
    return LedgerAuditReport(invalid.distinct(), duplicates, totals)
}
