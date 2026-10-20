package hmcl.patcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/// A single HMCL distribution file.
///
/// HMCL ships in three shapes: a plain `HMCL-<version>.jar`, and `HMCL-<version>.exe` /
/// `HMCL-<version>.sh`, which are a native launcher followed by exactly the same jar
/// (see `ExecutableHeaderHelper` in HMCL). This class knows how to split such a file into
/// launcher header and jar, rewrite the jar and put everything back in place.
public final class HmclArchive {

    private final Path path;
    private final byte[] data;
    private final int headerLength;

    private HmclArchive(Path path, byte[] data, int headerLength) {
        this.path = path;
        this.data = data;
        this.headerLength = headerLength;
    }

    /// Reads the given HMCL distribution file.
    public static HmclArchive read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        return new HmclArchive(path, data, detectHeaderLength(data));
    }

    public Path path() {
        return path;
    }

    /// Length of the native launcher prefix, `0` for a plain jar.
    public int headerLength() {
        return headerLength;
    }

    /// The jar part of the file.
    public byte[] jar() {
        byte[] jar = new byte[data.length - headerLength];
        System.arraycopy(data, headerLength, jar, 0, jar.length);
        return jar;
    }

    /// Detects how many bytes of a self-extracting archive belong to the executable header.
    ///
    /// The length is derived from the zip central directory: a zip file stores offset and size of
    /// its central directory, so `headerLength = (endOfCentralDirectory - cdSize) - cdOffset`.
    /// This works for plain jars (result `0`), for the `exe`/`sh` bundles and for zip64 archives.
    public static int detectHeaderLength(byte[] data) throws IOException {
        int eocd = findEndOfCentralDirectory(data);

        long centralDirectorySize = readU4(data, eocd + 12);
        long centralDirectoryOffset = readU4(data, eocd + 16);
        int entryCount = readU2(data, eocd + 10);
        int centralDirectoryEnd = eocd;

        if (centralDirectorySize == 0xFFFFFFFFL
                || centralDirectoryOffset == 0xFFFFFFFFL
                || entryCount == 0xFFFF) {
            int locator = eocd - 20;
            if (locator >= 0 && readU4(data, locator) == 0x07064B50) {
                long zip64End = readU8(data, locator + 8);
                if (zip64End >= 0 && zip64End + 56 <= data.length
                        && readU4(data, (int) zip64End) == 0x06064B50) {
                    centralDirectorySize = readU8(data, (int) zip64End + 40);
                    centralDirectoryOffset = readU8(data, (int) zip64End + 48);
                    centralDirectoryEnd = (int) zip64End;
                }
            }
        }

        long centralDirectoryStart = centralDirectoryEnd - centralDirectorySize;
        long headerLength = centralDirectoryStart - centralDirectoryOffset;

        if (centralDirectoryStart < 0
                || centralDirectoryStart + 4 > data.length
                || readU4(data, (int) centralDirectoryStart) != 0x02014B50) {
            throw new IOException("文件不是有效的 HMCL 发行包（找不到 zip 中央目录）");
        }
        if (headerLength < 0
                || headerLength + 4 > data.length
                || readU4(data, (int) headerLength) != 0x04034B50) {
            throw new IOException("文件不是有效的 HMCL 发行包（找不到 jar 起始位置）");
        }
        return (int) headerLength;
    }

    private static int findEndOfCentralDirectory(byte[] data) throws IOException {
        int minimum = Math.max(0, data.length - 0xFFFF - 22);
        for (int i = data.length - 22; i >= minimum; i--) {
            if (readU4(data, i) == 0x06054B50) {
                // Only accept a record whose comment ends exactly at the end of the file: this
                // rules out signature matches inside compressed data.
                if (i + 22 + readU2(data, i + 20) == data.length) {
                    return i;
                }
            }
        }
        throw new IOException("文件不是有效的 zip/JAR（找不到 EOCD 记录）");
    }

    /// Reads one entry from the given jar bytes.
    ///
    /// @return the entry content, or `null` if the jar does not contain that entry
    public static byte[] entry(byte[] jar, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    /// Rebuilds a jar, replacing the given entries and adding missing entries.
    public static byte[] repack(byte[] jar,
                                Map<String, byte[]> replacements,
                                Map<String, byte[]> additions) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(jar.length + 8192);
        Set<String> written = new LinkedHashSet<>();

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar));
             ZipOutputStream out = new ZipOutputStream(buffer)) {
            byte[] chunk = new byte[1 << 16];
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                written.add(name);

                byte[] content = replacements.get(name);
                if (content == null) {
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        entryBuffer.write(chunk, 0, read);
                    }
                    content = entryBuffer.toByteArray();
                }

                ZipEntry copy = new ZipEntry(name);
                if (entry.getTime() != -1) {
                    copy.setTime(entry.getTime());
                }
                if (entry.getComment() != null) {
                    copy.setComment(entry.getComment());
                }
                out.putNextEntry(copy);
                out.write(content);
                out.closeEntry();
            }

            for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
                if (written.contains(addition.getKey())) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(addition.getKey());
                copy.setTime(System.currentTimeMillis());
                out.putNextEntry(copy);
                out.write(addition.getValue());
                out.closeEntry();
            }
        }

        return buffer.toByteArray();
    }

    /// Writes a new jar (keeping the launcher header) over the original file.
    ///
    /// The file is replaced atomically when the file system supports it, and the original
    /// permissions (in particular the executable bit of `HMCL-<version>.sh`) are preserved.
    public void write(byte[] newJar) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path directory = absolute.getParent();
        Path temporary = Files.createTempFile(directory, "." + absolute.getFileName() + ".", ".tmp");
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream(headerLength + newJar.length);
            content.write(data, 0, headerLength);
            content.write(newJar, 0, newJar.length);
            Files.write(temporary, content.toByteArray());

            copyPermissions(absolute, temporary);

            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyPermissions(Path from, Path to) {
        try {
            if (Files.getFileStore(to).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX file systems simply keep the default permissions.
        }
    }

    // Zip records store their multi-byte fields in little-endian order.

    private static int readU2(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU4(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readU8(byte[] data, int offset) {
        return (readU4(data, offset) & 0xFFFFFFFFL) | ((long) readU4(data, offset + 4) << 32);
    }
}
