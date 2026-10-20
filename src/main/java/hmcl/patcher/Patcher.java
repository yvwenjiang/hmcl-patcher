package hmcl.patcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/// Applies and reverts the download restriction patch on a single HMCL distribution file.
public final class Patcher {

    /// Suffix of the backup that is created before an installation is modified.
    public static final String BACKUP_SUFFIX = ".hmclbak";

    /// Info entry written into patched archives so a patch can be recognised without parsing
    /// the class file.
    public static final String MARKER_ENTRY = "META-INF/hmcl-patcher.properties";

    /// Outcome of a patch attempt.
    public enum Outcome {
        /// The installation was patched in this run.
        PATCHED,
        /// The original file could not be written, so a patched copy was created instead.
        PATCHED_COPY,
        /// The installation already contains the patch.
        ALREADY_PATCHED,
        /// The installation does not contain the region based download restriction.
        NO_RESTRICTION,
        /// The file was not modified because `--dry-run` was used.
        WOULD_PATCH,
        /// The installation was restored from its backup.
        RESTORED,
        /// There was nothing to restore.
        NOTHING_TO_RESTORE
    }

    /// Result of a patch attempt.
    public record Result(Path path, String version, Outcome outcome) {
    }

    private Patcher() {
    }

    /// Returns the backup path belonging to the given installation.
    public static Path backupOf(Path installation) {
        return installation.resolveSibling(installation.getFileName() + BACKUP_SUFFIX);
    }

    /// Patches the given file, retrying briefly while Windows still holds it open and falling back
    /// to a patched copy when the original location is not writable.
    public static Result repair(Path installation, String version, boolean dryRun)
            throws IOException, PatchException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return patch(installation, version, dryRun);
            } catch (java.nio.file.AccessDeniedException e) {
                if (dryRun) {
                    throw e;
                }
                return patchCopy(installation, version);
            } catch (java.nio.file.FileSystemException e) {
                // Most likely the file is still in use; give the user a moment to close HMCL.
                lastError = e;
                sleep(700L * attempt);
            }
        }
        throw lastError;
    }

    /// Patches the given HMCL distribution file.
    ///
    /// @param dryRun when `true` the file is only analysed and never written
    public static Result patch(Path installation, String version, boolean dryRun)
            throws IOException, PatchException {
        return patch(installation, version, dryRun, true);
    }

    private static Result patch(Path installation, String version, boolean dryRun, boolean createBackup)
            throws IOException, PatchException {
        HmclArchive archive = HmclArchive.read(installation);
        byte[] jar = archive.jar();

        byte[] classFile = HmclArchive.entry(jar, ClassFilePatcher.REGION_UTILS_ENTRY);
        if (classFile == null) {
            return new Result(installation, version, Outcome.NO_RESTRICTION);
        }

        ClassFilePatcher.State state = ClassFilePatcher.inspect(classFile);
        if (state == ClassFilePatcher.State.PATCHED) {
            return new Result(installation, version, Outcome.ALREADY_PATCHED);
        }

        byte[] patchedClass = ClassFilePatcher.patch(classFile);
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        replacements.put(ClassFilePatcher.REGION_UTILS_ENTRY, patchedClass);

        Map<String, byte[]> additions = new LinkedHashMap<>();
        additions.put(MARKER_ENTRY, marker(version).getBytes(StandardCharsets.UTF_8));

        byte[] patchedJar = HmclArchive.repack(jar, replacements, additions);
        verifyJar(patchedJar);

        if (dryRun) {
            return new Result(installation, version, Outcome.WOULD_PATCH);
        }

        if (createBackup) {
            backup(installation);
        }
        archive.write(patchedJar);
        verifyFile(installation);

        return new Result(installation, version, Outcome.PATCHED);
    }

    /// Restores an installation from its backup.
    public static Result restore(Path installation, String version) throws IOException {
        Path backup = backupOf(installation);
        if (!Files.isRegularFile(backup)) {
            return new Result(installation, version, Outcome.NOTHING_TO_RESTORE);
        }
        Files.copy(backup, installation, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.delete(backup);
        return new Result(installation, version, Outcome.RESTORED);
    }

    /// Creates a patched copy next to the original file (or in the home directory) when the
    /// original cannot be written, for example because HMCL lives in a read-only directory.
    private static Result patchCopy(Path installation, String version) throws IOException, PatchException {
        Path copy = patchedCopyPath(installation);
        Files.copy(installation, copy, StandardCopyOption.REPLACE_EXISTING);
        patch(copy, version, false, false);
        return new Result(copy, version, Outcome.PATCHED_COPY);
    }

    private static Path patchedCopyPath(Path installation) {
        String name = installation.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        String copyName = base + "-patched" + extension;
        try {
            Path directory = installation.toAbsolutePath().getParent();
            if (directory != null && Files.isWritable(directory)) {
                return directory.resolve(copyName);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the home directory.
        }
        return Path.of(System.getProperty("user.home", "."), copyName);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void backup(Path installation) throws IOException {
        Path backup = backupOf(installation);
        Files.copy(installation, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /// Checks the freshly built jar in memory.
    private static void verifyJar(byte[] jar) throws IOException, PatchException {
        byte[] classFile = HmclArchive.entry(jar, ClassFilePatcher.REGION_UTILS_ENTRY);
        if (classFile == null || ClassFilePatcher.inspect(classFile) != ClassFilePatcher.State.PATCHED) {
            throw new PatchException("补丁校验失败：RegionUtils 未被正确替换");
        }
    }

    /// Checks the file that was just written to disk, including its launcher header.
    private static void verifyFile(Path installation) throws IOException, PatchException {
        HmclArchive archive = HmclArchive.read(installation);
        verifyJar(archive.jar());
    }

    private static String marker(String version) {
        return String.join("\n",
                "# Written by hmcl-patcher, see https://github.com/HMCL-dev/HMCL",
                "tool=hmcl-patcher",
                "hmcl.version=" + version,
                "patched.at=" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "description=RegionUtils.isIsChinaMainlandNetwork() replaced by `return false`",
                ""
        );
    }
}
