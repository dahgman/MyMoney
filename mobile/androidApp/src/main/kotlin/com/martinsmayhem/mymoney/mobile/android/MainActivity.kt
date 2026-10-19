package com.martinsmayhem.mymoney.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.graphics.Color
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.ui.App
import com.martinsmayhem.mymoney.mobile.ui.Navigator
import com.martinsmayhem.mymoney.mobile.ui.Platform
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

class MainActivity : ComponentActivity(), Platform {

    private val nav = Navigator()
    private var scanned: ((String?) -> Unit)? = null
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        scanned?.invoke(result.contents)
        scanned = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!nav.back()) finish()
            }
        })
        lifecycleScope.launch {
            val phone: Phone = MyMoneyPhone.get(this@MainActivity)
            setContent { App(phone, this@MainActivity, nav) }
            if (phone.state.value.pairing != null) MyMoneyPhone.scheduleBackground(this@MainActivity)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {                    // opening the app: catch up
            val phone = MyMoneyPhone.get(this@MainActivity)
            if (phone.state.value.pairing != null) {
                MyMoneyPhone.scheduleBackground(this@MainActivity)
                phone.sync()
            }
        }
    }

    override fun scanQr(result: (String?) -> Unit) {
        scanned = result
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the code on the computer (File > Sync Settings > Pair a Phone)")
            .setBeepEnabled(false).setOrientationLocked(false))
    }

    override fun syncSoon() = MyMoneyPhone.syncSoon(this)

    override fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun systemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
