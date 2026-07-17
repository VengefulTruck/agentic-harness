package com.jay.agentic.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A concrete output produced by a stage.
 *
 * <p>The content hash is load-bearing rather than bookkeeping. A downstream
 * stage records which artifact versions it consumed; when an upstream stage
 * re-runs and produces different content, the hash changes and the mismatch
 * is what marks the downstream work {@link StageStatus#STALE}. That is how
 * re-planning is detected mechanically instead of being asserted in a README.
 *
 * <p>Concretely: IMPLEMENT emits a PATCH. TEST and DOCS consume it. A human
 * rejects the patch, IMPLEMENT re-runs, new hash — and the tests now describe
 * code that no longer exists. The mismatch forces them to re-run rather than
 * carrying stale work into the release gate.
 */
public record Artifact(
        String id,
        StageId producedBy,
        Type type,
        /** Repo-relative path for PATCH, TEST_SOURCE and DOCUMENTATION; a logical name otherwise. */
        String name,
        String content,
        /** SHA-256 of {@link #content()}, hex-encoded. Derived — never set by hand. */
        String contentHash,
        Instant producedAt
) {

    public enum Type {
        /** A structured restatement of the requirement. */
        NORMALISED_REQUIREMENT,
        /** The decomposed task list with dependencies. */
        TASK_PLAN,
        /** Which modules, APIs and data flows are impacted. */
        IMPACT_ANALYSIS,
        /** A design note. */
        DESIGN,
        /** A unified diff proposed against the target repo. Never applied without approval. */
        PATCH,
        TEST_SOURCE,
        DOCUMENTATION,
        /** Output of a validation or security check. */
        REVIEW_REPORT,
        /** The final engineering summary. */
        RUN_SUMMARY
    }

    /** Canonical factory — computes the hash so it can never disagree with the content. */
    public static Artifact of(String id, StageId producedBy, Type type, String name, String content) {
        return new Artifact(id, producedBy, type, name, content, sha256(content), Instant.now());
    }

    public static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; unreachable on a conformant runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Short form for traces and logs. */
    public String shortHash() {
        return contentHash.substring(0, 12);
    }
}