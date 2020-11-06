package net.allape.windows;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.remote.RemoteConnectionType;
import com.intellij.ssh.ConnectionBuilder;
import com.intellij.ssh.RemoteCredentialsUtil;
import com.intellij.ssh.RemoteFileObject;
import com.intellij.ssh.SftpProgressTracker;
import com.intellij.ssh.channels.SftpChannel;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.DarculaColors;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.ContentManagerEvent;
import com.jetbrains.plugins.remotesdk.console.RemoteDataProducer;
import net.allape.bus.Data;
import net.allape.bus.Services;
import net.allape.dialogs.Confirm;
import net.allape.models.FileModel;
import net.allape.models.FileTableModel;
import net.allape.models.Transfer;
import net.allape.utils.Maps;
import org.jetbrains.annotations.NotNull;
import org.omg.CORBA.TRANSACTION_MODE;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class XFTPExplorerWindow extends XFTPWindow {

    private static final int COLLECTION_SIZE = 100;

    // region UI objects

    private JPanel panel;

    private JScrollPane localFs;
    private JTextField localFsPath;
    private JList<FileModel> localFiles;

    private JScrollPane remoteFs;
    private JTextField remoteFsPath;
    private JPanel remoteFsWrapper;
    private JPanel sshConfigCBWrapper;
    private JButton exploreButton;
    private JButton disconnectButton;
    private JTable remoteFiles;

    // endregion

    // 上一次选择文件
    private FileModel lastFile = null;

    // 当前选中的本地文件
    private FileModel currentLocalFile = null;
    // 当前选中的所有文件
    private List<FileModel> selectedLocalFiles = new ArrayList<>(COLLECTION_SIZE);

    // 当前开启的channel
    private SftpChannel sftpChannel = null;
    // 当前选中的远程文件
    private FileModel currentRemoteFile = null;
    // 当前选中的所有文件
    private List<FileModel> selectedRemoteFiles = new ArrayList<>(COLLECTION_SIZE);

    // 修改中的远程文件, 用于文件修改后自动上传 key: remote file, value: local file
    private Map<RemoteFileObject, String> remoteEditingFiles = new HashMap<>(COLLECTION_SIZE);

    public XFTPExplorerWindow(Project project, ToolWindow toolWindow) {
        super(project, toolWindow);
        this.initUIStyle();
        this.initUIAction();

        this.loadLocal(USER_HOME);

        final XFTPExplorerWindow self = XFTPExplorerWindow.this;

        // 初始化文件监听
        if (this.project != null) {
            this.project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                @Override
                public void after(@NotNull List<? extends VFileEvent> events) {
                    for (VFileEvent e : events) {
                        if (e.isFromSave()) {
                            VirtualFile virtualFile = e.getFile();
                            if (virtualFile != null) {
                                String localFile = virtualFile.getPath();
                                RemoteFileObject remoteFile = Maps.getFirstKeyByValue(self.remoteEditingFiles, localFile);
                                if (remoteFile != null) {
                                    // 上传文件
                                    self.uploadFile(localFile, remoteFile);
                                }
                            }
                        }
                    }
                }
            });
        } else {
            Services.message("Failed to initial file change listener", MessageType.ERROR);
        }
    }

    @Override
    public void onClosed(ContentManagerEvent e) {
        super.onClosed(e);

        // 关闭连接
        this.disconnect(true);
    }

    /**
     * 初始化UI样式
     */
    @SuppressWarnings("unused")
    private void initUIStyle () {
        this.setDefaultTheme(this.panel);

        this.setDefaultTheme(this.localFs);
        this.localFs.setBorder(null);
        this.setDefaultTheme(this.localFsPath);
        this.setDefaultTheme(this.localFiles);
        this.localFiles.setSelectionBackground(JBColor.namedColor(
                "Plugins.lightSelectionBackground",
                DarculaColors.BLUE
        ));
        this.localFiles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        this.setDefaultTheme(this.remoteFs);
        this.remoteFs.setBorder(null);
        this.setDefaultTheme(this.remoteFsWrapper);
        this.setDefaultTheme(this.remoteFsPath);
        this.setDefaultTheme(this.sshConfigCBWrapper);
        this.setDefaultTheme(this.remoteFiles);
        this.setDefaultTheme(this.exploreButton);
        this.setDefaultTheme(this.disconnectButton);
        this.remoteFiles.setSelectionBackground(JBColor.namedColor(
                "Plugins.lightSelectionBackground",
                DarculaColors.BLUE
        ));
        this.remoteFiles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        this.remoteFiles.setAutoCreateRowSorter(false);
        this.remoteFiles.setBorder(null);
    }

    /**
     * 初始化UI行为
     */
    @SuppressWarnings("unused")
    private void initUIAction () {
        this.localFiles.setCellRenderer(new XFTPExplorerWindowListCellRenderer());
        // 设置当前选中的内容
        this.localFiles.addListSelectionListener(e -> {
            this.selectedLocalFiles = this.localFiles.getSelectedValuesList();
            this.lastFile = this.currentLocalFile;
            this.currentLocalFile = this.localFiles.getSelectedValue();
        });
        // 监听双击, 双击后打开文件或文件夹
        this.localFiles.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {
                final XFTPExplorerWindow self = XFTPExplorerWindow.this;
                long now = System.currentTimeMillis();
                if (self.lastFile == self.currentLocalFile && now - self.clickWatcher < DOUBLE_CLICK_INTERVAL) {
                    self.loadLocal(self.currentLocalFile.getPath());
                }
                self.clickWatcher = now;
            }

            @Override
            public void mousePressed(MouseEvent e) {

            }

            @Override
            public void mouseReleased(MouseEvent e) {

            }

            @Override
            public void mouseEntered(MouseEvent e) {

            }

            @Override
            public void mouseExited(MouseEvent e) {

            }
        });

        // 弹出的时候获取ssh配置
        this.exploreButton.addActionListener(e -> this.connectSftp());
        this.disconnectButton.addActionListener(e -> {
            DialogWrapper dialog = new Confirm(new Confirm.Options()
                    .title("Disconnecting")
                    .content("Do you really want to close this session?")
            );
            if (dialog.showAndGet()) {
                this.disconnect(true);
            }
        });
        this.remoteFiles.getSelectionModel().addListSelectionListener(e -> {
            List<FileModel> allRemoteFiles = ((FileTableModel) this.remoteFiles.getModel()).getData();

            int[] selectedRows = this.remoteFiles.getSelectedRows();
            this.selectedRemoteFiles = new ArrayList<>(selectedRows.length);
            for (int i : selectedRows) {
                this.selectedRemoteFiles.add(allRemoteFiles.get(i));
            }

            int currentSelectRow = this.remoteFiles.getSelectedRow();
            if (currentSelectRow != -1) {
                this.lastFile = this.currentRemoteFile;
                this.currentRemoteFile = allRemoteFiles.get(currentSelectRow);
            }
        });
        this.remoteFiles.addMouseListener(new MouseListener() {
            @Override
            public void mouseClicked(MouseEvent e) {
                final XFTPExplorerWindow self = XFTPExplorerWindow.this;
                long now = System.currentTimeMillis();
                if (self.lastFile == self.currentRemoteFile && now - self.clickWatcher < DOUBLE_CLICK_INTERVAL) {
                    self.loadRemote(self.currentRemoteFile.getPath());
                }

                self.clickWatcher = now;
            }

            @Override
            public void mousePressed(MouseEvent e) {

            }

            @Override
            public void mouseReleased(MouseEvent e) {

            }

            @Override
            public void mouseEntered(MouseEvent e) {

            }

            @Override
            public void mouseExited(MouseEvent e) {

            }
        });
    }

    /**
     * 获取本地文件目录
     * @param path 路径
     */
    public void loadLocal (String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                Services.message(path + " does not exist!", MessageType.WARNING);
            } else if (!file.isDirectory()) {
                if (file.length() > EDITABLE_FILE_SIZE) {
                    DialogWrapper dialog = new Confirm(new Confirm.Options()
                            .title("This file is too large for text editor")
                            .content("Do you still want to edit it?"));
                    if (dialog.showAndGet()) {
                        this.openFileInEditor(file);
                    }
                } else {
                    this.openFileInEditor(file);
                }
            } else {
                File[] files = file.listFiles();
                if (files == null) {
                    DialogWrapper dialog = new Confirm(
                        new Confirm.Options()
                            .title("It is an unavailable folder!")
                            .content("This folder is not available, do you want to open it in system file manager?")
                    );
                    if (dialog.showAndGet()) {
                        try {
                            Desktop.getDesktop().open(file);
                        } catch (IOException ioException) {
                            ioException.printStackTrace();
                            Services.message("Failed to open file in system file manager", MessageType.INFO);
                        }
                    }
                    return;
                }

                path = file.getAbsolutePath();
                this.localFsPath.setText(path);

                List<FileModel> fileModels = new ArrayList<>(file.length() == 0 ? 1 : (file.length() > Integer.MAX_VALUE ?
                        Integer.MAX_VALUE :
                        Integer.parseInt(String.valueOf(file.length()))
                ));

                // 添加返回上一级目录
                fileModels.add(this.getLastFolder(path, File.separator));

                for (File currentFile : files) {
                    // FIXME 完善文件权限换算
                    fileModels.add(new FileModel(
                            currentFile.getAbsolutePath(),
                            currentFile.getName(),
                            currentFile.isDirectory(),
                            currentFile.length(),
                            (currentFile.canRead() ? 1 : 0) |
                                    (currentFile.canWrite() ? 2 : 0) |
                                    (currentFile.canExecute() ? 4 : 0)
                    ));
                }

                rerenderFileList(this.localFiles, fileModels);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Services.message(e.getMessage(), MessageType.WARNING);
        }
    }

    /**
     * 使用当前选择的配置进行连接
     */
    public void connectSftp () {
        // SftpChannel connection = ConnectionBuilder.openSftpChannel$default(
        //   RemoteCredentialsUtil.connectionBuilder$default(
        //     this.credentials,
        //     (Project)null,
        //     (ProgressIndicator)null,
        //     3,
        //     (Object)null
        //   ),
        //   0,
        //   1,
        //   (Object)null
        // );

        // (new RemoteDataProducer())
        //   .withActionEvent(event)
        //   .withShowProjectLevelServers(true)
        //   .produceRemoteData(
        //      optionsProvider.getConnectionType(),
        //      optionsProvider.getConnectionId(),
        //      optionsProvider.getAdditionalData(),
        //      (data) -> {}
        //    )
        // com.jetbrains.plugins.remotesdk.console.RemoteDataProducer
        try {
            (new RemoteDataProducer())
                    .withProject(this.project)
                    .produceRemoteData(
                            RemoteConnectionType.SSH_CONFIG,
                            null,
                            "",
                            data -> {
                                this.triggerConnecting();
                                this.disconnect(false);

                                ConnectionBuilder connectionBuilder = RemoteCredentialsUtil.connectionBuilder(data, this.project);
                                this.sftpChannel = connectionBuilder.openSftpChannel();
                                this.triggerConnected();

                                this.loadRemote(this.sftpChannel.getHome());
                            }
                    );
        } catch (Exception e) {
            e.printStackTrace();
            this.triggerDisconnected();
            Services.message(e.getMessage(), MessageType.ERROR);
        }
    }

    /**
     * 获取远程文件目录
     * @param path 默认地址, 为null时自动使用sftp默认文件夹
     */
    public void loadRemote (String path) {
        if (this.sftpChannel == null) {
            Services.message("Please connect to server first!", MessageType.INFO);
        } else if (!this.sftpChannel.isConnected()) {
            Services.message("SFTP lost connection, retrying...", MessageType.ERROR);
            this.connectSftp();
        }

        RemoteFileObject file = this.sftpChannel.file(path);
        if (!file.exists()) {
            Services.message(path + " does not exist!", MessageType.WARNING);
        } if (!file.isDir()) {
            // 如果文件小于2M, 则自动下载到缓存目录并进行监听
            if (file.size() > EDITABLE_FILE_SIZE) {
                DialogWrapper dialog = new Confirm(new Confirm.Options()
                        .title("This file is too large for text editor")
                        .content("Do you still want to download and edit it?"));
                if (dialog.showAndGet()) {
                    this.downloadFileAndEdit(file);
                }
            } else {
                this.downloadFileAndEdit(file);
            }
        } else {
            this.remoteFsPath.setText(path);

            List<RemoteFileObject> files = file.list();
            List<FileModel> fileModels = new ArrayList<>(files.size());

            // 添加返回上一级目录
            fileModels.add(this.getLastFolder(path, "/"));

            for (RemoteFileObject f : files) {
                fileModels.add(new FileModel(f.path(), f.name(), f.isDir(), f.size(), f.getPermissions()));
            }

            rerenderFileTable(this.remoteFiles, fileModels);
        }
    }

    /**
     * 断开当前连接
     */
    public void disconnect (boolean triggerEvent) {
        if (this.sftpChannel != null && this.sftpChannel.isConnected()) {
            this.sftpChannel.disconnect();
            this.sftpChannel = null;
        }

        // 清空列表
        if (this.remoteFiles.getModel() instanceof FileTableModel) {
            ((FileTableModel) this.remoteFiles.getModel()).resetData(new ArrayList<>());
        }
        // 清空文件列表
        this.remoteEditingFiles = new HashMap<>();

        if (triggerEvent) {
            this.triggerDisconnected();
        }
    }

    /**
     * 设置当前状态为连接中
     */
    public void triggerConnecting () {
        this.exploreButton.setEnabled(false);
        this.exploreButton.setIcon(AnimatedIcon.Default.INSTANCE);
    }

    /**
     * 设置当前状态为已连接
     */
    public void triggerConnected () {
        this.exploreButton.setVisible(false);
        this.exploreButton.setIcon(null);
        this.disconnectButton.setVisible(true);
    }

    /**
     * 设置当前状态为未连接
     */
    public void triggerDisconnected () {
        this.exploreButton.setEnabled(true);
        this.exploreButton.setVisible(true);
        this.disconnectButton.setVisible(false);
        this.remoteFsPath.setText("");
    }

    /**
     * 获取JPanel
     * @return JPanel
     */
    public JPanel getPanel () {
        return this.panel;
    }

    /**
     * 获取上一级目录
     * @param path 当前目录
     */
    private FileModel getLastFolder (String path, String separator) {
        // 添加返回上一级目录
        int lastIndexOfSep = path.lastIndexOf(separator);
        String parentFolder = lastIndexOfSep == -1 ? "" : path.substring(0, lastIndexOfSep);
        return new FileModel(parentFolder.isEmpty() ? separator : parentFolder , "..", true, null, null);
    }

    /**
     * 上传文件, 并通知bus
     * @param localFile 本地文件
     * @param remoteFile 线上文件
     */
    private void uploadFile (String localFile, RemoteFileObject remoteFile) {
        System.out.println("上传" + localFile + ":" + remoteFile.path());

        File file = new File(localFile);

        Transfer transfer = new Transfer();
        transfer.setType(Transfer.Type.UPLOAD);
        transfer.setResult(Transfer.Result.TRANSFERRING);
        transfer.setSource(localFile);
        transfer.setSize(file.length());
        transfer.setTarget(remoteFile.path());

        Runnable runnable = () -> {
            if (!file.exists()) {
                Services.message("Can't find file " + localFile, MessageType.ERROR);
                throw new IllegalStateException(localFile + " does not exists!");
            }
            try {
                //noinspection UnstableApiUsage
                this.sftpChannel.uploadFileOrDir(file, remoteFile.path(), "", new SftpProgressTracker() {
                    @Override
                    public boolean isCanceled() {
    //                    transfer.setResult(Transfer.Result.FAIL);
    //                    transfer.setException("Cancelled");
    //                    this.removeFromQueue();
                        return false;
                    }

                    @Override
                    public void onFileCopied(@NotNull File file) {
                        transfer.setResult(Transfer.Result.SUCCESS);
                        transfer.setException(null);
                        this.removeFromQueue();
                    }

                    @Override
                    public void onBytesTransferred(long l) {
                        transfer.setTransferred(l);
                    }

                    /**
                     * 从队列中移除
                     */
                    private void removeFromQueue () {
                        Runnable r = Maps.getFirstKeyByValue(Data.TRANSFERRING, transfer);
                        Data.TRANSFERRING.remove(r);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                Runnable r = Maps.getFirstKeyByValue(Data.TRANSFERRING, transfer);
                Data.TRANSFERRING.remove(r);
                Services.message("Error occurred while uploading " + localFile + " to " + remoteFile.path(), MessageType.ERROR);
            }
        };
        Data.TRANSFERRING.put(runnable, transfer);
        Data.HISTORY.add(transfer);
        Services.UPLOAD_POOL.execute(runnable);
    }

    /**
     * 下载文件
     * @param file 远程文件
     * @return 本地文件
     */
    private File downloadFile (RemoteFileObject file) {
        if (this.sftpChannel == null || !this.sftpChannel.isConnected()) {
            Services.message("Please start a sftp session first!", MessageType.INFO);
            return null;
        }

        try {
            final File localFile = File.createTempFile("jb-ide-xftp", file.name());
            this.sftpChannel.downloadFileOrDir(file.path(), localFile.getAbsolutePath());
            return localFile;
        } catch (Exception e) {
            e.printStackTrace();
            Services.message("error occurred while downloading file [" + file.path() + "], " + e.getMessage(), MessageType.ERROR);
        }

        return null;
    }

    /**
     * 下载文件并编辑
     * @param file 远程文件信息
     */
    private void downloadFileAndEdit (RemoteFileObject file) {
        if (file.isDir()) {
            throw new IllegalArgumentException("Can not edit a folder!");
        }

        // 如果当前远程文件已经在编辑器中打开了, 则关闭之前的
        RemoteFileObject existsRemoteFile = this.remoteEditingFiles
                .keySet().stream()
                .filter(rf -> rf.path().equals(file.path()))
                .findFirst()
                .orElse(null);
        if (existsRemoteFile != null) {
            File oldCachedFile = new File(this.remoteEditingFiles.get(existsRemoteFile));
            if (oldCachedFile.exists()) {
                DialogWrapper dialog = new Confirm(new Confirm.Options()
                        .title("This file is editing")
                        .okText("Replace")
                        .cancelText("Open")
                        .content("Do you want to replace current editing file? \n" +
                                "Press \"Open\" to open/focus an/the editor for/of existing file. \n" +
                                "Press \"Replace\" to discard downloaded file and re-download the file from remote.")
                );
                if (!dialog.showAndGet()) {
                    this.openFileInEditor(oldCachedFile);
                    return;
                } else {
                    this.remoteEditingFiles.remove(existsRemoteFile);
                }
            }
        }

        this.panel.setEnabled(false);
        File localFile = this.downloadFile(file);
        this.panel.setEnabled(true);
        if (localFile == null) {
            return;
        }

        this.openFileInEditor(localFile);

        // 加入文件监听队列
        this.remoteEditingFiles.put(file, localFile.getAbsolutePath());
    }

    /**
     * 打开文件
     * @param file 文件
     */
    private void openFileInEditor (@NotNull File file) {
        FileEditorManager.getInstance(this.project).openTextEditor(
                new OpenFileDescriptor(
                        this.project,
                        Objects.requireNonNull(LocalFileSystem.getInstance().findFileByIoFile(file)),
                        0
                ),
                true
        );
    }

}
