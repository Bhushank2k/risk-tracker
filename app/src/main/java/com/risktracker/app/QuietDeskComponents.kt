package com.risktracker.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class RiskSeverity { POSITIVE, INFORMATION, CAUTION, HIGH, CRITICAL, NEUTRAL }

@Composable
fun KpiValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    sampleData: Boolean = false
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = financialNumberFont(),
                fontSize = 30.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Medium
            ),
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (sampleData) SeverityChip("SAMPLE DATA", RiskSeverity.INFORMATION)
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SeverityChip(label: String, severity: RiskSeverity, modifier: Modifier = Modifier) {
    val (container, content, icon) = when (severity) {
        RiskSeverity.POSITIVE -> Triple(Color(0xFFE5F0EB), Color(0xFF287A54), Icons.Outlined.CheckCircle)
        RiskSeverity.INFORMATION -> Triple(Color(0xFFE8EFF7), Color(0xFF456D9B), Icons.Outlined.Info)
        RiskSeverity.CAUTION -> Triple(Color(0xFFF4EEDB), Color(0xFF8A6B12), Icons.Outlined.WarningAmber)
        RiskSeverity.HIGH -> Triple(Color(0xFFF8EBDD), Color(0xFFA85B18), Icons.Outlined.WarningAmber)
        RiskSeverity.CRITICAL -> Triple(Color(0xFFF8E7E9), Color(0xFFB42332), Icons.Outlined.ErrorOutline)
        RiskSeverity.NEUTRAL -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.Info)
    }
    val dark = MaterialTheme.colorScheme.background == RiskTrackerDesign.nightCanvas
    val actualContainer = if (dark) when (severity) {
        RiskSeverity.POSITIVE -> Color(0xFF243B34)
        RiskSeverity.INFORMATION -> Color(0xFF263747)
        RiskSeverity.CAUTION -> Color(0xFF403A25)
        RiskSeverity.HIGH -> Color(0xFF493322)
        RiskSeverity.CRITICAL -> Color(0xFF4A2529)
        RiskSeverity.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    } else container
    val actualContent = if (dark) when (severity) {
        RiskSeverity.POSITIVE -> RiskTrackerDesign.nightPositiveGreen
        RiskSeverity.INFORMATION -> RiskTrackerDesign.nightInformationBlue
        RiskSeverity.CAUTION -> RiskTrackerDesign.nightCautionGold
        RiskSeverity.HIGH -> RiskTrackerDesign.nightHighAmber
        RiskSeverity.CRITICAL -> RiskTrackerDesign.nightCriticalRed
        RiskSeverity.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    } else content
    Surface(color = actualContainer, shape = MaterialTheme.shapes.extraLarge, modifier = modifier) {
        Row(
            modifier = Modifier.height(28.dp).widthInIfNeeded(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            androidx.compose.foundation.layout.Box(Modifier.width(8.dp))
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = actualContent, modifier = Modifier.size(14.dp))
            Text(label, color = actualContent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(3.dp))
        }
    }
}

@Composable
fun SourceBadge(label: String, imported: Boolean, modifier: Modifier = Modifier) {
    SeverityChip(label, if (imported) RiskSeverity.INFORMATION else RiskSeverity.NEUTRAL, modifier)
}

@Composable
fun ChartHeader(title: String, period: String, units: String, summary: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("$period · $units", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun QuietFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            isError = error != null,
            trailingIcon = unit?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        )
        (error ?: helper)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun QuietEmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAction, modifier = Modifier.height(48.dp)) { Text(actionLabel) }
    }
}

@Composable
fun QuietErrorState(
    title: String,
    detail: String,
    localDataSafe: Boolean,
    recoveryLabel: String,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeverityChip("ERROR", RiskSeverity.CRITICAL)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (localDataSafe) "Local data is unchanged." else "Local data status needs review.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onRecover, modifier = Modifier.height(48.dp)) { Text(recoveryLabel) }
    }
}

@Composable
fun QuietConfirmationDialog(
    title: String,
    affectedData: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("This action affects: $affectedData. This cannot be assumed to be reversible. Continue only if these are the intended records.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun Modifier.widthInIfNeeded(): Modifier = this
