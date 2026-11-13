package dev.knthmn.herdr.core

import com.intellij.util.EnvironmentUtil

/** Starts `hc` with the login shell environment, which a core it starts passes to its terminals. */
internal object HcProcess {
    fun builder(command: List<String>): ProcessBuilder =
        ProcessBuilder(command).apply { environment().putAll(EnvironmentUtil.getEnvironmentMap()) }
}
