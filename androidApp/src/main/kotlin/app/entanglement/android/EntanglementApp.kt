package app.entanglement.android

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.entanglement.android.data.PhoneStore
import app.entanglement.android.data.Repository
import app.entanglement.android.data.SyncScheduler
import app.entanglement.shared.sync.HttpRemoteApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

class EntanglementApp : Application() {

    val repository: Repository by lazy {
        Repository(
            store = PhoneStore(filesDir),
            apiFactory = { cfg -> HttpRemoteApi(http, cfg.serverUrl, cfg.token) },
            scheduler = WorkScheduler(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WorkScheduler(this).schedulePeriodic()
    }
}

/** Background sync via WorkManager: runs when there is network, also after the app was closed. */
class WorkScheduler(private val context: Context) : SyncScheduler {

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** A few seconds after the last edit (each edit pushes it back), or as soon as there is network. */
    override fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-soon", ExistingWorkPolicy.REPLACE, request)
    }

    /** Every 15 minutes (Android's minimum), to fetch what other observers wrote. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = (applicationContext as EntanglementApp).repository.syncAll()
        return if (ok || runAttemptCount >= 5) Result.success() else Result.retry()
    }
}
