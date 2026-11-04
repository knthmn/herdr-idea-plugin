package dev.knthmn.herdr.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.knthmn.herdr.core.HcClient
import dev.knthmn.herdr.core.HcLocator
import dev.knthmn.herdr.model.Launchers
import dev.knthmn.herdr.ui.ProgramIcons
import javax.swing.Icon
import javax.swing.JList

class HerdrConfigurable : BoundConfigurable("Herdr") {
    private val settings = HerdrSettings.getInstance()

    private data class Choice(val key: String, val name: String, val icon: Icon)

    override fun apply() {
        val hcPath = settings.hcPath
        super.apply()
        if (settings.hcPath != hcPath) HcClient.getInstance().restart()
        settings.changed()
    }

    override fun createPanel(): DialogPanel {
        val choices =
            listOf(Choice("", "Terminal", AllIcons.Nodes.Console)) +
                Launchers.knownAgents().map {
                    Choice(
                        it.program.key,
                        if (it.installed) it.program.name else "${it.program.name} (not installed)",
                        ProgramIcons.of(it.program),
                    )
                }
        return panel {
            row("hc executable:") {
                textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.singleFile().withTitle("Select hc Executable")
                    )
                    .bindText(settings::hcPath)
                    .align(AlignX.FILL)
                    .comment("Leave empty to search PATH, then ${HcLocator.DEFAULT_PATH_HINT}.")
            }
            row("Default agent:") {
                comboBox(
                        choices,
                        ChoiceRenderer(),
                    )
                    .bindItem(
                        { choices.find { it.key == settings.defaultAgent } ?: choices.first() },
                        { settings.defaultAgent = it?.key.orEmpty() },
                    )
                    .comment("Started by the first button of the sidebar toolbar.")
            }
            sidebarGroup()
            labelsGroup()
        }
    }

    private fun Panel.sidebarGroup() {
        group("Sidebar") {
            row {
                checkBox("Show the folder or worktree branch").bindSelected(settings::showLocation)
            }
            row {
                checkBox("Show the time since the last status change")
                    .bindSelected(settings::showAge)
            }
            row {
                checkBox("Double-click or Enter on a resumable row resumes it")
                    .bindSelected(settings::resumeOnClick)
                    .comment(
                        "When off, it opens a live terminal and does nothing for a finished one."
                    )
            }
            row {
                checkBox("Confirm before Finish and Delete").bindSelected(settings::confirmEnd)
            }
        }
    }

    private fun Panel.labelsGroup() {
        group("Labels") {
            row {
                comment(
                    "Terminal label keys that the plugin reads. Leave a key empty to turn " +
                        "its feature off. The agent integrations in docs/ write the default " +
                        "keys <code>resume</code>, <code>title</code> and <code>blocked</code>."
                )
            }
            row("Resume command:") {
                textField().bindText(settings::resumeLabel).comment("The command that Resume runs.")
            }
            row("Title:") {
                textField().bindText(settings::titleLabel).comment("The row and tab name.")
            }
            row("Blocked question:") {
                textField()
                    .bindText(settings::blockedLabel)
                    .comment("Shows a live agent as blocked, with the value as its message.")
            }
        }
    }

    private class ChoiceRenderer : SimpleListCellRenderer<Choice>() {
        override fun customize(
            list: JList<out Choice>,
            value: Choice?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = value?.icon
            text = value?.name.orEmpty()
        }
    }
}
