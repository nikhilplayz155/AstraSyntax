package io.astra.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Filesystem helpers: atomic writes, safe traversal checks and resource copying. */
public final class FileUtil {

    private FileUtil() {}

    /** Create a directory (and parents) if missing. */
    public static Path ensureDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) Files.createDirectories(dir);
        return dir;
    }

    /** Read a UTF-8 text file. */
    public static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Write text atomically (temp file + move) so a crash cannot corrupt the target. */
    public static void writeAtomic(Path file, String content) throws IOException {
        writeAtomic(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Write bytes atomically. */
    public static void writeAtomic(Path file, byte[] content) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) ensureDirectory(parent);
        Path tmp = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Append a line to a UTF-8 file, creating it when needed. */
    public static void append(Path file, String line) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) ensureDirectory(parent);
        Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Read a classpath resource as UTF-8 text, or {@code null} when absent. */
    public static String readResource(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Runed: copy a classpath resource to disk when the target does not exist yet. */
    public static boolean copyResourceIfMissing(ClassLoader loader, String resource, Path target) throws IOException {
        if (Files.exists(target)) return false;
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return false;
            writeAtomic(target, in.readAllBytes());
            return true;
        }
    }

    /**
     * Resolve {@code candidate} inside {@code root}, rejecting traversal outside
     * the root. Used by every script/package/module file operation so configured
     * file access rules cannot be escaped with {@code ../}.
     */
    public static Path resolveSafely(Path root, String candidate) throws IOException {
        if (Strings.isBlank(candidate)) throw new IOException("Empty path");
        if (candidate.indexOf('\0') >= 0) throw new IOException("Invalid path");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Path escapes the allowed root: " + candidate);
        }
        return resolved;
    }

    /** List files inside a directory (empty when the directory does not exist). */
    public static List<Path> listFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Recursively list files with the given extension (case-insensitive). */
    public static List<Path> listFilesRecursive(Path dir, String extension) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> extension == null
                    || p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(extension.toLowerCase(java.util.Locale.ROOT)))
                .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** File name without its extension. */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Human readable file size. */
    public static String readableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
