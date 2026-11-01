package dev.knthmn.herdr.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

class HerdrSettingsState : BaseState() {
    var hcPath by string("")
}

@Service(Service.Level.APP)
@State(
    name = "HerdrSettings",
    storages = [Storage("herdr.xml", roamingType = RoamingType.DISABLED)],
)
class HerdrSettings : SimplePersistentStateComponent<HerdrSettingsState>(HerdrSettingsState()) {
    var hcPath: String
        get() = state.hcPath.orEmpty()
        set(value) {
            state.hcPath = value.trim()
        }

    companion object {
        fun getInstance(): HerdrSettings = service()
    }
}
