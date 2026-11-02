package social.hotmess.android.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * Whether a newer Hot Mess is on Google Play, for the update rows in Me › About (as iOS's AboutUpdateRows, with
 * Google Play's in-app update check in place of the App Store and TestFlight). Checked when the app comes to the
 * front, at most once a day, and on "Check for updates". Only an app installed from Google Play has updates to
 * offer; a build installed any other way (Android Studio, adb, a download) shows no update rows.
 */
class AppUpdates(private val context: Context, private val scope: CoroutineScope) {
    /** Where the build came from. */
    enum class Source {
        GOOGLE_PLAY,
        OTHER,
    }

    sealed interface Status {
        data object Unknown : Status

        data object UpToDate : Status

        /** A newer build on Google Play: its version code. */
        data class Available(val versionCode: Int) : Status

        /** Google Play couldn't say. */
        data object Failed : Status
    }

    data class State(
        val status: Status = Status.Unknown,
        val lastChecked: Instant? = null,
        val checking: Boolean = false,
    )

    val source: Source = installSource(context)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val manager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(context) }

    private val _state = MutableStateFlow(State(lastChecked = prefs.getLong(LAST_CHECKED, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Looks once a day at most; the app calls it whenever it comes to the front. */
    fun checkIfDue(now: Instant = Instant.now()) {
        if (source != Source.GOOGLE_PLAY) return
        val last = _state.value.lastChecked
        if (last != null && _state.value.status != Status.Unknown && Duration.between(last, now) < CHECK_EVERY) return
        scope.launch { check() }
    }

    /** "Check for updates": asks Google Play now. */
    suspend fun check() {
        if (source != Source.GOOGLE_PLAY || _state.value.checking) return
        _state.update { it.copy(checking = true) }
        val status = try {
            val info = manager.requestAppUpdateInfo()
            when (info.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE,
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                -> Status.Available(info.availableVersionCode())
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> Status.UpToDate
                else -> Status.Unknown
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(checking = false) }
            throw e
        } catch (_: Exception) {
            Status.Failed
        }
        val now = Instant.now()
        if (status != Status.Failed) prefs.edit().putLong(LAST_CHECKED, now.toEpochMilli()).apply()
        _state.update { it.copy(status = status, checking = false, lastChecked = if (status != Status.Failed) now else it.lastChecked) }
    }

    /** "Open on Google Play": the app's store page, in the Play Store when it's there, else on the web. */
    fun openStore(from: Context) {
        val id = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).setPackage(PLAY_STORE)
        try {
            from.startActivity(market.addFlagsFor(from))
        } catch (_: ActivityNotFoundException) {
            from.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")).addFlagsFor(from))
        }
    }

    private fun Intent.addFlagsFor(from: Context): Intent =
        if (from is android.app.Activity) this else addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        private const val PREFS = "hotmess.updates"
        private const val LAST_CHECKED = "lastChecked"
        private const val PLAY_STORE = "com.android.vending"
        private val CHECK_EVERY: Duration = Duration.ofDays(1)

        fun installSource(context: Context): Source {
            val installer = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            } catch (_: Exception) {
                null
            }
            return if (installer == PLAY_STORE) Source.GOOGLE_PLAY else Source.OTHER
        }
    }
}
