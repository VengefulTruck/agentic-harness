package com.jay.agentic.state;

import java.time.Instant;
import java.util.List;

/**
 * A choice made during the run, recorded with the alternatives it beat.
 *
 * <p>This is the "decision lineage" the assignment asks for. Recording only
 * the chosen option produces a log; recording the rejected options and why
 * produces something a reviewer can audit. It is also what makes the interview
 * question "why did you do it this way?" answerable from data rather than
 * from memory.
 *
 * <p>{@link Actor} is the load-bearing field: it marks the boundary between
 * what the agent decided on its own and what a human was asked to settle.
 * Read across a whole run, it is a map of where autonomy actually stopped.
 */
public record Decision(
        String id,
        StageId stage,
        /** The question that was open, e.g. "where should rate limiting be enforced?" */
        String question,
        /** The option taken. */
        String choice,
        /** Why this option beat the others. */
        String rationale,
        /** Options considered and discarded, each with its reason. */
        List<Alternative> alternatives,
        List<EvidenceRef> evidence,
        Actor decidedBy,
        Instant decidedAt
) {

    /** Who made the call. */
    public enum Actor { AGENT, HUMAN }

    /** An option that was considered and rejected. */
    public record Alternative(String option, String rejectedBecause) {}

    public Decision {
        alternatives = List.copyOf(alternatives);
        evidence = List.copyOf(evidence);
    }

    public static Decision byAgent(String id, StageId stage, String question, String choice,
                                   String rationale, List<Alternative> alternatives,
                                   List<EvidenceRef> evidence) {
        return new Decision(id, stage, question, choice, rationale,
                alternatives, evidence, Actor.AGENT, Instant.now());
    }

    /** A decision the harness escalated rather than made. Evidence is the human's own words. */
    public static Decision byHuman(String id, StageId stage, String question,
                                   String choice, String rationale) {
        return new Decision(id, stage, question, choice, rationale,
                List.of(), List.of(EvidenceRef.humanInput(choice)), Actor.HUMAN, Instant.now());
    }
}