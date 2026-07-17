package com.jay.agentic.state;

import java.time.Instant;
import java.util.List;

/**
 * Something the harness had to take as true in order to proceed.
 *
 * <p>Ambiguity is not an error condition — a requirement like "make the links
 * safer" is under-specified by nature, and refusing to move is as useless as
 * guessing silently. The middle path is to guess, and to make the guess a
 * first-class tracked object rather than a sentence buried in a summary.
 *
 * <p>Because an assumption is queryable, the release gate can refuse to pass
 * while an OPEN assumption of HIGH risk remains outstanding. That is the
 * difference between disclosing a guess and being governed by it.
 */
public record Assumption(
        String id,
        /** The stage that was forced into the assumption. */
        StageId madeBy,
        /** What is being assumed, e.g. "the rate limit is per-IP, not per-API-key". */
        String statement,
        /** Why the harness could not simply know — the ambiguity being papered over. */
        String reason,
        Risk risk,
        Status status,
        List<EvidenceRef> evidence,
        Instant recordedAt,
        /** How a human settled it. Null while OPEN. */
        String resolution
) {

    /** What it costs if the guess is wrong. Drives whether a human is asked. */
    public enum Risk {
        /** Wrong guess costs a trivial rework. */
        LOW,
        /** Wrong guess invalidates a design decision. */
        MEDIUM,
        /** Wrong guess ships incorrect or unsafe behaviour. */
        HIGH
    }

    public enum Status { OPEN, CONFIRMED, REJECTED }

    public Assumption {
        evidence = List.copyOf(evidence);
    }

    /** Records a new, unresolved assumption. */
    public static Assumption open(String id, StageId madeBy, String statement,
                                  String reason, Risk risk, List<EvidenceRef> evidence) {
        return new Assumption(id, madeBy, statement, reason, risk,
                Status.OPEN, evidence, Instant.now(), null);
    }

    /** Returns a resolved copy. The original is retained in the ledger — assumptions are never erased. */
    public Assumption resolve(Status outcome, String resolution) {
        if (outcome == Status.OPEN) {
            throw new IllegalArgumentException("resolve() requires CONFIRMED or REJECTED");
        }
        return new Assumption(id, madeBy, statement, reason, risk,
                outcome, evidence, recordedAt, resolution);
    }

    /** True when this assumption must be settled by a human before release. */
    public boolean blocksRelease() {
        return status == Status.OPEN && risk == Risk.HIGH;
    }
}