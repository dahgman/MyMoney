package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.ReceiptImage

/** Take photo / Choose photo. */
@Composable
fun ReceiptButtons(platform: Platform, failed: (String) -> Unit, picked: (ReceiptImage) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { platform.pickReceipt(true, { it?.let(picked) }, failed) }, modifier = Modifier.weight(1f)) {
            Text("Take photo")
        }
        OutlinedButton(onClick = { platform.pickReceipt(false, { it?.let(picked) }, failed) }, modifier = Modifier.weight(1f)) {
            Text("Choose photo")
        }
    }
}

/** A row of receipt thumbnails; each label (like "Waiting to send") under its picture. */
@Composable
fun Thumbnails(items: List<Pair<ImageBitmap?, String?>>) {
    if (items.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        items(items) { (image, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    if (image != null) Image(image, "Receipt", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                    else Text("📄")
                }
                if (label != null) Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A stored thumbnail, read once. */
@Composable
fun storedThumbnail(phone: Phone, platform: Platform, receiptId: String): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, receiptId) {
        value = phone.thumbnail(receiptId)?.let(platform::imageBitmap)
    }
    return image
}
