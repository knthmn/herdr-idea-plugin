package dev.knthmn.herdr.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.ui.UIUtil
import dev.knthmn.herdr.core.HcClient
import dev.knthmn.herdr.core.HcException
import dev.knthmn.herdr.core.HcHealth
import dev.knthmn.herdr.core.HcInstaller
import dev.knthmn.herdr.core.HcLocator
import dev.knthmn.herdr.core.HcReleases
import dev.knthmn.herdr.core.HcStatusService
import dev.knthmn.herdr.core.HcVersion
import java.net.URI
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The herdr-core group of the settings: hc path, installed version, install and update. */
internal class CoreSection(private val settings: HerdrSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = Dispatchers.EDT + ModalityState.any().asContextElement()
    private var refreshJob: Job? = null
    private var installing = false

    private lateinit var status: JLabel
    private lateinit var hint: JLabel
    private lateinit var installButton: JButton
    private lateinit var checkButton: JButton

    fun build(panel: Panel) {
        panel.group("herdr-core") {
            row("hc executable:") {
                textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withTitle("Select hc Executable")
                    )
                    .bindText(settings::hcPath)
                    .align(AlignX.FILL)
                    .comment(
                        "Leave empty to search PATH, then ${HcLocator.DEFAULT_PATH_HINT} " +
                            "and ${HcLocator.INSTALL_DIR_HINT}/herdr-core."
                    )
            }
            row("Installed:") {
                status = label("Checking…").component
                installButton = button("Install") { install() }.component
                checkButton = button("Check Now") { refresh(force = true) }.component
            }
            row("") {
                hint =
                    label("")
                        .applyToComponent { foreground = UIUtil.getContextHelpForeground() }
                        .component
            }
            row {
                checkBox(
                        "Check for herdr-core updates every ${HcReleases.CHECK_INTERVAL.inWholeHours} hours"
                    )
                    .bindSelected(settings::checkUpdates)
                    .comment(
                        "Shows a notification when a new release is out. A missing or too old " +
                            "hc is always shown."
                    )
            }
        }
        refresh(force = false)
    }

    fun dispose() {
        scope.cancel()
    }

    /** Reads the installed version and, if allowed or [force]d, the latest release. */
    fun refresh(force: Boolean) {
        refreshJob?.cancel()
        if (force) status.text = "Checking…"
        refreshJob = scope.launch {
            val health = HcStatusService.getInstance().health()
            val check = force || settings.checkUpdates
            val latest = if (check) HcReleases.getInstance().latest(force) else null
            withContext(ui) { render(health, latest, check) }
        }
    }

    private fun render(health: HcHealth, latest: HcVersion?, checked: Boolean) {
        val (installed, path) =
            when (health) {
                HcHealth.Missing -> null to null
                is HcHealth.Unsupported -> health.version to health.hc
                is HcHealth.Usable -> health.version to health.hc
            }
        val managed = path == null || HcLocator.isManaged(path)
        val customMissing = path == null && settings.hcPath.isNotBlank()
        status.text = statusText(health, installed, path, latest, checked)
        val needsInstall = health !is HcHealth.Usable || HcVersion.isUpdate(installed, latest)
        installButton.text = if (path == null) "Install" else "Update"
        installButton.isVisible = needsInstall
        installButton.isEnabled = !installing && managed && !customMissing
        checkButton.isEnabled = !installing
        hint.text = hintText(managed, customMissing)
    }

    private fun hintText(managed: Boolean, customMissing: Boolean): String =
        when {
            customMissing ->
                "No hc at the path above. Clear it to use an install in ${HcLocator.INSTALL_DIR_HINT}."
            !managed ->
                "This hc is not in ${HcLocator.INSTALL_DIR_HINT}. Update it the way you installed it."
            else ->
                "Install and Update run the official installer into " +
                    "${HcLocator.INSTALL_DIR_HINT}."
        }

    private fun statusText(
        health: HcHealth,
        installed: HcVersion?,
        path: Path?,
        latest: HcVersion?,
        checked: Boolean,
    ): String {
        if (path == null) {
            return "Not installed." + (latest?.let { " The latest release is $it." } ?: "")
        }
        val where =
            "${installed ?: "Unknown version"} at ${HcLocator.abbreviateHome(path.toString())}."
        val support =
            if (health is HcHealth.Unsupported) " The plugin needs ${HcVersion.MINIMUM} or later."
            else ""
        val release =
            when {
                HcVersion.isUpdate(installed, latest) -> " $latest is available."
                latest != null -> " Up to date."
                checked -> " Cannot reach ${URI(HcReleases.LATEST_URL).host}."
                else -> ""
            }
        return where + support + release
    }

    private fun install() {
        installing = true
        installButton.isEnabled = false
        checkButton.isEnabled = false
        status.text = "Installing…"
        object : Task.Backgroundable(null, "Installing herdr-core", true) {
                override fun run(indicator: ProgressIndicator) {
                    var result: Result<String> =
                        Result.failure(HcException("cancelled", "The install was cancelled"))
                    try {
                        result =
                            try {
                                Result.success(HcInstaller.install(indicator))
                            } catch (e: HcException) {
                                Result.failure(e)
                            }
                        result.onSuccess {
                            HcClient.getInstance().restart()
                            HcStatusService.getInstance().reset()
                        }
                    } finally {
                        val outcome = result
                        ApplicationManager.getApplication()
                            .invokeLater({ finished(outcome) }, ModalityState.any())
                    }
                }
            }
            .queue()
    }

    private fun finished(result: Result<String>) {
        installing = false
        val group =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(HcStatusService.NOTIFICATIONS)
        result
            .onSuccess {
                group
                    .createNotification(
                        StringUtil.escapeXmlEntities(it),
                        NotificationType.INFORMATION,
                    )
                    .notify(null)
            }
            .onFailure {
                group
                    .createNotification(
                        "Cannot install herdr-core",
                        StringUtil.escapeXmlEntities(it.message.orEmpty()),
                        NotificationType.ERROR,
                    )
                    .notify(null)
            }
        refresh(force = false)
    }
}
