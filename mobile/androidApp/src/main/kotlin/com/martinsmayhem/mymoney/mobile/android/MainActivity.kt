package com.martinsmayhem.mymoney.mobile.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.martinsmayhem.mymoney.mobile.ReceiptImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Receipt photos: the camera app (into a file of ours), or the photo picker.
    private var receipt: ((ReceiptImage?) -> Unit)? = null
    private var receiptFailed: ((String) -> Unit)? = null
    private var photoFile: File? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile
        if (ok && f != null) prepare(Uri.fromFile(f)) { f.delete() } else { f?.delete(); receiptDone(null) }
    }
    private val choosePhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepare(uri) {} else receiptDone(null)
    }
    private val cameraAllowed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else receiptDone(null)
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
            .setPrompt("Scan the code on the computer (Sync > Settings > Pair a Phone)")
            .setBeepEnabled(false).setOrientationLocked(false))
    }

    override fun syncSoon() = MyMoneyPhone.syncSoon(this)

    override fun pickReceipt(camera: Boolean, result: (ReceiptImage?) -> Unit, failed: (String) -> Unit) {
        receipt = result
        receiptFailed = failed
        if (!camera) {
            choosePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            cameraAllowed.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val dir = File(cacheDir, "receipts").apply { mkdirs() }
        val f = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        photoFile = f
        takePhoto.launch(FileProvider.getUriForFile(this, "$packageName.receipts", f))
    }

    /** Shrinks and turns the photo off the main thread, then hands it over. */
    private fun prepare(uri: Uri, then: () -> Unit) {
        lifecycleScope.launch {
            val image = withContext(Dispatchers.Default) {
                try {
                    ReceiptImages.prepare(this@MainActivity, uri)
                } catch (e: Exception) {
                    null
                }
            }
            then()
            if (image == null) {
                receiptFailed?.invoke("That photo couldn't be read. Try another, or take it again.")
                receipt = null
            } else {
                receiptDone(image)
            }
        }
    }

    private fun receiptDone(image: ReceiptImage?) {
        receipt?.invoke(image)
        receipt = null
    }

    override fun imageBitmap(jpeg: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

    override fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    override val appVersion: String = BuildConfig.VERSION_NAME

    override fun systemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
