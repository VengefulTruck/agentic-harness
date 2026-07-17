package com.jay.agentic.orchestration;

import com.jay.agentic.state.TaskState;

/**
 * A precondition or an acceptance criterion attached to a stage.
 *
 * <p>The assignment asks for entry and exit gates, and the distinction between
 * them is the point. An <em>entry</em> gate asks "is it sensible to start?" —
 * it can defer or skip a stage. An <em>exit</em> gate asks "is this output good
 * enough to hand downstream?" — it can fail a stage that ran to completion but
 * produced rubbish.
 *
 * <p>Without exit gates, an agent that returns an empty patch counts as success
 * and the run marches on. The gate is what makes "the stage ran" and "the stage
 * worked" different propositions.
 */
@FunctionalInterface
public interface Gate {

    /** The outcome of evaluating a gate, with a reason a human can read. */
    record Verdict(boolean passed, String reason) {

        public static Verdict pass() {
            return new Verdict(true, "ok");
        }

        public static Verdict pass(String reason) {
            return new Verdict(true, reason);
        }

        public static Verdict fail(String reason) {
            return new Verdict(false, reason);
        }
    }

    Verdict evaluate(TaskState state);

    /** A gate that always passes. The default for stages with no precondition. */
    static Gate open() {
        return state -> Verdict.pass("no condition");
    }

    /** Both gates must pass. The reason returned is that of the first failure. */
    default Gate and(Gate other) {
        return state -> {
            Verdict first = this.evaluate(state);
            return first.passed() ? other.evaluate(state) : first;
        };
    }
}