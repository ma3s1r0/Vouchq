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
    public ResponseEntity<String> script(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "claude") String target,
                                         @RequestParam(defaultValue = "project") String scope,
                                         Authentication authentication) {
        String tgt = target.trim().toLowerCase();
        if (!tgt.equals("claude") && !tgt.equals("cursor")) {
            throw new IllegalArgumentException("Unknown install target: " + target + " (claude|cursor)");
        }
        String scp = scope.trim().toLowerCase();
        if (!scp.equals("project") && !scp.equals("user")) {
            throw new IllegalArgumentException("Unknown install scope: " + scope + " (project|user)");
        }
        // Cursor has no file-based user scope — user rules live in Cursor's app
        // settings (text), not on disk. Only project (.cursor/rules/) is installable.
        if (tgt.equals("cursor") && scp.equals("user")) {
            throw new IllegalArgumentException(
                    "Cursor has no file-based user scope — set Cursor user rules in app settings. Use scope=project.");
        }
        UUID orgId = currentOrg.require();
        ApiDtos.InstallManifest manifest = install.buildManifest(orgId, id);
        String actor = authentication != null ? authentication.getName() : "system";
        install.recordInstallServed(orgId, id, actor, manifest);

        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/x-shellscript"))
                .body(renderScript(base, id, manifest, tgt, scp));
    }

    /**
     * Render the POSIX install script. Both targets fetch each file by hash and
     * verify it (verify-then-adapt): Claude writes the bytes verbatim into
     * {@code .claude/skills/}; Cursor renders each skill's {@code SKILL.md} into a
     * {@code .cursor/rules/<name>.mdc} rule (frontmatter swapped) and warns that
     * bundled scripts aren't installed (Cursor rules don't run scripts).
     */
    private static String renderScript(String base, UUID sourceId,
                                       ApiDtos.InstallManifest m, String target, String scope) {
        boolean cursor = target.equals("cursor");
        boolean userScope = scope.equals("user");
        StringBuilder plan = new StringBuilder();
        for (ApiDtos.InstallSkill skill : m.skills()) {
            if (cursor) {
                appendCursorPlan(plan, skill);
            } else {
                for (ApiDtos.InstallFile f : skill.files()) {
                    plan.append("install_file ")
                            .append(shquote(f.sha256())).append(' ')
                            .append(shquote(skill.name() + "/" + f.path())).append('\n');
                }
            }
        }
        String excluded = m.excluded().total() == 0 ? ""
                : "echo \"vouchq: skipped " + m.excluded().total()
                        + " skill(s) not approved (pending/drifted/blocked)\"\n";
        String body = m.skills().isEmpty()
                ? "echo \"vouchq: nothing to install — no approved skills in this source.\"\n"
                : plan.toString();

        // Cursor: project rules only. Claude: project (./.claude) or user (~/.claude).
        String destDefault = cursor ? "${VOUCHQ_RULES_DIR:-.cursor/rules}"
                : userScope ? "${VOUCHQ_SKILLS_DIR:-$HOME/.claude/skills}"
                : "${VOUCHQ_SKILLS_DIR:-.claude/skills}";
        String writer = cursor ? CURSOR_WRITER : CLAUDE_WRITER;
        String installing = cursor ? "Cursor rules into" : "vouched skills into";

        return """
                #!/bin/sh
                # vouchq vouched install (%s) — source %s (%s)
                # Installs only APPROVED + pinned Skills. Every file is fetched from
                # vouchq (the exact governed bytes) and hash-verified before writing.
                set -eu

                BASE=%s
                SOURCE=%s
                DEST="%s"
                AUTH="${VOUCHQ_AUTH:-}"

                fetch() { curl -fsSL ${AUTH:+-u "$AUTH"} "$1"; }
                sha256_of() {
                  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
                  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
                  else echo "vouchq: need sha256sum or shasum" >&2; exit 1; fi
                }

                # Fetch a file by hash and verify it; echoes the verified temp path or aborts.
                fetch_verified() {
                  sha="$1"; tmp="$(mktemp)"
                  fetch "$BASE/api/sources/$SOURCE/install/file?sha256=$sha" > "$tmp"
                  got="$(sha256_of "$tmp")"
                  if [ "$got" != "$sha" ]; then
                    echo "vouchq: hash mismatch (want $sha, got $got) — aborting" >&2
                    rm -f "$tmp"; exit 1
                  fi
                  printf '%%s' "$tmp"
                }
                %s
                echo "vouchq: installing %s $DEST"
                %s%s
                echo "vouchq: done."
                """.formatted((cursor ? "cursor" : "claude") + ", " + scope + " scope",
                        label(m), sourceId,
                        shquote(base), shquote(sourceId.toString()), destDefault,
                        writer, installing, excluded, body);
    }

    /** Claude writer: the verified bytes go into {@code $DEST/<skill>/<path>} verbatim. */
    private static final String CLAUDE_WRITER = """
            install_file() {
              sha="$1"; rel="$2"; out="$DEST/$rel"
              mkdir -p "$(dirname "$out")"
              tmp="$(fetch_verified "$sha")"
              mv "$tmp" "$out"
              echo "  ok $rel"
            }""";

    /**
     * Cursor writer: render a verified SKILL.md into {@code $DEST/<name>.mdc},
     * replacing its frontmatter with a Cursor rule header (description + manual
     * attach). The body (everything after the first --- block) is kept verbatim.
     */
    private static final String CURSOR_WRITER = """
            cursor_rule() {
              sha="$1"; name="$2"; desc="$3"; out="$DEST/$name.mdc"
              mkdir -p "$(dirname "$out")"
              tmp="$(fetch_verified "$sha")"
              {
                printf -- '---\\ndescription: "%s"\\nalwaysApply: false\\n---\\n' "$desc"
                awk 'NR==1 && $0=="---"{f=1;next} f==1 && $0=="---"{f=2;next} f!=1{print}' "$tmp"
              } > "$out"
              rm -f "$tmp"
              echo "  ok $name.mdc"
            }""";

    /** One skill → a cursor_rule call for its SKILL.md, + a warning for any extra files. */
    private static void appendCursorPlan(StringBuilder plan, ApiDtos.InstallSkill skill) {
        ApiDtos.InstallFile skillMd = skill.files().stream()
                .filter(f -> f.path().equalsIgnoreCase("SKILL.md"))
                .findFirst().orElse(null);
        if (skillMd == null) {
            plan.append("echo ").append(shquote(
                    "vouchq: " + skill.name() + " has no SKILL.md — skipped")).append('\n');
            return;
        }
        plan.append("cursor_rule ")
                .append(shquote(skillMd.sha256())).append(' ')
                .append(shquote(skill.name())).append(' ')
                .append(shquote(cursorDescription(skill.description()))).append('\n');
        long extra = skill.files().stream()
                .filter(f -> !f.path().equalsIgnoreCase("SKILL.md")).count();
        if (extra > 0) {
            plan.append("echo ").append(shquote("vouchq: " + skill.name() + " — " + extra
                    + " bundled file(s) skipped (Cursor rules don't run scripts)")).append('\n');
        }
    }

    /** One-line, YAML-safe description for the Cursor rule frontmatter. */
    private static String cursorDescription(String desc) {
        if (desc == null) {
            return "";
        }
        return desc.replaceAll("\\s+", " ").replace('"', '\'').trim();
    }

    private static String label(ApiDtos.InstallManifest m) {
        return m.source() != null ? m.source().label() : "?";
    }

    /** Single-quote a value for POSIX sh (close-quote, escaped quote, reopen). */
    private static String shquote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
