package com.vouchq.verify;

import com.vouchq.api.ApiDtos;
import com.vouchq.ingestion.ZipExtractor;
import com.vouchq.tenancy.CurrentOrg;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * CI verify / build gate endpoint (MA3-98). A consumer's CI uploads its
 * checked-out repo as a zip; vouchq parses every Skill and reports whether each
 * is an APPROVED + pinned version. Read-only: nothing is persisted and vouchq
 * never enters the agent's data path — the CI is simply a registry reader.
 *
 * <p>RBAC: VIEWER+ (a least-privilege CI token), wired in {@code SecurityConfig}
 * ahead of the general {@code POST /api/**} MEMBER+ rule.
 */
@RestController
public class VerifyController {

    private final VerifyService verify;
    private final CurrentOrg currentOrg;

    public VerifyController(VerifyService verify, CurrentOrg currentOrg) {
        this.verify = verify;
        this.currentOrg = currentOrg;
    }

    @PostMapping(path = "/api/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiDtos.VerifyResult verify(@RequestParam("file") MultipartFile file) {
        UUID orgId = currentOrg.require();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required (a zip of the checked-out repo)");
        }
        Path tempDir = null;
        try (InputStream in = file.getInputStream()) {
            tempDir = ZipExtractor.extractToTempDir(in);
            return verify.verify(orgId, tempDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded zip: " + e.getMessage());
        } finally {
            ZipExtractor.deleteRecursively(tempDir);
        }
    }
}
