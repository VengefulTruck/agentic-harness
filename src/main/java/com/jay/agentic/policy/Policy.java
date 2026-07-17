package com.jay.agentic.policy;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.Tool;

import java.util.Map;

/**
 * A rule about what may happen, evaluated before it happens.
 *
 * <p>Policies are separate from gates on purpose, and the distinction is worth
 * holding onto. A gate asks "is this stage's work good enough?" — it is about
 * quality, and it runs after the work. A policy asks "is this action permitted?"
 * — it is about authority, and it runs before. Merging them would mean the only
 * way to stop a forbidden action is to notice it in the output afterwards, which
 * for anything with side effects is too late.
 *
 * <p>Each policy is a small, independently testable rule with a name. The name
 * matters: a denial that says "blocked by no-secrets-in-generated-code" is
 * actionable, one that says "policy violation" is not.
 */
public interface Policy {

    /** Stable identifier, recorded in the audit log on every denial. */
    String name();

    /** What the rule exists to prevent, in one line. Read by humans, not by code. */
    String rationale();

    /** The context an authority decision is made against. */
    record Request(
            /** The stage attempting the action. */
            StageId stage,
            /** The tool being invoked. */
            Tool tool,
            /** The arguments it would be invoked with. */
            Map<String, String> args,
            /** The run this is happening in. */
            TaskState state
    ) {
        public Request {
            args = Map.copyOf(args);
        }
    }

    /** Permitted, or refused with a reason a human can act on. */
    record Verdict(boolean allowed, String reason) {

        public static Verdict allow() {
            return new Verdict(true, null);
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    Verdict check(Request request);
}