package com.jay.agentic.policy;

import com.jay.agentic.hooks.HookRegistry;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates every tool invocation against the run's policies.
 *
 * <p>The single choke point. Agents do not call tools; they call this, and it
 * calls the tool if the rules permit. That indirection is the whole control —
 * an agent with a direct reference to a tool would make every policy in this
 * package advisory, and nothing in the type system would flag it.
 *
 * <p>Two decisions are worth stating plainly. First, <b>deny wins</b>: the
 * engine evaluates every policy and refuses if any refuses. Policies do not
 * override each other and there is no precedence order to reason about, because
 * a precedence order is a place for a mistake to hide.
 *
 * <p>Second, <b>fail closed</b>: a policy that throws is treated as a denial,
 * not as an abstention. A rule whose evaluation is broken tells you nothing
 * about whether the action is safe, and "we could not determine whether this
 * was allowed, so we did it" is not a defensible sentence in an incident review.
 */
public final class PolicyEngine {

    private final List<Policy> policies;

    /** A refused action, retained for the run summary and the audit trail. */
    public record Denial(StageId stage, String toolName, String policyName, String reason) {
        @Override
        public String toString() {
            return "[" + policyName + "] " + stage + " → " + toolName + ": " + reason;
        }
    }

    private final List<Denial> denials = new ArrayList<>();

    /**
     * Optional. A denial is worth telling an extension about — a security team may
     * want a refused action alerted on, not merely recorded. Optional rather than
     * required because the engine must work without any extension registered: a
     * guardrail that depends on the observability layer being wired up is a
     * guardrail that fails silently when it is not.
     */
    private HookRegistry hooks;

    public PolicyEngine(List<Policy> policies) {
        this.policies = List.copyOf(policies);
    }

    public PolicyEngine withHooks(HookRegistry hooks) {
        this.hooks = hooks;
        return this;
    }

    public static PolicyEngine standard() {
        return new PolicyEngine(Policies.standard());
    }

    /**
     * Invokes a tool if every policy permits it.
     *
     * @return the tool's result, or a failure describing which rule refused and why.
     *         The agent receives the denial as an ordinary tool failure — it does not
     *         get to distinguish "the tool broke" from "you were not allowed", because
     *         a model that knows which rule stopped it is a model being handed a map
     *         of the fence.
     */
    public Tool.Result invoke(Tool tool, Map<String, String> args, StageId stage, TaskState state) {
        Policy.Request request = new Policy.Request(stage, tool, args, state);

        for (Policy policy : policies) {
            Policy.Verdict verdict;
            try {
                verdict = policy.check(request);
            } catch (RuntimeException e) {
                verdict = Policy.Verdict.deny("policy '" + policy.name()
                        + "' could not be evaluated: " + e.getMessage());
            }

            if (!verdict.allowed()) {
                Denial denial = new Denial(stage, tool.name(), policy.name(), verdict.reason());
                record(denial, state);
                return Tool.Result.failed("refused: " + verdict.reason());
            }
        }

        return tool.invoke(args);
    }

    /**
     * A denial is a fact about the run, not just a log line.
     *
     * <p>It becomes an open question on the state, which means it surfaces in the
     * approval context a human reads and in the final summary. A guardrail that
     * fires silently has taught nobody anything — the interesting output of a
     * blocked action is the human finding out the agent tried.
     */
    private synchronized void record(Denial denial, TaskState state) {
        denials.add(denial);
        state.addOpenQuestion("Policy refused: " + denial);
        if (hooks != null) {
            hooks.fire(HookRegistry.Point.ON_POLICY_DENIAL, denial.stage(), state,
                    denial.policyName() + ": " + denial.reason());
        }
    }

    public synchronized List<Denial> denials() {
        return List.copyOf(denials);
    }

    /** The rules in force, for the architecture document and the run summary. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Policy p : policies) {
            sb.append("- ").append(p.name()).append(": ").append(p.rationale()).append('\n');
        }
        return sb.toString();
    }
}