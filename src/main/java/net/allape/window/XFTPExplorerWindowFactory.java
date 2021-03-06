package net.allape.window;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ui.content.*;
import net.allape.bus.Windows;
import net.allape.bus.Services;
import net.allape.window.explorer.XFTPExplorerWindow;
import org.jetbrains.annotations.NotNull;

public class XFTPExplorerWindowFactory implements ToolWindowFactory, DumbAware {

    public static final Logger logger = Logger.getInstance(XFTPExplorerWindowFactory.class);

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        Services.TOOL_WINDOW = toolWindow;
        toolWindow.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                XFTPWindow window = Windows.windows.get(event.getContent());
                if (window == null) {
                    logger.warn("closed an un-cached window: " + event);
                } else {
                    window.dispose();
                    Windows.windows.remove(event.getContent());
                }

                if (toolWindow.getContentManager().getContents().length == 0) {
                    toolWindow.hide();
                }
            }
        });
        if (toolWindow instanceof ToolWindowEx) {
            ((ToolWindowEx) toolWindow).setTabActions(new DumbAwareAction(() -> "New Explorer", () -> "Open a new explorer", AllIcons.General.Add) {
                public void actionPerformed(@NotNull AnActionEvent e) {
                    if (e.getProject() != null) {
                        XFTPExplorerWindowFactory.this.createToolWindowContent(e.getProject(), toolWindow);
                    } else {
                        Services.message("Explorer requires a project!", MessageType.INFO);
                    }
                }
            });
        }
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 默认创建一个XFTP窗口
        createTheToolWindowContent(project, toolWindow);
    }

    public static void createTheToolWindowContent(Project project, @NotNull ToolWindow toolWindow) {
        // window实例
        XFTPExplorerWindow window = new XFTPExplorerWindow(project, toolWindow);
        //获取内容工厂的实例
        ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
        //获取用于toolWindow显示的内容
        Content content = contentFactory.createContent(window.getUI(), Windows.WINDOW_DEFAULT_NAME, false);
        content.setCloseable(true);
        //给toolWindow设置内容
        ContentManager contentManager = toolWindow.getContentManager();
        contentManager.addContent(content);
        contentManager.setSelectedContent(content);

        window.setContent(content);

        // 放入缓存
        Windows.windows.put(content, window);
    }

}
