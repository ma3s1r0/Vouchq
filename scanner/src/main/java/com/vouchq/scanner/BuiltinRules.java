package com.vouchq.scanner;

import java.util.List;

import static com.vouchq.scanner.Category.DANGEROUS_COMMAND;
import static com.vouchq.scanner.Category.DATA_EXFILTRATION;
import static com.vouchq.scanner.Category.PROMPT_INJECTION;
import static com.vouchq.scanner.Category.SECRET;
import static com.vouchq.scanner.Severity.CRITICAL;
import static com.vouchq.scanner.Severity.WARN;

/**
 * The default rule set. Intentionally conservative on the CRITICAL bar so clean
 * skills pass; broaden via additional {@link Rule}s passed to {@link SkillScanner}.
 */
public final class BuiltinRules {

    private BuiltinRules() {}

    private static final List<Rule> RULES = List.of(
            // --- Prompt injection: attempts to steer or silence the agent ---
            new RegexRule("injection.override", PROMPT_INJECTION, CRITICAL,
                    "(?i)ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+instructions", false),
            new RegexRule("injection.conceal", PROMPT_INJECTION, CRITICAL,
                    "(?i)(do\\s*not|don'?t|never)\\s+(tell|inform|notify|mention\\s+to|reveal\\s+to)\\s+the\\s+user", false),
            new RegexRule("injection.exfil-directive", PROMPT_INJECTION, CRITICAL,
                    "(?i)(exfiltrate|leak|steal|send)\\b.{0,40}(secret|credential|token|api[_-]?key|password|\\.ssh)", false),
            // Tool-poisoning: a hidden instruction that fires the agent on a user
            // request but redirects it to do something else ("when the user asks
            // X, do Y instead"). High-confidence injection phrasing → CRITICAL.
            new RegexRule("injection.tool-poisoning", PROMPT_INJECTION, CRITICAL,
                    "(?i)when\\s+the\\s+user\\s+asks?\\b.{0,80}\\b(instead|secretly|actually|first\\s+run|also\\s+(run|send|fetch))", false),

            // --- Secret exposure (masked in evidence) ---
            new RegexRule("secret.aws-access-key", SECRET, CRITICAL,
                    "AKIA[0-9A-Z]{16}", true),
            new RegexRule("secret.private-key", SECRET, CRITICAL,
                    "-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----", false),
            new RegexRule("secret.known-token", SECRET, CRITICAL,
                    "(xox[baprs]-[A-Za-z0-9-]{10,}|ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{20,})", true),
            // Google API key (AIza + 35 chars). Distinct shape → high confidence.
            new RegexRule("secret.google-api-key", SECRET, CRITICAL,
                    "AIza[0-9A-Za-z_\\-]{35}", true),
            // Generic key/secret assignment. Tuned for FPs (MA3-94): the value must
            // be quoted (real config/code quotes secrets; prose rarely does) AND not
            // an obvious placeholder (your-…, xxx, changeme, example, redacted, <…>).
            new RegexRule("secret.generic", SECRET, WARN,
                    "(?i)(api[_-]?key|secret|access[_-]?token|password)\\s*[:=]\\s*"
                            + "['\"](?!(your|my|the|xxx|changeme|example|redacted|placeholder|<))[A-Za-z0-9_\\-]{16,}['\"]", true),

            // --- Data exfiltration ---
            new RegexRule("exfil.curl-secret", DATA_EXFILTRATION, CRITICAL,
                    "(?i)(curl|wget)\\b.{0,80}(\\$\\(.*cat|/etc/passwd|~/\\.ssh|id_rsa|\\.aws/credentials)", false),
            // SSRF to the cloud-instance metadata endpoint — used to lift IAM creds
            // from EC2/GCE/Azure. Almost never legitimate in a skill → CRITICAL.
            new RegexRule("exfil.cloud-metadata", DATA_EXFILTRATION, CRITICAL,
                    "169\\.254\\.169\\.254|metadata\\.google\\.internal", false),
            // Outbound transfer via curl/wget. Tuned for FPs (MA3-94): only fire when
            // the command actually SENDS/UPLOADS data (-d/--data, -F/--form, -T/
            // --upload-file, -X POST/PUT, or an -o/-O download), not every doc that
            // merely shows `curl https://...`.
            new RegexRule("exfil.curl", DATA_EXFILTRATION, WARN,
                    "(?i)\\b(curl|wget)\\b.{0,120}(-d\\b|--data|-F\\b|--form|-T\\b|--upload-file|-X\\s+(POST|PUT)|-O\\b|-o\\b).{0,120}https?://"
                            + "|(?i)\\b(curl|wget)\\b.{0,120}https?://.{0,120}(-d\\b|--data|-F\\b|--form|-T\\b|--upload-file)", false),
            new RegexRule("exfil.netcat", DATA_EXFILTRATION, WARN,
                    "(?i)(\\bnc\\b\\s+-|/dev/tcp/)", false),
            new RegexRule("exfil.http-post", DATA_EXFILTRATION, WARN,
                    "(?i)(requests\\.post|fetch|axios\\.post)\\s*\\(?\\s*['\"]?https?://", false),
            // Decode-then-execute a base64 payload — a common smuggling pattern.
            // Fires on an explicit decode call (base64 -d, atob, b64decode) OR a very
            // long (80+) base64 run ending in '='-padding (a near-certain blob, which
            // a bare alnum URL/hash/id of similar length won't have). WARN, not crit.
            new RegexRule("exfil.base64-blob", DATA_EXFILTRATION, WARN,
                    "(?i)(base64\\s+(-d|--decode)|atob\\s*\\(|b64decode)|[A-Za-z0-9+/]{80,}={1,2}", false),

            // --- Dangerous commands / broad access ---
            new RegexRule("danger.rm-rf", DANGEROUS_COMMAND, CRITICAL,
                    "(?i)\\brm\\s+-[a-z]*r[a-z]*f", false),
            new RegexRule("danger.shell-exec", DANGEROUS_COMMAND, WARN,
                    "(?i)(os\\.system|subprocess\\.(call|run|Popen)|child_process|shell\\s*=\\s*True)", false),
            new RegexRule("danger.eval", DANGEROUS_COMMAND, WARN,
                    "(?i)\\b(eval|exec)\\s*\\(", false),
            new RegexRule("danger.chmod-777", DANGEROUS_COMMAND, WARN,
                    "chmod\\s+(-R\\s+)?777", false),
            new RegexRule("danger.sensitive-file", DANGEROUS_COMMAND, WARN,
                    "(/etc/passwd|/etc/shadow|~/\\.ssh|id_rsa|\\.aws/credentials)", false)
    );

    public static List<Rule> all() {
        return RULES;
    }
}
