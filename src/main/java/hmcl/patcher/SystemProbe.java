package hmcl.patcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/// Small helpers that ask the operating system about the environment.
public final class SystemProbe {

    private SystemProbe() {
    }

    /// Whether the current platform is Windows.
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /// Whether the current platform is Linux.
    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || os.contains("freebsd");
    }

    /// Best effort check whether an HMCL instance is currently running.
    ///
    /// A running HMCL keeps its `HMCL-*.exe` file open on Windows, which makes patching fail with
    /// an access error, so the user is warned before the tool tries to write.
    public static boolean isHmclRunning() {
        try {
            List<String> output = isWindows()
                    ? run(List.of("tasklist", "/FO", "CSV", "/NH"))
                    : run(List.of("ps", "-eo", "args="));
            for (String line : output) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("hmcl-patcher")) {
                    continue;
                }
                if (lower.contains("hmcl") && (lower.contains(".exe") || lower.contains(".jar") || lower.contains("hmclauncher"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // The check is a convenience only; never fail because of it.
        }
        return false;
    }

    /// Runs a command and returns its standard output lines.
    static List<String> run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null) {
            try {
                charset = java.nio.charset.Charset.forName(nativeEncoding);
            } catch (RuntimeException ignored) {
                // Fall back to UTF-8.
            }
        }
        List<String> lines;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), charset))) {
            lines = reader.lines().toList();
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return lines;
    }
}
