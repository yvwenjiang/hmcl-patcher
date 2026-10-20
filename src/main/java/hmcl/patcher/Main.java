package hmcl.patcher;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Command line entry point of hmcl-patcher.
public final class Main {

    private static final String NAME = "hmcl-patcher";
    private static final String VERSION = "1.1.0";

    /// Return value of [#run(String[])] when a Swing window was opened and the JVM must stay alive.
    private static final int GUI_MODE = -1;

    private Main() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != GUI_MODE) {
            System.exit(code);
        }
    }

    private static int run(String[] args) {
        boolean list = false;
        boolean assumeYes = false;
        boolean dryRun = false;
        boolean restore = false;
        boolean verify = false;
        boolean deep = false;
        boolean gui = false;
        List<String> arguments = new ArrayList<>();

        for (String argument : args) {
            switch (argument) {
                case "-h", "--help" -> {
                    printHelp();
                    return 0;
                }
                case "-v", "--version" -> {
                    System.out.println(NAME + " " + VERSION);
                    return 0;
                }
                case "-l", "--list" -> list = true;
                case "-y", "--yes" -> assumeYes = true;
                case "-n", "--dry-run" -> dryRun = true;
                case "--restore" -> restore = true;
                case "--verify" -> verify = true;
                case "--deep" -> deep = true;
                case "--gui" -> gui = true;
                case "--all" -> {
                    // Patching everything that was found is the default behaviour.
                }
                default -> {
                    if (argument.startsWith("-") && argument.length() > 1) {
                        error("未知选项: " + argument);
                        printHelp();
                        return 2;
                    }
                    arguments.add(argument);
                }
            }
        }

        System.out.println(NAME + " " + VERSION);
        System.out.println();
        Disclaimer.print();

        if (gui) {
            if (!Gui.isAvailable()) {
                error("当前环境没有图形界面，无法打开窗口模式。");
                return 1;
            }
            Gui.open(null);
            return GUI_MODE;
        }

        boolean explicit = !arguments.isEmpty();

        // Started by double clicking the jar: no console and no arguments, so prefer the window,
        // where the user can also drag in an HMCL that the automatic scan did not find.
        if (!explicit && !assumeYes && !list && !verify && !restore && !dryRun
                && System.console() == null && Gui.isAvailable()) {
            info("已打开窗口模式：自动扫描本机 HMCL，也可以把程序文件拖进窗口。");
            Gui.open(null);
            return GUI_MODE;
        }
        List<InstallationScanner.Found> targets;
        try {
            if (explicit) {
                targets = resolve(arguments);
            } else {
                info("正在扫描已安装的 HMCL" + (deep ? "（深度扫描，可能需要几分钟）" : "") + " ...");
                targets = deep ? InstallationScanner.deepScan() : InstallationScanner.scan();
            }
        } catch (IOException e) {
            error("读取目标失败: " + e.getMessage());
            return 1;
        }

        if (targets.isEmpty()) {
            return noInstallationFound();
        }

        if (list || verify) {
            printTable(targets);
            return 0;
        }

        if (restore) {
            return restoreAll(targets, assumeYes);
        }

        return patchAll(targets, assumeYes, dryRun, explicit);
    }

    private static int noInstallationFound() {
        if (Gui.isAvailable()) {
            info("没有自动找到 HMCL，已为你打开窗口：把 HMCL 的 exe/jar/sh 拖进窗口即可修复。");
            Gui.open(null);
            return GUI_MODE;
        }
        info("没有找到 HMCL。");
        info("请把 HMCL 的 exe/jar/sh 文件拖入窗口，或显式指定路径：");
        info("  " + NAME + " ~/Downloads/HMCL-3.17.0.362.jar");
        info("  " + NAME + " 'C:\\Users\\me\\Desktop\\HMCL-3.17.0.362.exe'");
        info("  " + NAME + " --deep           # 深度扫描整机");
        return 1;
    }

    private static List<InstallationScanner.Found> resolve(List<String> arguments) throws IOException {
        List<InstallationScanner.Found> result = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String argument : arguments) {
            Path path = Path.of(argument).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                error("路径不存在: " + path);
                continue;
            }
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".deb")) {
                warn("不支持直接修补 .deb 安装包，请先安装，然后修补 /usr/share/java/hmcl/ 下的 HMCL-*.sh：");
                info("  sudo " + NAME + " /usr/share/java/hmcl/");
                continue;
            }
            List<InstallationScanner.Found> found = Files.isDirectory(path)
                    ? InstallationScanner.scan(path, 8)
                    : new ArrayList<>(List.of(InstallationScanner.inspect(path)));
            if (found.size() == 1 && found.get(0) == null) {
                warn("不是 HMCL 发行包，已忽略: " + path);
                continue;
            }
            for (InstallationScanner.Found installation : found) {
                if (installation != null && seen.add(installation.path())) {
                    result.add(installation);
                }
            }
        }
        return result;
    }

    private static int patchAll(List<InstallationScanner.Found> targets, boolean assumeYes,
                                boolean dryRun, boolean explicit) {
        List<InstallationScanner.Found> pending = targets.stream()
                .filter(InstallationScanner.Found::patchable)
                .toList();

        printTable(targets);

        if (pending.isEmpty()) {
            info("所有安装要么已经打过补丁，要么不含下载限制，无需处理。");
            return 0;
        }

        if (!explicit && !assumeYes && !dryRun && !confirm("是否对以上 " + pending.size() + " 个安装写入补丁?")) {
            info("已取消。");
            return 0;
        }

        if (SystemProbe.isHmclRunning()) {
            warn("检测到 HMCL 可能正在运行，请先退出 HMCL；Windows 下文件被占用会导致写入失败。");
        }

        int failed = 0;
        for (InstallationScanner.Found installation : pending) {
            try {
                info("正在处理 " + installation.path() + " ...");
                Patcher.Result result = Patcher.repair(installation.path(), installation.version(), dryRun);
                switch (result.outcome()) {
                    case PATCHED -> success("已写入补丁，原文件备份为 "
                            + Patcher.backupOf(result.path()).getFileName());
                    case PATCHED_COPY -> warn("原文件无法写入，已生成修复副本: " + result.path());
                    case WOULD_PATCH -> success("检查通过，--dry-run 模式未写入。");
                    case ALREADY_PATCHED -> info("已打过补丁，跳过。");
                    case NO_RESTRICTION -> info("该版本没有下载限制，跳过。");
                    default -> info("跳过。");
                }
            } catch (IOException e) {
                failed++;
                error("处理失败: " + e.getMessage());
                if (e instanceof AccessDeniedException) {
                    info("没有写入权限：Linux 上可尝试 sudo，或先把 HMCL 复制到用户目录再运行。");
                } else if (e instanceof FileSystemException) {
                    info("文件可能正被占用，请先退出正在运行的 HMCL 再重试。");
                }
            } catch (PatchException e) {
                failed++;
                error("无法修补: " + e.getMessage());
            }
        }

        if (!dryRun) {
            System.out.println();
            System.out.println("[!] 说明: 修补后的 HMCL 官方签名失效，会把自己识别为“非官方构建”：");
            System.out.println("    - 自动检查更新会被禁用（因此补丁不会被自动更新覆盖）；");
            System.out.println("    - 登录微软账号时会显示“非官方构建”提示，登录本身不受影响；");
            System.out.println("    - 重新下载或手动升级 HMCL 后，需要再次运行本工具。");
            System.out.println("    可使用 --restore 还原备份。");
        }

        return failed == 0 ? 0 : 1;
    }

    private static int restoreAll(List<InstallationScanner.Found> targets, boolean assumeYes) {
        List<InstallationScanner.Found> restorable = targets.stream()
                .filter(InstallationScanner.Found::backupExists)
                .toList();

        if (restorable.isEmpty()) {
            info("没有找到备份文件（*" + Patcher.BACKUP_SUFFIX + "），无需还原。");
            return 0;
        }

        if (!assumeYes && !confirm("是否从备份还原以上 " + restorable.size() + " 个安装?")) {
            info("已取消。");
            return 0;
        }

        int failed = 0;
        for (InstallationScanner.Found installation : restorable) {
            try {
                Patcher.restore(installation.path(), installation.version());
                success("已还原 " + installation.path());
            } catch (IOException e) {
                failed++;
                error("还原失败: " + e.getMessage());
            }
        }
        return failed == 0 ? 0 : 1;
    }

    private static void printTable(List<InstallationScanner.Found> targets) {
        System.out.println("找到 " + targets.size() + " 个 HMCL 安装:");
        for (InstallationScanner.Found installation : targets) {
            System.out.printf("  %-60s %-14s %s%n",
                    shorten(installation.path().toString(), 60),
                    installation.version(),
                    describe(installation.state()));
        }
        System.out.println();
    }

    static String describe(ClassFilePatcher.State state) {
        return switch (state) {
            case PATCHED -> "已打补丁";
            case ORIGINAL -> "待打补丁";
            case ABSENT -> "无此限制";
        };
    }

    private static String shorten(String text, int maximum) {
        return text.length() <= maximum ? text : "..." + text.substring(text.length() - maximum + 3);
    }

    private static boolean confirm(String message) {
        if (System.console() == null) {
            // Non-interactive environment: continue without asking.
            return true;
        }
        System.out.print(message + " [Y/n] ");
        String answer = System.console().readLine();
        return answer == null || answer.isBlank()
                || answer.equalsIgnoreCase("y")
                || answer.equalsIgnoreCase("yes");
    }

    private static void printHelp() {
        System.out.println("""
                %s %s — 解除 HMCL 3.17.0.362+ 的中国大陆下载限制

                用法:
                  %s [选项] [文件或目录 ...]

                不带文件参数时会自动扫描本机常见的 HMCL 安装位置并修补；
                找不到时会自动打开窗口，把 HMCL 的 exe/jar/sh 拖进去即可修复。
                支持 HMCL-x.y.z.jar、HMCL-x.y.z.exe（Windows）和 HMCL-x.y.z.sh（Linux）。

                选项:
                  -l, --list        仅列出检测到的 HMCL 及其补丁状态
                      --verify      同 --list
                      --restore     从备份（*%s）还原
                  -n, --dry-run     只检查，不写入文件
                  -y, --yes         不询问，直接执行
                      --deep        深度扫描整机（Windows 扫描所有固定磁盘）
                      --gui         强制打开图形窗口（支持拖放文件）
                  -h, --help        显示本帮助
                  -v, --version     显示版本

                示例:
                  %s                                 自动扫描并修补
                  %s ~/Downloads/HMCL-3.17.0.362.jar 修补指定文件
                  %s --gui                           打开窗口，拖入文件修复
                  %s --restore                       还原所有备份
                """.formatted(NAME, VERSION, NAME, Patcher.BACKUP_SUFFIX, NAME, NAME, NAME, NAME));
    }

    private static void info(String message) {
        System.out.println("[i] " + message);
    }

    private static void success(String message) {
        System.out.println("[+] " + message);
    }

    private static void warn(String message) {
        System.out.println("[!] " + message);
    }

    private static void error(String message) {
        System.out.println("[-] " + message);
    }
}
