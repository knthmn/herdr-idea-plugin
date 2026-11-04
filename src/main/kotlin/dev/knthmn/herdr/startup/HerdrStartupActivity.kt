package dev.knthmn.herdr.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.knthmn.herdr.core.HcStatusService

class HerdrStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        HcStatusService.getInstance().onStartup(project)
    }
}
