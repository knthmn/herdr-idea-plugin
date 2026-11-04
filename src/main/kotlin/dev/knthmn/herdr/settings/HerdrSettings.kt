package dev.knthmn.herdr.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.knthmn.herdr.model.LabelKeys
import dev.knthmn.herdr.model.SortOrder
import dev.knthmn.herdr.model.ViewOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HerdrSettingsState : BaseState() {
    var hcPath by string("")
    var defaultAgent by string("")
    var resumeLabel by key(LabelKeys.DEFAULT.resume)
    var titleLabel by key(LabelKeys.DEFAULT.title)
    var blockedLabel by key(LabelKeys.DEFAULT.blocked)
    var confirmEnd by property(true)
    var resumeOnClick by property(true)
    var showLocation by property(true)
    var showAge by property(true)
    var groupByFolder by property(false)
    var sortOrder by string(SortOrder.RECENT.name)

    private fun key(default: String) = property(default) { it == default }
}

@Service(Service.Level.APP)
@State(
    name = "HerdrSettings",
    storages = [Storage("herdr.xml", roamingType = RoamingType.DISABLED)],
)
class HerdrSettings : SimplePersistentStateComponent<HerdrSettingsState>(HerdrSettingsState()) {
    private val revision = MutableStateFlow(0L)

    /** Changes after each load or apply of the settings. */
    val changes: StateFlow<Long> = revision.asStateFlow()

    var hcPath: String
        get() = state.hcPath.orEmpty()
        set(value) {
            state.hcPath = value.trim()
        }

    /** The program key of the agent that New Session starts. Empty for a login shell. */
    var defaultAgent: String
        get() = state.defaultAgent.orEmpty()
        set(value) {
            state.defaultAgent = value
        }

    var resumeLabel: String
        get() = state.resumeLabel
        set(value) {
            state.resumeLabel = value.trim()
        }

    var titleLabel: String
        get() = state.titleLabel
        set(value) {
            state.titleLabel = value.trim()
        }

    var blockedLabel: String
        get() = state.blockedLabel
        set(value) {
            state.blockedLabel = value.trim()
        }

    var confirmEnd: Boolean
        get() = state.confirmEnd
        set(value) {
            state.confirmEnd = value
        }

    var resumeOnClick: Boolean
        get() = state.resumeOnClick
        set(value) {
            state.resumeOnClick = value
        }

    var showLocation: Boolean
        get() = state.showLocation
        set(value) {
            state.showLocation = value
        }

    var showAge: Boolean
        get() = state.showAge
        set(value) {
            state.showAge = value
        }

    /** Sidebar view option. Call [changed] after a change. */
    var groupByFolder: Boolean
        get() = state.groupByFolder
        set(value) {
            state.groupByFolder = value
        }

    /** Sidebar view option. Call [changed] after a change. */
    var sortOrder: SortOrder
        get() = SortOrder.entries.find { it.name == state.sortOrder } ?: SortOrder.RECENT
        set(value) {
            state.sortOrder = value.name
        }

    val viewOptions: ViewOptions
        get() = ViewOptions(groupByFolder, sortOrder)

    val labelKeys: LabelKeys
        get() = LabelKeys(resumeLabel, titleLabel, blockedLabel)

    override fun loadState(state: HerdrSettingsState) {
        super.loadState(state)
        changed()
    }

    fun changed() {
        revision.value++
    }

    companion object {
        fun getInstance(): HerdrSettings = service()
    }
}
