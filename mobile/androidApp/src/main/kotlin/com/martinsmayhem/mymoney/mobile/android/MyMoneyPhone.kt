package com.martinsmayhem.mymoney.mobile.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.WebDavMailbox
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** The one Phone for the app: the screens and the background sync share it. */
object MyMoneyPhone {

    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
            }
        }
    }
    private var phone: Phone? = null
    private val loading = Mutex()
    /** The mailbox, kept while the app runs so it remembers what it learns (folders there, MOVE refused). */
    private var mailbox: Pair<List<String>, WebDavMailbox>? = null

    @Synchronized
    private fun mailboxFor(url: String, path: String, user: String, password: String): WebDavMailbox {
        val key = listOf(url, path, user, password)
        mailbox?.let { (k, m) -> if (k == key) return m }
        return WebDavMailbox(http, url, path, user, password).also { mailbox = key to it }
    }

    /** The Phone, its state loaded. */
    suspend fun get(context: Context): Phone = loading.withLock {
        phone ?: Phone(AndroidStore(context.applicationContext)) { code, user, password ->
            mailboxFor(code.mailbox.url, code.mailbox.path, user, password)
        }.also {
            it.load()
            phone = it
        }
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Syncs about every 15 minutes (Android's shortest), whenever there's a network. */
    fun scheduleBackground(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build())
    }

    /** Syncs as soon as there's a network (after a change is saved). */
    fun syncSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build())
    }
}

/** A sync in the background. A failure is kept (and shown) and tried again next time. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val phone = MyMoneyPhone.get(applicationContext)
        if (phone.state.value.pairing == null) return Result.success()
        phone.sync()
        return Result.success()
    }
}
