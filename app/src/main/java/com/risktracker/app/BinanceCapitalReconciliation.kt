package com.risktracker.app

/**
 * Reconciles only external cash flows visible in the saved statement archive.
 * It deliberately does not infer starting capital from a partial statement history.
 */
data class BinanceAssetCashFlow(
    val asset: String,
    val deposits: Double,
    val withdrawals: Double,
    val netExternalFlow: Double,
    val completedDepositCount: Int,
    val completedWithdrawalCount: Int
)

data class BinanceCapitalReconciliation(
    val assetFlows: List<BinanceAssetCashFlow>,
    val firstRecordTime: Long?,
    val lastRecordTime: Long?,
    val totalStoredRecords: Int,
    val completedExternalFlowRecords: Int,
    val isHistoryComplete: Boolean = false
)

object BinanceCapitalReconciliationEngine {
    private val completedDepositStatuses = setOf("1", "6", "SUCCESS", "COMPLETED")
    private val completedWithdrawalStatuses = setOf("6", "SUCCESS", "COMPLETED")

    fun reconcile(rows: List<BinanceStatement>): BinanceCapitalReconciliation {
        val deposits = rows.filter {
            it.category == "Deposits" && it.status.uppercase() in completedDepositStatuses && it.asset.isNotBlank()
        }
        val withdrawals = rows.filter {
            it.category == "Withdrawals" && it.status.uppercase() in completedWithdrawalStatuses && it.asset.isNotBlank()
        }
        val assets = (deposits.map { it.asset } + withdrawals.map { it.asset }).distinct().sorted()
        val flows = assets.map { asset ->
            val assetDeposits = deposits.filter { it.asset == asset }
            val assetWithdrawals = withdrawals.filter { it.asset == asset }
            val depositTotal = assetDeposits.sumOf { it.amount }
            val withdrawalTotal = assetWithdrawals.sumOf { it.amount }
            BinanceAssetCashFlow(
                asset = asset,
                deposits = depositTotal,
                withdrawals = withdrawalTotal,
                netExternalFlow = depositTotal - withdrawalTotal,
                completedDepositCount = assetDeposits.size,
                completedWithdrawalCount = assetWithdrawals.size
            )
        }
        return BinanceCapitalReconciliation(
            assetFlows = flows,
            firstRecordTime = rows.minOfOrNull { it.time },
            lastRecordTime = rows.maxOfOrNull { it.time },
            totalStoredRecords = rows.size,
            completedExternalFlowRecords = deposits.size + withdrawals.size,
            // The API collector is bounded and best-effort; completeness requires verified imported history.
            isHistoryComplete = false
        )
    }
}
