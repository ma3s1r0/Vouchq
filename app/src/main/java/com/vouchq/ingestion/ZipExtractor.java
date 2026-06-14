package com.vouchq.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safely unpacks an uploaded zip into a fresh temp directory for the file-upload
 * ingestion fallback (MA3-76, 기획서 §5.1).
 *
 * <p>Defensive against hostile archives:
 * <ul>
 *   <li><b>Zip-slip:</b> every entry's resolved path must stay inside the temp
 *       root; an entry that escapes (e.g. {@code ../../etc/passwd}) is rejected.</li>
 *   <li><b>Bomb caps:</b> total uncompressed bytes and entry count are bounded so
 *       a malicious archive can't exhaust disk before scanning.</li>
 * </ul>
 *
 * The caller owns the returned directory and must delete it after ingestion.
 */
public final class ZipExtractor {

    /** Cap on total uncompressed bytes written (defensive against zip bombs). */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024; // 256 MiB
    /** Cap on the number of entries (files + dirs) extracted. */
    private static final int MAX_ENTRIES = 10_000;
    private static final int BUFFER_SIZE = 8192;

    private ZipExtractor() {}

    /** Thrown when an archive is rejected (zip-slip, too large, too many entries). */
    public static class UnsafeZipException extends RuntimeException {
        public UnsafeZipException(String message) {
            super(message);
        }
    }

    /**
     * Extracts the zip read from {@code in} into a newly created temp directory
     * and returns that directory. Closing {@code in} is the caller's
     * responsibility.
     *
     * @throws UnsafeZipException if an entry escapes the root or a cap is exceeded
     * @throws IOException        on I/O failure
     */
    public static Path extractToTempDir(InputStream in) throws IOException {
        Path root = Files.createTempDirectory("vouchq-upload-");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        long totalBytes = 0;
        int entries = 0;

        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new UnsafeZipException("Zip has too many entries (> " + MAX_ENTRIES + ")");
                }

                // Zip-slip guard: the resolved target must remain under the root.
                Path target = normalizedRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new UnsafeZipException(
                            "Zip entry escapes target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    zip.closeEntry();
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_TOTAL_BYTES) {
                            throw new UnsafeZipException(
                                    "Zip uncompressed size exceeds limit (" + MAX_TOTAL_BYTES + " bytes)");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
        return normalizedRoot;
    }

    /** Recursively delete a directory tree, ignoring errors (best-effort cleanup). */
    public static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup of a temp dir
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup of a temp dir
        }
    }
}
