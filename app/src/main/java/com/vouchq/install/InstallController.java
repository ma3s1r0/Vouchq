package com.vouchq.install;

import com.vouchq.api.ApiDtos;
import com.vouchq.tenancy.CurrentOrg;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

/**
 * Group one-click install (MA3-137/138): the "vouched-out" distribution surface.
 * For a source (repo) it serves the install <em>manifest</em> (the pinned lock),
 * individual approved file bytes by content hash, and a ready-to-run POSIX
 * {@code install.sh} that drops the approved Skills into {@code .claude/skills/}.
 *
 * <p>RBAC: these are GET reads (VIEWER+); HTTP Basic works for the {@code curl|sh}
 * flow ({@code SecurityConfig}). The script reads creds from {@code $VOUCHQ_AUTH}.
 */
@RestController
public class InstallController {

    private final InstallService install;
    private final CurrentOrg currentOrg;

    public InstallController(InstallService install, CurrentOrg currentOrg) {
        this.install = install;
        this.currentOrg = currentOrg;
    }

    /** The install manifest (lock) for a source — APPROVED + pinned skills only. */
    @GetMapping("/api/sources/{id}/install/manifest")
    public ApiDtos.InstallManifest manifest(@PathVariable UUID id) {
        return install.buildManifest(currentOrg.require(), id);
    }

    /** Raw UTF-8 bytes of one approved file, addressed by its SHA-256. */
    @GetMapping("/api/sources/{id}/install/file")
    public ResponseEntity<String> file(@PathVariable UUID id, @RequestParam String sha256) {
        return install.fileContent(currentOrg.require(), id, sha256)
                .map(content -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(content))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * A self-contained POSIX install script with the file plan baked in. Running
     * it fetches each approved file by hash, re-verifies SHA-256 before writing,
     * and refuses on any mismatch. Issuing the script is the "install" moment, so
     * it records the distribution event (MA3-140).
     */
    @GetMapping(value = "/api/sources/{id}/install.sh", produces = "text/x-shellscript")
    public ResponseEntity<String> script(@PathVariable UUID id, Authentication authentication) {
        UUID orgId = currentOrg.require();
        ApiDtos.InstallManifest manifest = install.buildManifest(orgId, id);
        String actor = authentication != null ? authentication.getName() : "system";
        install.recordInstallServed(orgId, id, actor, manifest);

        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/x-shellscript"))
                .body(renderScript(base, id, manifest));
    }

    /** Render the POSIX script; the plan (sha → relative path) is embedded inline. */
    private static String renderScript(String base, UUID sourceId, ApiDtos.InstallManifest m) {
        StringBuilder plan = new StringBuilder();
        for (ApiDtos.InstallSkill skill : m.skills()) {
            for (ApiDtos.InstallFile f : skill.files()) {
                // skill name + file path form the destination under .claude/skills/.
                plan.append("install_file ")
                        .append(shquote(f.sha256())).append(' ')
                        .append(shquote(skill.name() + "/" + f.path())).append('\n');
            }
        }
        String excluded = m.excluded().total() == 0 ? ""
                : "echo \"vouchq: skipped " + m.excluded().total()
                        + " skill(s) not approved (pending/drifted/blocked)\"\n";
        String body = m.skills().isEmpty()
                ? "echo \"vouchq: nothing to install — no approved skills in this source.\"\n"
                : plan.toString();

        return """
                #!/bin/sh
                # vouchq vouched install — source %s (%s)
                # Installs only APPROVED + pinned Skills. Every file is fetched from
                # vouchq (the exact governed bytes) and hash-verified before writing.
                set -eu

                BASE=%s
                SOURCE=%s
                DEST="${VOUCHQ_SKILLS_DIR:-.claude/skills}"
                AUTH="${VOUCHQ_AUTH:-}"

                fetch() { curl -fsSL ${AUTH:+-u "$AUTH"} "$1"; }
                sha256_of() {
                  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
                  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
                  else echo "vouchq: need sha256sum or shasum" >&2; exit 1; fi
                }

                install_file() {
                  sha="$1"; rel="$2"; out="$DEST/$rel"
                  mkdir -p "$(dirname "$out")"
                  tmp="$(mktemp)"
                  fetch "$BASE/api/sources/$SOURCE/install/file?sha256=$sha" > "$tmp"
                  got="$(sha256_of "$tmp")"
                  if [ "$got" != "$sha" ]; then
                    echo "vouchq: hash mismatch for $rel (want $sha, got $got) — aborting" >&2
                    rm -f "$tmp"; exit 1
                  fi
                  mv "$tmp" "$out"
                  echo "  ok $rel"
                }

                echo "vouchq: installing vouched skills into $DEST"
                %s%s
                echo "vouchq: done."
                """.formatted(label(m), sourceId, shquote(base), shquote(sourceId.toString()),
                        excluded, body);
    }

    private static String label(ApiDtos.InstallManifest m) {
        return m.source() != null ? m.source().label() : "?";
    }

    /** Single-quote a value for POSIX sh (close-quote, escaped quote, reopen). */
    private static String shquote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
