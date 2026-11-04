package dev.knthmn.herdr.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class HerdrProjectSettingsState : BaseState() {
    var extraRoots by list<String>()
}

/** Settings of one project, kept in its `.idea/workspace.xml`. */
@Service(Service.Level.PROJECT)
@State(name = "HerdrProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HerdrProjectSettings :
    SimplePersistentStateComponent<HerdrProjectSettingsState>(HerdrProjectSettingsState()) {
    /** Folders whose terminals are in scope, in addition to the project and its worktrees. */
    var extraRoots: List<String>
        get() = state.extraRoots.toList()
        set(value) {
            state.extraRoots = value.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }

    companion object {
        fun getInstance(project: Project): HerdrProjectSettings = project.service()
    }
}
