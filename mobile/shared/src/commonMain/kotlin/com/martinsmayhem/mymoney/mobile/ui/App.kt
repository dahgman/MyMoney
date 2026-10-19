package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.Phone
import kotlinx.datetime.LocalDate

/** What only the platform can do: scan a QR code, sync in the background, know today's date. */
interface Platform {
    /** Opens the camera to scan a QR code; result gets its text, or null if cancelled. */
    fun scanQr(result: (String?) -> Unit)

    /** Asks for a sync soon, in the background (after a change is saved). */
    fun syncSoon()

    fun today(): LocalDate
}

/** The screens, as a stack: back pops one. */
sealed interface Screen {
    data object Pair : Screen
    data object Accounts : Screen
    data class Ledger(val accountUid: String) : Screen
    /** A new transaction (txnUid null), or changing one; enter: entering a scheduled one. */
    data class Edit(val accountUid: String?, val txnUid: String?, val enter: Boolean = false) : Screen
    data object Upcoming : Screen
    data object Sync : Screen
}

/** The screen stack. The Android back button calls back(); false means leave the app. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Accounts)
    val current get() = stack.last()

    fun go(s: Screen) {
        stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun home() {
        stack.clear()
        stack.add(Screen.Accounts)
    }
}

val Negative = Color(0xFFE0533F)
val Positive = Color(0xFF2EA65A)

@Composable
fun App(phone: Phone, platform: Platform, nav: Navigator) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF6EA8FE), background = Color(0xFF1E1F22),
            surface = Color(0xFF1E1F22)) else lightColorScheme(primary = Color(0xFF2E5BB8)),
    ) {
        Surface {
            val state by phone.state.collectAsState()
            val view = remember(state) { LedgerView(state) }
            if (state.pairing == null) {
                PairScreen(phone, platform) { nav.home() }
                return@Surface
            }
            when (val s = nav.current) {
                Screen.Pair, Screen.Accounts -> AccountsScreen(phone, view, platform, nav)
                is Screen.Ledger -> LedgerScreen(view, s.accountUid, nav)
                is Screen.Edit -> EditScreen(phone, view, platform, nav, s)
                Screen.Upcoming -> UpcomingScreen(view, nav)
                Screen.Sync -> SyncScreen(phone, view, nav)
            }
        }
    }
}
