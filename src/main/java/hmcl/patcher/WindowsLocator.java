package hmcl.patcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Windows specific heuristics used to find a portable HMCL installation.
///
/// HMCL is usually distributed as a portable `HMCL-<version>.exe`, so it has no installation
/// directory that could simply be looked up. Instead this class follows the traces a user leaves
/// behind: shortcuts on the desktop, in the start menu, in the task bar and in the recent files
/// list, plus (best effort) uninstall registry entries and a deep scan of the fixed drives.
public final class WindowsLocator {

    private WindowsLocator() {
    }

    /// Resolves shortcuts whose name mentions HMCL to their target files.
    public static List<Path> shortcutTargets() {
        List<Path> result = new ArrayList<>();
        List<Path> directories = shortcutDirectories();
        if (directories.isEmpty()) {
            return result;
        }

        StringBuilder script = new StringBuilder();
        script.append("[Console]::OutputEncoding=[Text.Encoding]::UTF8;");
        script.append("$s=New-Object -ComObject WScript.Shell;");
        script.append("@(");
        for (int i = 0; i < directories.size(); i++) {
            if (i > 0) {
                script.append(',');
            }
            script.append('\'').append(directories.get(i).toString().replace("'", "''")).append('\'');
        }
        script.append(") | ForEach-Object { if (Test-Path -LiteralPath $_) {");
        script.append(" Get-ChildItem -LiteralPath $_ -Filter *.lnk -Recurse -ErrorAction SilentlyContinue } }");
        script.append(" | Where-Object { $_.Name -like '*HMCL*' -or $_.Name -like '*hmcl*' }");
        script.append(" | ForEach-Object { $s.CreateShortcut($_.FullName).TargetPath }");
        script.append(" | Where-Object { $_ }");

        try {
            for (String line : SystemProbe.run(List.of(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script.toString()))) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                Path path = Path.of(value);
                if (path.isAbsolute()) {
                    result.add(path);
                }
            }
        } catch (Exception ignored) {
            // PowerShell may be disabled by policy; the other heuristics still work.
        }
        return result;
    }

    /// Reads uninstall registry entries that mention HMCL (display icon / install location).
    public static List<Path> registryEntries() {
        List<Path> result = new ArrayList<>();
        List<String> keys = List.of(
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        );
        for (String key : keys) {
            try {
                List<String> lines = SystemProbe.run(List.of("reg", "query", key, "/s", "/f", "HMCL", "/d"));
                for (String line : lines) {
                    String trimmed = line.trim();
                    String lower = trimmed.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("displayicon")) {
                        addPath(result, trimmed.substring(trimmed.indexOf("REG_SZ") >= 0
                                ? trimmed.indexOf("REG_SZ") + "REG_SZ".length()
                                : 0));
                    } else if (lower.startsWith("installlocation")) {
                        addPath(result, trimmed.substring(trimmed.indexOf("REG_SZ") >= 0
                                ? trimmed.indexOf("REG_SZ") + "REG_SZ".length()
                                : 0));
                    }
                }
            } catch (Exception ignored) {
                // Missing key, no reg.exe, or no permission: not fatal.
            }
        }
        return result;
    }

    /// All existing fixed drive roots, used for a deep scan.
    public static List<Path> fixedDrives() {
        List<Path> result = new ArrayList<>();
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                Path path = root.toPath();
                if (java.nio.file.Files.isDirectory(path)) {
                    result.add(path);
                }
            }
        } catch (Exception ignored) {
            // Ignore.
        }
        return result;
    }

    private static void addPath(List<Path> result, String raw) {
        String value = raw.trim();
        int comma = value.indexOf(',');
        if (comma > 0 && value.substring(comma + 1).trim().matches("\\d+")) {
            value = value.substring(0, comma);
        }
        value = value.replace("\"", "").trim();
        if (value.length() > 2 && value.charAt(1) == ':') {
            try {
                Path path = Path.of(value);
                if (java.nio.file.Files.exists(path)) {
                    result.add(path);
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed paths.
            }
        }
    }

    private static List<Path> shortcutDirectories() {
        List<Path> directories = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        addIfPresent(directories, System.getenv("USERPROFILE"), "Desktop");
        addIfPresent(directories, System.getenv("PUBLIC"), "Desktop");
        addIfPresent(directories, System.getenv("APPDATA"),
                "Microsoft", "Windows", "Start Menu", "Programs");
        addIfPresent(directories, System.getenv("ProgramData"),
                "Microsoft", "Windows", "Start Menu", "Programs");
        addIfPresent(directories, System.getenv("APPDATA"),
                "Microsoft", "Internet Explorer", "Quick Launch", "User Pinned", "TaskBar");
        addIfPresent(directories, System.getenv("APPDATA"), "Microsoft", "Windows", "Recent");
        addIfPresent(directories, home, "Desktop");
        return directories;
    }

    private static void addIfPresent(List<Path> directories, String base, String... children) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(base, children);
            if (java.nio.file.Files.isDirectory(path)) {
                directories.add(path);
            }
        } catch (RuntimeException ignored) {
            // Ignore invalid environment values.
        }
    }

    /// Runs a PowerShell helper, kept for callers that need a quick environment question.
    @SuppressWarnings("unused")
    static String powershell(String command) throws IOException, InterruptedException {
        return String.join("\n", SystemProbe.run(List.of(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", command)));
    }
}
