package dev.knthmn.herdr.startup

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.knthmn.herdr.core.HcStatusService
import dev.knthmn.herdr.terminal.TerminalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HerdrStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val opener = TerminalOpener.getInstance(project)
        withContext(Dispatchers.EDT) { opener.closeDead() }
        HcStatusService.getInstance().onStartup(project)
    }
}
