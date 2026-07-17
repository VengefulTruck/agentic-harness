package com.jay.agentic.state;
/**
 * Lifecycle of a single stage within a run.
 *
 * <p>The non-terminal states are what make execution stateful rather than a
 * single linear pass. AWAITING_APPROVAL parks a run indefinitely pending a
 * human. STALE marks work that was invalidated by an upstream change, so the
 * orchestrator can re-plan instead of shipping output derived from inputs
 * that no longer exist.
 */
public enum StageStatus {

    /** Declared in the graph; dependencies not yet satisfied. */
    PENDING,

    /** Dependencies satisfied and the entry gate passed; eligible to run. */
    READY,

    RUNNING,

    /** Exit gate satisfied. */
    PASSED,

    /** Exit gate failed after all permitted attempts. */
    FAILED,

    /** An upstream dependency failed, so this stage will not be attempted. */
    BLOCKED,

    /** Parked at a human checkpoint. */
    AWAITING_APPROVAL,

    /** Not applicable to this run — e.g. EXPLORE on a greenfield task. */
    SKIPPED,

    /** Previously PASSED, but an input it consumed has since changed. */
    STALE,

    /** Its effects were compensated after a downstream failure. */
    ROLLED_BACK;

    /** True when no further work is expected for this stage in this run. */
    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == SKIPPED || this == ROLLED_BACK;
    }

    /** True when downstream stages may treat this stage's dependency as met. */
    public boolean isSuccessful() {
        return this == PASSED || this == SKIPPED;
    }
}
