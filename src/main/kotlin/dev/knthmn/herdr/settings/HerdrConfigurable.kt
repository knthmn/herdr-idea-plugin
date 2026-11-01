package dev.knthmn.herdr.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.knthmn.herdr.core.HcClient
import dev.knthmn.herdr.core.HcLocator

class HerdrConfigurable : BoundConfigurable("Herdr") {
    private val settings = HerdrSettings.getInstance()

    override fun apply() {
        super.apply()
        HcClient.getInstance().restart()
    }

    override fun createPanel(): DialogPanel = panel {
        row("hc executable:") {
            textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile().withTitle("Select hc Executable")
                )
                .bindText(settings::hcPath)
                .align(AlignX.FILL)
                .comment("Leave empty to search PATH, then ${HcLocator.DEFAULT_PATH_HINT}.")
        }
    }
}
