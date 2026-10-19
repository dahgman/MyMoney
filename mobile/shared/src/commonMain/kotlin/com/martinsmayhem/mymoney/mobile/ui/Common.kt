package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.Format

/** A top bar with a back button (if back is given), a title, and actions on the right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, back: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { if (back != null) TextButton(onClick = back) { Text("‹ Back") } },
        actions = { Row { actions() } },
    )
}

/** Money: red below zero. */
@Composable
fun Money(cents: Long, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(Format.money(cents), modifier = modifier, color = if (cents < 0) Negative else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
