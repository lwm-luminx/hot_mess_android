package social.hotmess.android.support

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.audiencekit.GraphQLCachePolicy
import com.audiencekit.RefreshBackoff
import com.audiencekit.VisitedLocales
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import social.hotmess.android.appGraph
import social.hotmess.core.AppLocale
import social.hotmess.core.HotMessApi

/**
 * Keeps what the screens read fresh in the on-device GraphQL cache, so a flaky connection or a failing API shows the
 * last good data instead of an error.
 *
 * Which cities to refresh comes from the locales the person has been in lately ([VisitedLocales]), kept on the phone
 * only and never sent. It runs as an hourly JobScheduler job that needs a network ([CacheRefreshJobService]) and when
 * the app comes to the front. After a failure it waits longer each time ([RefreshBackoff], 15 minutes doubling to 6
 * hours) and never retries in a loop; the cache keeps serving meanwhile.
 */
class CacheRefresher(
    private val context: Context,
    private val api: HotMessApi,
    val visited: VisitedLocales,
    private val isSignedIn: () -> Boolean,
) {
    private val mutex = Mutex()
    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var backoff: RefreshBackoff
        get() = preferences.getString(BACKOFF_KEY, null)
            ?.let { runCatching { Json.decodeFromString(RefreshBackoff.serializer(), it) }.getOrNull() }
            ?: RefreshBackoff()
        set(value) {
            preferences.edit().putString(BACKOFF_KEY, Json.encodeToString(RefreshBackoff.serializer(), value)).apply()
        }

    /** Notes that the person is in [locale], so its data is kept fresh. */
    fun recordVisit(locale: AppLocale) {
        visited.record(locale.id, locale.name)
    }

    /**
     * Refreshes the cities visited lately when the backoff says it's due (or [force]). A refresh already running is
     * waited for, not repeated. True when it succeeded.
     */
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        if (!isSignedIn() || !(force || backoff.isDue())) return@withLock false
        val locales = visited.recent().take(LOCALE_LIMIT)
        if (locales.isEmpty()) return@withLock false

        var succeeded = true
        for (locale in locales) {
            try {
                api.events(locale.id, cachePolicy = GraphQLCachePolicy.Refresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                succeeded = false
                Log.w(TAG, "Cache refresh failed: ${e.message}")
                // The API is failing: stop here rather than ask again.
                break
            }
        }
        // The Venues screen's first pages (each city's, and Everywhere's), not the audience's whole list: the screen
        // draws them at once and pages in the rest itself.
        if (succeeded) {
            for (locale in locales) {
                runCatching { api.venuesPage(locale.id, cachePolicy = GraphQLCachePolicy.Refresh) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            runCatching { api.venuesPage(null, cachePolicy = GraphQLCachePolicy.Refresh) }
                .onFailure { if (it is CancellationException) throw it }
        }
        backoff = if (succeeded) backoff.succeeded() else backoff.failed()
        succeeded
    }

    /** Asks JobScheduler for an hourly refresh while a network is up. Each run checks the backoff first. */
    fun schedule() {
        if (!isSignedIn()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CacheRefreshJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(JOB_INTERVAL_MILLIS)
            .build()
        runCatching { scheduler.schedule(job) }.onFailure { Log.w(TAG, "Couldn't schedule the cache refresh: ${it.message}") }
    }

    /** Forgets the cities and the backoff on sign-out, and stops the job. The SDK empties the cache itself. */
    fun forget() {
        visited.removeAll()
        preferences.edit().remove(BACKOFF_KEY).apply()
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    companion object {
        private const val TAG = "CacheRefresher"
        private const val PREFERENCES = "cache-refresh"
        private const val BACKOFF_KEY = "backoff"
        private const val LOCALE_LIMIT = 3
        const val JOB_ID = 0x4d455353
        private const val JOB_INTERVAL_MILLIS = 60L * 60 * 1000
    }
}

/** JobScheduler's way into [CacheRefresher]. */
class CacheRefreshJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = applicationContext.appGraph
        job = graph.scope.launch {
            val succeeded = graph.cacheRefresher.refresh()
            // A failure is retried on the next hourly run once the backoff allows, not rescheduled sooner.
            jobFinished(params, false)
            Log.i("CacheRefresher", "Background refresh ${if (succeeded) "done" else "skipped or failed"}")
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return false
    }
}
