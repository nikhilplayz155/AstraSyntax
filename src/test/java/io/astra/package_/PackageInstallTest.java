package io.astra.package_;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parts of a remote package install that must not need a live server: archive
 * extraction, the zip-slip guard and folder-name sanitising. Downloading itself is exercised
 * through {@code HttpService}, which a separate test covers.
 */
class PackageInstallTest {

    @TempDir
    Path temp;

    private static Path writeZip(Path where, String name, String... entries) throws IOException {
        Path archive = where.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    @Test
    void extractsAPackageNextToTheArchive() throws Exception {
        Path archive = writeZip(temp, "sample.zip",
            "package.yml", "name: sample\nversion: 1.0\n",
            "scripts/main.ar", "on load:\n    broadcast \"hi\"\n");
        Path root = PackageManager.extract(archive, temp.resolve("staging"));
        assertNotNull(root, "a readable zip must extract");
        assertTrue(Files.isRegularFile(root.resolve("package.yml")), "package.yml must be extracted");
        assertTrue(Files.isRegularFile(root.resolve("scripts/main.ar")), "nested scripts must be extracted");
    }

    @Test
    void refusesAnEntryThatEscapesThePackageFolder() throws Exception {
        Path archive = writeZip(temp, "evil.zip", "../escaped.txt", "owned");
        IOException error = assertThrows(IOException.class,
            () -> PackageManager.extract(archive, temp.resolve("staging")));
        assertTrue(error.getMessage().contains("escapes"), error.getMessage());
        assertFalse(Files.exists(temp.resolve("escaped.txt")), "nothing may be written outside the package folder");
    }

    @Test
    void reportsANonZipArchiveInsteadOfPretendingItWorked() throws Exception {
        Path archive = temp.resolve("notes.zip");
        Files.writeString(archive, "this is not a zip");
        assertNull(PackageManager.extract(archive, temp.resolve("staging")), "a broken archive must be reported");
    }

    @Test
    void packageNamesBecomeSafeFolderNames() {
        assertEquals("My_Package", PackageManager.safeName("My Package"));
        assertEquals("a_b_c", PackageManager.safeName("a/b\\c"));
        assertEquals("_.._etc", PackageManager.safeName("../../etc"));
        assertEquals("___", PackageManager.safeName("///"), "separators become underscores, never folders");
        assertEquals("package", PackageManager.safeName(".."), "a name of '..' must not point at the parent");
        assertEquals("package", PackageManager.safeName("."));
    }
}
