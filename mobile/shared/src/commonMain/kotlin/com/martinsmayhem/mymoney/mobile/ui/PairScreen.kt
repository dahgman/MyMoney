package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.MailboxException
import com.martinsmayhem.mymoney.mobile.PairingCode
import com.martinsmayhem.mymoney.mobile.Phone
import kotlinx.coroutines.launch

/**
 * Pairing with a computer: scan the code from Sync > Settings > Pair a
 * Phone (or paste it), then the NAS user name and password.
 */
@Composable
fun PairScreen(phone: Phone, platform: Platform, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<PairingCode?>(null) }
    var pasted by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun read(text: String) {
        try {
            code = phone.readCode(text)
            problem = null
        } catch (e: IllegalArgumentException) {
            problem = e.message
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MyMoney", style = MaterialTheme.typography.headlineMedium)
        val c = code
        if (c == null) {
            Text("Pair with your computer", style = MaterialTheme.typography.titleMedium)
            Text("On the computer, open MyMoney and choose Sync > Settings > Pair a Phone. Then scan the code it shows.")
            Button(onClick = { platform.scanQr { it?.let(::read) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Scan the code")
            }
            OutlinedTextField(pasted, { pasted = it }, label = { Text("Or paste the code's text") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedButton(onClick = { read(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Use pasted code")
            }
        } else {
            Text("Pairing with ${c.fileName.ifBlank { "your MyMoney file" }}", style = MaterialTheme.typography.titleMedium)
            Text("Mailbox: ${c.mailbox.url}${c.mailbox.path}")
            Text("Sign in to the NAS: best, a user allowed only into the mailbox folder.")
            OutlinedTextField(user, { user = it }, label = { Text("NAS user name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && user.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                problem = null
                scope.launch {
                    try {
                        phone.pair(c, user.trim(), password)     // the accounts screen then syncs
                        done()
                    } catch (e: MailboxException) {
                        problem = e.message
                    } catch (e: Exception) {
                        problem = "Couldn't reach the mailbox: ${e.message ?: e::class.simpleName}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Pairing…" else "Pair") }
            OutlinedButton(onClick = { code = null }, modifier = Modifier.fillMaxWidth()) { Text("Use another code") }
        }
        problem?.let { Text(it, color = Negative) }
    }
}
