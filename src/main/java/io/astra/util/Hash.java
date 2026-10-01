package io.astra.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers used by the compile cache, bundled-library verification and integrity checks. */
public final class Hash {

    private Hash() {}

    /** SHA-256 of a string, hex encoded. */
    public static String sha256(String input) {
        return sha256(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 of bytes, hex encoded. */
    public static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** SHA-256 of a file, hex encoded; returns an empty string when unreadable. */
    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Short (12 hex char) fingerprint, convenient for logs. */
    public static String shortHash(String input) {
        String full = sha256(input);
        return full.length() >= 12 ? full.substring(0, 12) : full;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
