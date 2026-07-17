package com.jay.agentic.tools;

import com.jay.agentic.state.EvidenceRef;

import java.util.Map;

/**
 * A capability an agent may invoke against the outside world.
 *
 * <p>Tools exist as a named, registered abstraction rather than agents calling
 * {@code Files.readString} directly, and the reason is governance rather than
 * tidiness. Every reach outside the harness passes through one interface, which
 * means there is exactly one place to enforce policy, one place to record the
 * call for the audit log, and one list a reviewer can read to answer "what can
 * this thing actually do?"
 *
 * <p>An agent that could open a file directly would make the policy engine
 * advisory. The narrow waist is the control.
 */
public interface Tool {

    /** Stable identifier, used in policy rules and audit entries. */
    String name();

    /** What this tool does, in one line. Surfaced to the model and to reviewers. */
    String description();

    /**
     * Whether this tool changes anything outside the harness.
     *
     * <p>Drives policy: read-only tools run freely, mutating tools require an
     * approved plan. This is the recommendation-versus-execution split the
     * assignment asks for, expressed as a property of the tool rather than as
     * a convention agents are trusted to follow.
     */
    boolean isMutating();

    /** The outcome of a call, with a pointer for the evidence trail. */
    record Result(
            boolean success,
            String output,
            String failureReason,
            /** How a decision built on this call cites it. */
            EvidenceRef evidence
    ) {
        public static Result ok(String output, EvidenceRef evidence) {
            return new Result(true, output, null, evidence);
        }

        public static Result failed(String reason) {
            return new Result(false, null, reason, null);
        }
    }

    /**
     * Invokes the tool.
     *
     * @param args named arguments; each tool documents what it expects
     * @return the result — never throws for expected failures such as a missing
     *         file. A tool that throws is broken; a tool that cannot do the job
     *         returns {@link Result#failed}. The distinction matters because the
     *         first is a harness bug and the second is a normal fact the agent
     *         must reason about.
     */
    Result invoke(Map<String, String> args);
}