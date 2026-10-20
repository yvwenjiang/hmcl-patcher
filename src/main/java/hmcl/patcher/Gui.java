package hmcl.patcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// A small Swing window: drag an HMCL `exe` / `jar` / `sh` onto it and press "开始修复".
///
/// The window is opened automatically when an automatic scan finds nothing, so a player who
/// simply double clicks the tool can still repair their own copy by dragging it in.
public final class Gui {

    private final JFrame frame = new JFrame("HMCL 一键修复工具");
    private final JTextArea logArea = new JTextArea(9, 60);
    private final JLabel targetLabel = new JLabel("尚未选择文件");
    private final JCheckBox agreement = new JCheckBox("我已阅读并同意以上免责条款");
    private final JButton chooseButton = new JButton("选择文件…");
    private final JButton repairButton = new JButton("开始修复");
    private final JButton restoreButton = new JButton("还原备份");
    private final JButton scanButton = new JButton("深度扫描");

    private Path target;
    private String targetVersion = "";
    private boolean targetHasBackup;
    private boolean busy;

    private Gui(Path initial) {
        buildWindow();
        if (initial != null) {
            selectFile(initial);
        } else {
            autoScan();
        }
    }

    /// Whether a graphical environment is available.
    public static boolean isAvailable() {
        return !GraphicsEnvironment.isHeadless();
    }

    /// Opens the window; safe to call from any thread.
    public static void open(Path initial) {
        SwingUtilities.invokeLater(() -> {
            configureLookAndFeel();
            new Gui(initial).frame.setVisible(true);
        });
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default look and feel.
        }
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));

        JLabel title = new JLabel("HMCL 一键修复（解除中国大陆下载限制）");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        top.add(title, BorderLayout.NORTH);

        JTextArea disclaimer = new JTextArea(Disclaimer.TEXT);
        disclaimer.setEditable(false);
        disclaimer.setLineWrap(true);
        disclaimer.setWrapStyleWord(true);
        disclaimer.setBackground(new Color(0xF7, 0xF7, 0xF7));
        disclaimer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane disclaimerScroll = new JScrollPane(disclaimer);
        disclaimerScroll.setPreferredSize(new Dimension(640, 150));
        top.add(disclaimerScroll, BorderLayout.CENTER);
        top.add(agreement, BorderLayout.SOUTH);
        frame.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        JLabel dropArea = new JLabel("""
                <html><div style='text-align:center'>
                把 HMCL 的 <b>exe</b> / <b>jar</b> / <b>sh</b> 文件拖到这里
                <br><span style='color:#888'>找不到文件？打开 HMCL 所在文件夹（桌面 / 下载），把程序本体拖进来</span>
                </div></html>""", JLabel.CENTER);
        dropArea.setPreferredSize(new Dimension(640, 90));
        dropArea.setOpaque(true);
        dropArea.setBackground(new Color(0xF2, 0xF6, 0xFB));
        dropArea.setBorder(BorderFactory.createDashedBorder(Color.GRAY, 2f, 4f, 4f, true));

        JPanel dropPanel = new JPanel(new BorderLayout());
        dropPanel.add(dropArea, BorderLayout.CENTER);
        enableDropping(dropArea);
        enableDropping(dropPanel);

        center.add(dropPanel, BorderLayout.NORTH);

        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        targetLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        middle.add(targetLabel);
        middle.add(Box.createVerticalStrut(4));
        center.add(middle, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("日志"));
        center.add(logScroll, BorderLayout.SOUTH);

        frame.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 4, 8, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        buttons.add(chooseButton);
        buttons.add(repairButton);
        buttons.add(restoreButton);
        buttons.add(scanButton);
        frame.add(buttons, BorderLayout.SOUTH);

        // The button in the middle row and the one in the bottom row must stay in sync.
        chooseButton.addActionListener(e -> chooseFile());
        repairButton.addActionListener(e -> repair());
        restoreButton.addActionListener(e -> restore());
        scanButton.addActionListener(e -> deepScan());
        agreement.addActionListener(e -> refreshButtons());

        frame.setMinimumSize(new Dimension(680, 520));
        frame.setSize(700, 620);
        frame.setLocationRelativeTo(null);
        refreshButtons();
        log("提示：先勾选免责条款，再选择或拖入 HMCL 程序文件。");
        log("支持 Windows 的 HMCL-*.exe、通用的 HMCL-*.jar 以及 Linux 的 HMCL-*.sh。");
    }

    private void enableDropping(Component component) {
        new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) event.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        event.dropComplete(false);
                        return;
                    }
                    selectFile(files.get(0).toPath());
                    event.dropComplete(true);
                } catch (Exception e) {
                    log("拖放失败: " + e.getMessage());
                    event.dropComplete(false);
                }
            }
        });
    }

    private void autoScan() {
        log("正在自动扫描本机的 HMCL ...");
        new SwingWorker<List<InstallationScanner.Found>, Void>() {
            @Override
            protected List<InstallationScanner.Found> doInBackground() {
                return InstallationScanner.scan();
            }

            @Override
            protected void done() {
                try {
                    List<InstallationScanner.Found> found = get();
                    if (found.isEmpty()) {
                        log("没有自动找到 HMCL。请打开 HMCL 所在文件夹，把程序本体拖进窗口。");
                        return;
                    }
                    log("自动扫描找到 " + found.size() + " 个 HMCL。");
                    if (found.size() == 1) {
                        selectFile(found.get(0).path());
                    } else {
                        for (InstallationScanner.Found installation : found) {
                            log("  - " + installation.path() + "  版本 " + installation.version()
                                    + "，" + Main.describe(installation.state()));
                        }
                        log("请拖入要修复的那个文件，或点击“深度扫描”筛选。");
                    }
                } catch (Exception e) {
                    log("自动扫描失败: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 HMCL 程序文件");
        chooser.setFileFilter(new FileNameExtensionFilter("HMCL 程序 (exe, jar, sh)", "exe", "jar", "sh"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            selectFile(chooser.getSelectedFile().toPath());
        }
    }

    private void selectFile(Path path) {
        if (Files.isDirectory(path)) {
            List<InstallationScanner.Found> found = InstallationScanner.scan(path, 8);
            if (found.isEmpty()) {
                log("该目录中没有找到 HMCL 程序: " + path);
                return;
            }
            if (found.size() > 1) {
                log("该目录中找到 " + found.size() + " 个 HMCL，选择第一个: " + found.get(0).path());
            }
            selectFile(found.get(0).path());
            return;
        }

        InstallationScanner.Found found = InstallationScanner.inspect(path);
        if (found == null) {
            log("这不是有效的 HMCL 程序文件: " + path);
            log("请拖入 HMCL-<版本>.exe、HMCL-<版本>.jar 或 HMCL-<版本>.sh 本体。");
            return;
        }

        target = found.path();
        targetVersion = found.version();
        targetHasBackup = found.backupExists();
        targetLabel.setText("已选择: " + target + "  (版本 " + targetVersion + "，"
                + Main.describe(found.state()) + ")");
        log("已选择 " + target + "，版本 " + targetVersion + "，" + Main.describe(found.state()));
        refreshButtons();
    }

    private void repair() {
        Path file = target;
        if (file == null) {
            return;
        }
        setBusy(true);
        log("开始修复 " + file + " ...");
        new SwingWorker<Patcher.Result, Void>() {
            @Override
            protected Patcher.Result doInBackground() throws Exception {
                return Patcher.repair(file, targetVersion, false);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    Patcher.Result result = get();
                    switch (result.outcome()) {
                        case PATCHED -> {
                            log("修复成功，原文件已备份为 " + Patcher.backupOf(result.path()).getFileName());
                            log("请重新启动 HMCL 验证：下载页面应可正常使用。");
                        }
                        case PATCHED_COPY -> {
                            log("原文件无法写入，已生成修复副本: " + result.path());
                            log("请改用这个副本启动 HMCL。");
                        }
                        case ALREADY_PATCHED -> log("该文件已经打过补丁，无需重复操作。");
                        case NO_RESTRICTION -> log("该版本没有下载限制，无需打补丁。");
                        default -> log("处理结果: " + result.outcome());
                    }
                    selectFile(result.path());
                } catch (Exception e) {
                    log("修复失败: " + rootMessage(e));
                    log("如果 HMCL 正在运行，请先退出它再重试。");
                }
            }
        }.execute();
    }

    private void restore() {
        Path file = target;
        if (file == null) {
            return;
        }
        setBusy(true);
        new SwingWorker<Patcher.Result, Void>() {
            @Override
            protected Patcher.Result doInBackground() throws Exception {
                return Patcher.restore(file, targetVersion);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    Patcher.Result result = get();
                    if (result.outcome() == Patcher.Outcome.RESTORED) {
                        log("已从备份还原: " + file);
                    } else {
                        log("没有找到备份文件，无法还原。");
                    }
                    selectFile(file);
                } catch (Exception e) {
                    log("还原失败: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void deepScan() {
        setBusy(true);
        log("正在进行深度扫描，请稍候（可能需要几分钟）...");
        new SwingWorker<List<InstallationScanner.Found>, Void>() {
            @Override
            protected List<InstallationScanner.Found> doInBackground() {
                return InstallationScanner.deepScan();
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    List<InstallationScanner.Found> found = get();
                    if (found.isEmpty()) {
                        log("深度扫描没有找到 HMCL，请手动把程序文件拖入窗口。");
                        return;
                    }
                    log("深度扫描找到 " + found.size() + " 个 HMCL。");
                    if (found.size() == 1) {
                        selectFile(found.get(0).path());
                    } else {
                        String[] options = new String[found.size()];
                        for (int i = 0; i < found.size(); i++) {
                            InstallationScanner.Found installation = found.get(i);
                            options[i] = installation.path() + "  (" + installation.version() + ", "
                                    + Main.describe(installation.state()) + ")";
                        }
                        Object choice = JOptionPane.showInputDialog(frame, "请选择要修复的 HMCL：",
                                "深度扫描结果", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
                        if (choice != null) {
                            for (int i = 0; i < options.length; i++) {
                                if (options[i].equals(choice)) {
                                    selectFile(found.get(i).path());
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log("深度扫描失败: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        refreshButtons();
    }

    private void refreshButtons() {
        boolean agreed = agreement.isSelected() && !busy;
        repairButton.setEnabled(agreed && target != null);
        restoreButton.setEnabled(agreed && target != null);
        chooseButton.setEnabled(!busy);
        scanButton.setEnabled(!busy);
    }

    private void log(String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> log(message));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    /// Kept for symmetry with the command line tool; opens the given directory in the file manager.
    @SuppressWarnings("unused")
    private void openDirectory(Path directory) {
        try {
            Desktop.getDesktop().open(directory.toFile());
        } catch (Exception ignored) {
            // Not every desktop environment supports this.
        }
    }
}
