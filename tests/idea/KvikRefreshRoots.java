package io.heapy.kvik.probe;

import com.intellij.ide.CommandLineInspectionProjectConfigurator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtil;

/** Headless inspect refreshes its project directory; exported source roots can be outside it. */
public final class KvikRefreshRoots implements CommandLineInspectionProjectConfigurator {
    @Override public String getName() { return "kvik-fixture-roots"; }
    @Override public String getDescription() { return "Refresh the isolated fixture's imported source roots"; }
    @Override public boolean shouldBeInvokedAlongsideActivityTracking() { return true; }
    @Override public void configureProject(Project project, ConfiguratorContext context) {
        VfsUtil.markDirtyAndRefresh(false, true, true, ProjectRootManager.getInstance(project).getContentRoots());
    }
}
