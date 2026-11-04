package dev.knthmn.herdr.core

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.knthmn.herdr.settings.HerdrConfigurable
import dev.knthmn.herdr.settings.HerdrSettings
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tells the user when herdr-core is missing, too old or has an update. All projects share it, so
 * several windows or tool windows at once give one notification.
 */
@Service(Service.Level.APP)
class HcStatusService(private val cs: CoroutineScope) {
    private val mutex = Mutex()
    private var blocking: Notification? = null
    private var blockingShownAt = 0L
    private var update: Notification? = null
    private var startupDone = false
    private var updateChecked = false

    suspend fun health(): HcHealth =
        withContext(Dispatchers.IO) { HcLocator.health(HerdrSettings.getInstance().hcPath) }

    /** At project open. Shows a missing or too old hc once per IDE session. */
    fun onStartup(project: Project) {
        cs.launch { check(project, startup = true) }
    }

    /** When the user opens the sidebar or a terminal, or starts a session. */
    fun onUse(project: Project) {
        cs.launch { check(project, startup = false) }
    }

    /** True when hc can run. Otherwise tells the user why not. */
    suspend fun ensureUsable(project: Project): Boolean {
        val health = health()
        if (health is HcHealth.Usable) return true
        mutex.withLock { showBlocking(project, health) }
        return false
    }

    /** After an install or update from the settings. */
    fun reset() {
        cs.launch {
            mutex.withLock {
                blocking?.expire()
                update?.expire()
                blocking = null
                update = null
                updateChecked = false
            }
        }
    }

    private suspend fun check(project: Project, startup: Boolean) = mutex.withLock {
        val firstStartup = startup && !startupDone
        if (startup) startupDone = true
        when (val health = health()) {
            is HcHealth.Usable -> {
                blocking?.expire()
                blocking = null
                checkUpdate(project, health)
            }
            else -> if (!startup || firstStartup) showBlocking(project, health)
        }
    }

    private suspend fun checkUpdate(project: Project, health: HcHealth.Usable) {
        val settings = HerdrSettings.getInstance()
        if (updateChecked || !settings.checkUpdates) return
        val installed = health.version ?: return
        val latest = HcReleases.getInstance().latest() ?: return
        updateChecked = true
        if (!HcVersion.isUpdate(installed, latest, settings.skippedVersion)) return
        update =
            notification(
                    "herdr-core $latest is available",
                    "You have $installed. Update it in the settings.",
                    NotificationType.INFORMATION,
                )
                .addAction(
                    NotificationAction.createSimpleExpiring("Skip this version") {
                        settings.skippedVersion = latest.toString()
                    }
                )
                .also { it.notify(project) }
    }

    private fun showBlocking(project: Project, health: HcHealth) {
        if (isVisible(blocking)) return
        val (title, content) =
            when (health) {
                is HcHealth.Unsupported ->
                    "herdr-core ${health.version} is too old" to
                        "The plugin needs ${HcVersion.MINIMUM} or later. Update it in the settings."
                else ->
                    "herdr-core is not installed" to
                        "The Herdr sidebar and terminal tabs need hc. Install it in the settings."
            }
        blocking?.expire()
        blockingShownAt = System.currentTimeMillis()
        blocking =
            notification(title, content, NotificationType.WARNING).also {
                it.notify(project)
            }
    }

    private fun isVisible(notification: Notification?): Boolean {
        if (notification == null || notification.isExpired) return false
        val balloon = notification.balloon
        return if (balloon == null) {
            System.currentTimeMillis() - blockingShownAt < BALLOON_GRACE.inWholeMilliseconds
        } else {
            !balloon.isDisposed
        }
    }

    private fun notification(
        title: String,
        content: String,
        type: NotificationType,
    ): Notification =
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATIONS)
            .createNotification(title, content, type)
            .addAction(
                NotificationAction.createExpiring("Open Herdr settings") { e, _ ->
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(e.project, HerdrConfigurable::class.java)
                }
            )

    companion object {
        const val NOTIFICATIONS = "Herdr"
        private val BALLOON_GRACE = 5.seconds

        fun getInstance(): HcStatusService = service()
    }
}
