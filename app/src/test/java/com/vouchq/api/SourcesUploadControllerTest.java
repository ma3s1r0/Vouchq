package com.vouchq.api;

import com.vouchq.ingestion.GitIngestionService;
import com.vouchq.ingestion.RescanService;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the zip-upload endpoint (MA3-76): unpacking an uploaded zip and
 * delegating ingestion of the unzipped dir to
 * {@link GitIngestionService#ingestLocalPath(UUID, String, Path, Source.Type)}
 * with {@code FILE_UPLOAD}, then cleaning up the temp dir.
 */
class SourcesUploadControllerTest {

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    private GitIngestionService ingestion;
    private SourceRepository sources;
    private SourcesController controller;

    @BeforeEach
    void setUp() {
        ingestion = mock(GitIngestionService.class);
        sources = mock(SourceRepository.class);
        RescanService rescan = mock(RescanService.class);
        com.vouchq.tenancy.CurrentOrg currentOrg = mock(com.vouchq.tenancy.CurrentOrg.class);
        when(currentOrg.require()).thenReturn(ORG_ID);

        com.vouchq.credentials.CredentialCipher cipher =
                new com.vouchq.credentials.AesGcmCredentialCipher(
                        new com.vouchq.credentials.CredentialProperties(null));
        controller = new SourcesController(ingestion, rescan, sources, cipher, currentOrg);
    }

    @Test
    void uploadUnzipsAndDelegatesToIngestLocalPathAsFileUpload() throws IOException {
        byte[] zip = zip("greeter/SKILL.md", "---\nname: greeter\n---\nhello\n");
        MockMultipartFile file = new MockMultipartFile(
                "file", "skills.zip", "application/zip", zip);

        // Capture the temp dir handed to ingestLocalPath; assert it holds the unzipped content.
        ArgumentCaptor<Path> dirCap = ArgumentCaptor.forClass(Path.class);
        when(ingestion.ingestLocalPath(eq(ORG_ID), eq("upload://skills.zip"),
                dirCap.capture(), eq(Source.Type.FILE_UPLOAD)))
                .thenAnswer(inv -> {
                    Path dir = inv.getArgument(2);
                    // The unzipped SKILL.md exists at call time.
                    assertThat(Files.exists(dir.resolve("greeter/SKILL.md"))).isTrue();
                    return new GitIngestionService.IngestionResult(SOURCE_ID, 1, 1, 1, 0);
                });
        when(sources.findById(SOURCE_ID)).thenReturn(Optional.of(
                new Source(SOURCE_ID, ORG_ID, Source.Type.FILE_UPLOAD, "upload://skills.zip", null)));

        ApiDtos.SourceUploadResult result = controller.upload(file);

        assertThat(result.source().type()).isEqualTo("FILE_UPLOAD");
        assertThat(result.source().uri()).isEqualTo("upload://skills.zip");
        assertThat(result.ingestion().skillsParsed()).isEqualTo(1);
        assertThat(result.ingestion().toolsCreated()).isEqualTo(1);
        // Temp dir is cleaned up after the request.
        assertThat(Files.exists(dirCap.getValue())).isFalse();
    }

    @Test
    void emptyFileIsRejected() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.zip", "application/zip", new byte[0]);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.upload(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ----------------------------------------------------------

    private static byte[] zip(String name, String content) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return bos.toByteArray();
    }
}
