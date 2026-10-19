package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A status as the computer's ledger draws it (TxnStatus): a colored circle,
 * a diamond while Reconciling, nothing for None.
 */
@Composable
fun StatusDot(status: String, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    val color = statusColor(status) ?: return
    val diamond = status == "I"
    Canvas(modifier.size(size)) {
        val outline = Color(color.red * 0.75f, color.green * 0.75f, color.blue * 0.75f)
        val r = this.size.minDimension / 2
        val c = Offset(this.size.width / 2, this.size.height / 2)
        if (diamond) {
            val p = Path().apply {
                moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
            }
            drawPath(p, color)
            drawPath(p, outline, style = Stroke(1.dp.toPx()))
        } else {
            drawCircle(color, r, c)
            drawCircle(outline, r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
    }
}

/** The computer's colors (TxnStatus); null for None (no dot). */
fun statusColor(status: String): Color? = when (status) {
    "R" -> Color(142, 68, 173)       // purple
    "C" -> Color(39, 174, 96)        // green
    "I" -> Color(243, 156, 18)       // orange diamond
    "P" -> Color(241, 196, 15)       // yellow
    "D" -> Color(230, 126, 34)       // orange
    "S" -> Color(52, 152, 219)       // blue
    else -> null
}
