package com.familysafety.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class BarEntry(val label: String, val value: Long, val accessibleValue: String)

/**
 * Simple vertical bar chart with an optional reference line (e.g. a daily limit). The full data is exposed as a
 * content description so screen-reader users get the same information.
 */
@Composable
fun BarChart(entries: List<BarEntry>, modifier: Modifier = Modifier, referenceValue: Long? = null, showLabels: Boolean = true) {
    val max = (entries.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(referenceValue ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.tertiary
    val line = MaterialTheme.colorScheme.error
    val description = entries.joinToString("; ") { "${it.label}: ${it.accessibleValue}" }
    Column(modifier.semantics { contentDescription = "Usage chart. $description" }) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (entries.isEmpty()) return@Canvas
            val slot = size.width / entries.size
            val barWidth = slot * 0.6f
            entries.forEachIndexed { i, e ->
                val h = size.height * (e.value.toFloat() / max)
                val color = if (referenceValue != null && e.value > referenceValue) over else bar
                drawRoundRect(color, Offset(i * slot + (slot - barWidth) / 2, size.height - h), Size(barWidth, h), CornerRadius(6f, 6f))
            }
            referenceValue?.let {
                val y = size.height - size.height * (it.toFloat() / max)
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
        }
        if (showLabels) {
            Row(Modifier.fillMaxWidth()) {
                entries.forEach { Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1) }
            }
        }
    }
}
