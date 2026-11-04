package dev.knthmn.herdr.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import dev.knthmn.herdr.model.WorktreeRoots

class HerdrProjectConfigurable(private val project: Project) : BoundConfigurable("Scope") {
    private val settings = HerdrProjectSettings.getInstance(project)
    private val roots = CollectionListModel<String>()

    override fun apply() {
        super.apply()
        WorktreeRoots.getInstance(project).refresh()
    }

    override fun createPanel(): DialogPanel {
        val list = JBList(roots).apply { emptyText.text = "No extra folders" }
        val decorator =
            ToolbarDecorator.createDecorator(list)
                .setAddAction {
                    val descriptor =
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Select Folder")
                    FileChooser.chooseFile(descriptor, project, null)?.let { folder ->
                        if (folder.path !in roots.items) roots.add(folder.path)
                    }
                }
                .disableUpDownActions()
        return panel {
            row {
                comment(
                    "Terminals in the project folder and in the other checkouts of its git " +
                        "repository are always in scope. Terminals in these folders are in " +
                        "scope too. Kept in .idea/workspace.xml."
                )
            }
            row { cell(decorator.createPanel()).align(Align.FILL) }.resizableRow()
            onApply { settings.extraRoots = roots.items }
            onReset { roots.replaceAll(settings.extraRoots) }
            onIsModified { roots.items != settings.extraRoots }
        }
    }
}
