package hmcl.patcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Finds HMCL installations on the local machine.
public final class InstallationScanner {

    /// A detected HMCL distribution file.
    public record Found(Path path, String version, ClassFilePatcher.State state, boolean backupExists) {

        /// Whether this installation still needs the patch.
        public boolean patchable() {
            return state == ClassFilePatcher.State.ORIGINAL;
        }
    }

    private static final Pattern FILE_NAME = Pattern.compile("(?i)^hmcl.*\\.(jar|exe|sh)$");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".gradle", ".m2", ".cache", ".npm", "node_modules", "snap",
            "temp", "tmp", "cache", "packages", "microsoft", "crashdumps", "winsxs"
    );

    private static final int MAX_VISITED_DIRECTORIES = 60_000;

    private static final String MAIN_CLASS_ENTRY = "org/jackhuang/hmcl/Main.class";

    private InstallationScanner() {
    }

    /// Scans all default installation locations of the current platform.
    public static List<Found> scan() {
        List<Found> result = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Root root : defaultRoots()) {
            collect(root.path(), root.depth(), result, seen);
        }
        if (SystemProbe.isWindows()) {
            // Follow the traces a portable .exe leaves behind: shortcuts and (rare) registry entries.
            for (Path shortcut : WindowsLocator.shortcutTargets()) {
                addCandidate(shortcut, result, seen);
            }
            for (Path entry : WindowsLocator.registryEntries()) {
                if (Files.isDirectory(entry)) {
                    collect(entry, 3, result, seen);
                } else {
                    addCandidate(entry, result, seen);
                }
            }
        }
        return result;
    }

    /// Scans much more aggressively: every fixed drive on Windows, the whole home directory on Linux.
    ///
    /// This can take a while and is therefore only triggered on request.
    public static List<Found> deepScan() {
        List<Found> result = new ArrayList<>(scan());
        Set<Path> seen = new LinkedHashSet<>();
        for (Found found : result) {
            seen.add(found.path());
        }

        if (SystemProbe.isWindows()) {
            for (Path drive : WindowsLocator.fixedDrives()) {
                collect(drive, 5, result, seen);
            }
        } else {
            String home = System.getProperty("user.home", "");
            collect(Path.of(home), 6, result, seen);
            collect(Path.of("/opt"), 5, result, seen);
            collect(Path.of("/usr/local"), 5, result, seen);
        }
        return result;
    }

    /// Adds a single file if it is an HMCL distribution.
    public static void addCandidate(Path file, List<Found> result, Set<Path> seen) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Found found = inspect(file);
        if (found != null && seen.add(found.path())) {
            result.add(found);
        }
    }

    /// Scans the given directory recursively.
    public static List<Found> scan(Path directory, int depth) {
        List<Found> result = new ArrayList<>();
        collect(directory, depth, result, new LinkedHashSet<>());
        return result;
    }

    /// Inspects a single file, returning `null` when it is not an HMCL distribution.
    public static Found inspect(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry(MAIN_CLASS_ENTRY) == null) {
                return null;
            }
            return new Found(absolute, readVersion(zip), readState(zip),
                    Files.isRegularFile(Patcher.backupOf(absolute)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readVersion(ZipFile zip) {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            return "未知";
        }
        try (InputStream in = zip.getInputStream(entry)) {
            String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
            return version == null ? "未知" : version;
        } catch (IOException e) {
            return "未知";
        }
    }

    private static ClassFilePatcher.State readState(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(ClassFilePatcher.REGION_UTILS_ENTRY);
        if (entry == null) {
            return ClassFilePatcher.State.ABSENT;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return ClassFilePatcher.inspect(in.readAllBytes());
        } catch (PatchException e) {
            return ClassFilePatcher.State.ABSENT;
        }
    }

    private static void collect(Path root, int depth, List<Found> result, Set<Path> seen) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, Set.of(), depth, new SimpleFileVisitor<>() {
                private int visited;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    Path name = directory.getFileName();
                    if (!directory.equals(root) && name != null
                            && SKIPPED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return ++visited > MAX_VISITED_DIRECTORIES
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path name = file.getFileName();
                    if (attributes.isRegularFile() && name != null && FILE_NAME.matcher(name.toString()).matches()) {
                        Found found = inspect(file);
                        if (found != null && seen.add(found.path())) {
                            result.add(found);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // An unreadable directory simply contributes no results.
        }
    }

    private record Root(Path path, int depth) {
    }

    private static List<Root> defaultRoots() {
        List<Root> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            addEnvironmentRoot(roots, "LOCALAPPDATA", 3);
            addEnvironmentRoot(roots, "APPDATA", 2);
            addEnvironmentRoot(roots, "ProgramFiles", 2);
            addEnvironmentRoot(roots, "ProgramFiles(x86)", 2);
            roots.add(new Root(Path.of(home, "Desktop"), 2));
            roots.add(new Root(Path.of(home, "Downloads"), 2));
            roots.add(new Root(Path.of(home), 2));
        } else {
            roots.add(new Root(Path.of(home, "Desktop"), 2));
            roots.add(new Root(Path.of(home, "Downloads"), 2));
            roots.add(new Root(Path.of(home, "Applications"), 3));
            roots.add(new Root(Path.of(home, ".local", "share"), 3));
            roots.add(new Root(Path.of(home), 2));
            roots.add(new Root(Path.of("/opt"), 3));
            // Portable layout used by the .deb packages.
            roots.add(new Root(Path.of("/usr/share/java/hmcl"), 1));
            roots.add(new Root(Path.of("/usr/local/share/java/hmcl"), 1));
        }
        return roots;
    }

    private static void addEnvironmentRoot(List<Root> roots, String variable, int depth) {
        String value = System.getenv(variable);
        if (value != null && !value.isBlank()) {
            roots.add(new Root(Path.of(value), depth));
        }
    }
}
