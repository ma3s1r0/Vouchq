package com.vouchq.ingestion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the zip-upload unpacking step (MA3-76): a well-formed archive
 * extracts to a temp dir; a zip-slip entry is rejected.
 */
class ZipExtractorTest {

    private Path extracted;

    @AfterEach
    void cleanup() {
        ZipExtractor.deleteRecursively(extracted);
    }

    @Test
    void extractsEntriesUnderTempRoot() throws IOException {
        byte[] zip = zip(
                entry("greeter/SKILL.md", "---\nname: greeter\n---\nhello\n"),
                entry("greeter/run.sh", "echo hi\n"));

        extracted = ZipExtractor.extractToTempDir(new ByteArrayInputStream(zip));

        Path skill = extracted.resolve("greeter/SKILL.md");
        Path script = extracted.resolve("greeter/run.sh");
        assertThat(Files.exists(skill)).isTrue();
        assertThat(Files.exists(script)).isTrue();
        assertThat(Files.readString(skill)).contains("name: greeter");
        // Every extracted file stays inside the temp root.
        assertThat(skill.normalize().startsWith(extracted)).isTrue();
    }

    @Test
    void rejectsZipSlipEntry() {
        // An entry whose path escapes the extraction root.
        byte[] zip = zip(entry("../../../tmp/evil.txt", "pwned"));

        assertThatThrownBy(() ->
                ZipExtractor.extractToTempDir(new ByteArrayInputStream(zip)))
                .isInstanceOf(ZipExtractor.UnsafeZipException.class)
                .hasMessageContaining("escapes");
    }

    // --- helpers ----------------------------------------------------------

    private record Entry(String name, String content) {}

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static byte[] zip(Entry... entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(e.name()));
                zos.write(e.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return bos.toByteArray();
    }
}
