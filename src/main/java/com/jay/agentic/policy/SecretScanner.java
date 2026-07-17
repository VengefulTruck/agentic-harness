package com.jay.agentic.policy;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects credentials in generated content.
 *
 * <p>This exists because the failure it prevents is not hypothetical. A model
 * asked to wire up an API client will, given the chance, write the key inline —
 * it has read a great deal of code that does exactly that. The generated file
 * looks correct, compiles, and works, and the secret is in git history before
 * anyone reads the diff.
 *
 * <p>Detection is pattern-based and therefore incomplete: it finds the shapes it
 * knows and misses novel ones. That is an accepted limitation rather than a
 * defect to apologise for — the alternative to an imperfect scanner is no
 * scanner. It is one layer, not the whole answer.
 */
public final class SecretScanner {

    /** A credential shape, with a name that explains the finding. */
    private record Signature(String name, Pattern pattern) {}

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("Anthropic API key", Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{20,}")),
            new Signature("OpenAI API key", Pattern.compile("sk-[A-Za-z0-9]{32,}")),
            new Signature("AWS access key id", Pattern.compile("AKIA[0-9A-Z]{16}")),
            new Signature("GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}")),
            new Signature("Google API key", Pattern.compile("AIza[0-9A-Za-z_\\-]{35}")),
            new Signature("Slack token", Pattern.compile("xox[baprs]-[0-9A-Za-z\\-]{10,}")),
            new Signature("private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new Signature("JWT", Pattern.compile("eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}")),

            // Assignment-shaped rather than vendor-shaped: a literal password or
            // secret in source. Deliberately narrow — it requires a quoted literal
            // of real length, because `String password;` is a field, not a leak.
            new Signature("hardcoded credential literal", Pattern.compile(
                    "(?i)(password|passwd|secret|api[_-]?key|token)\\s*=\\s*[\"'][^\"'\\s]{8,}[\"']"))
    );

    /** Placeholders that match a signature but are not secrets. Prevents useless denials. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(your[_-]?key|example|placeholder|xxx+|<[^>]+>|\\$\\{[^}]+\\}|changeme|dummy|redacted)");

    /** What was found, where, and what kind of thing it is. */
    public record Finding(String signatureName, int line, String excerpt) {
        @Override
        public String toString() {
            return signatureName + " at line " + line + ": " + excerpt;
        }
    }

    private SecretScanner() {
    }

    /** Scans content, returning every credential-shaped string it recognises. */
    public static List<Finding> scan(String content) {
        if (content == null || content.isBlank()) return List.of();

        List<Finding> findings = new java.util.ArrayList<>();
        String[] lines = content.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (PLACEHOLDER.matcher(line).find()) continue;

            for (Signature sig : SIGNATURES) {
                Matcher m = sig.pattern().matcher(line);
                if (m.find()) {
                    findings.add(new Finding(sig.name(), i + 1, redact(m.group())));
                }
            }
        }
        return List.copyOf(findings);
    }

    public static boolean isClean(String content) {
        return scan(content).isEmpty();
    }

    /**
     * Shows enough of a finding to locate it, never enough to use it.
     *
     * <p>The audit log is read by humans and stored on disk. A scanner that
     * reports the secret it found has moved the secret from one file to another
     * and called it a day.
     */
    private static String redact(String secret) {
        if (secret.length() <= 8) return "****";
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 2);
    }
}