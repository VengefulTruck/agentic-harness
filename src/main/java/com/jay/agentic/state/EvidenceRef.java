package com.jay.agentic.state;

/**
 * A pointer to the thing that justified a decision or an assumption.
 *
 * <p>The rubric asks for "evidence references" and "decision rationale" as
 * separate items. A rationale with no evidence pointer is just an assertion —
 * this type is what lets a reviewer check the harness's reasoning against the
 * artefact it actually read, after the run is over.
 */
public record EvidenceRef(
        Kind kind,
        /** File path, tool-call id, or LLM-call id, depending on {@link #kind()}. */
        String locator,
        /** Optional narrowing: a line range, a JSON pointer, or null. */
        String detail
) {

    public enum Kind {
        /** A file in the target repository. */
        SOURCE_FILE,
        /** The recorded output of a tool invocation. */
        TOOL_CALL,
        /** A recorded model call, referenced by id in the audit log. */
        LLM_CALL,
        /** A statement made by the human operator. */
        HUMAN_INPUT,
        /** A line in the original requirement text. */
        REQUIREMENT
    }

    public EvidenceRef {
        if (kind == null || locator == null || locator.isBlank()) {
            throw new IllegalArgumentException("kind and locator are required");
        }
    }

    public static EvidenceRef sourceFile(String path, String lines) {
        return new EvidenceRef(Kind.SOURCE_FILE, path, lines);
    }

    public static EvidenceRef toolCall(String callId) {
        return new EvidenceRef(Kind.TOOL_CALL, callId, null);
    }

    public static EvidenceRef llmCall(String callId) {
        return new EvidenceRef(Kind.LLM_CALL, callId, null);
    }

    public static EvidenceRef humanInput(String note) {
        return new EvidenceRef(Kind.HUMAN_INPUT, note, null);
    }

    public static EvidenceRef requirement(String excerpt) {
        return new EvidenceRef(Kind.REQUIREMENT, excerpt, null);
    }
}