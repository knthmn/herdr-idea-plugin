package dev.knthmn.herdr.model

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReadStateData : BaseState() {
    var readAt by map<String, Long>()
}

/** When each hc terminal was last looked at, in ms since the epoch, kept across restarts. */
@Service(Service.Level.APP)
@State(
    name = "HerdrReadState",
    storages = [Storage("herdr.xml", roamingType = RoamingType.DISABLED)],
)
class ReadState : SimplePersistentStateComponent<ReadStateData>(ReadStateData()) {
    private val flow = MutableStateFlow<Map<String, Long>>(emptyMap())
    val readAt: StateFlow<Map<String, Long>> = flow.asStateFlow()

    override fun loadState(state: ReadStateData) {
        super.loadState(state)
        flow.value = state.readAt.toMap()
    }

    @Synchronized
    fun markRead(address: String, atMs: Long = System.currentTimeMillis()) {
        if ((state.readAt[address] ?: 0) >= atMs) return
        state.readAt = (state.readAt + (address to atMs)).toMutableMap()
        flow.value = state.readAt.toMap()
    }

    /** Forgets terminals that no longer exist. */
    @Synchronized
    fun retain(addresses: Set<String>) {
        if (state.readAt.keys.all { it in addresses }) return
        state.readAt = state.readAt.filterKeys { it in addresses }.toMutableMap()
        flow.value = state.readAt.toMap()
    }

    companion object {
        fun getInstance(): ReadState = service()
    }
}
