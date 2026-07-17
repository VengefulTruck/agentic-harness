package com.jay.agentic.hooks;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Extension points fired at each lifecycle moment of a run.
 *
 * <p>The purpose is to let the harness be extended without being edited. A team
 * that wants to post approvals to Slack, export metrics to Prometheus, or refuse
 * runs outside business hours should be able to register a hook rather than
 * modify the orchestrator — because every such modification is a merge conflict
 * with the next person who wants a different one.
 *
 * <p>Two rules make that safe, and both are deliberate:
 *
 * <p><b>A hook cannot fail the run.</b> Exceptions are caught and reported, never
 * propagated. A hook is an observer of the workflow, not a participant in it; a
 * Slack outage must not fail a code review. The gates and policies are where
 * something is allowed to stop the run, and they are deliberately not extensible
 * by this mechanism — a governance layer that any plugin can weaken is not one.
 *
 * <p><b>A hook cannot mutate the run.</b> It receives the state to read, and its
 * return value is ignored. If hooks could write, the order of registration would
 * silently become part of the workflow's semantics.
 */
public final class HookRegistry {

    /** Where a hook can attach. Covers every stage of the lifecycle. */
    public enum Point {
        /** Before any stage runs. Configuration checks, run announcements. */
        RUN_START,
        /** A stage is about to execute — includes requirement analysis, code search,
         *  planning, code and test generation, security checks, and validation. */
        BEFORE_STAGE,
        /** A stage finished successfully. */
        AFTER_STAGE,
        /** A stage failed, after exhausting its retries. */
        ON_FAILURE,
        /** A human answered a checkpoint. */
        ON_APPROVAL,
        /** A stage was invalidated because an upstream output changed. */
        ON_REPLAN,
        /** A tool call was refused by policy. */
        ON_POLICY_DENIAL,
        /** The run reached a terminal state. Final summary generation, reporting. */
        RUN_END
    }

    /** What happened, handed to every hook registered at that point. */
    public record Event(
            Point point,
            /** Null for RUN_START and RUN_END. */
            StageId stage,
            /** Read-only from a hook's point of view; writes are not honoured. */
            TaskState state,
            /** Outcome, failure reason, or decision — depends on the point. */
            String detail
    ) {}

    /** A named extension. The name appears in errors, so a broken hook is identifiable. */
    public interface Hook {
        String name();

        void on(Event event);
    }

    private final Map<Point, List<Hook>> hooks = new EnumMap<>(Point.class);

    public HookRegistry register(Point point, Hook hook) {
        hooks.computeIfAbsent(point, k -> new ArrayList<>()).add(hook);
        return this;
    }

    /** Convenience for lambdas. */
    public HookRegistry register(Point point, String name, java.util.function.Consumer<Event> action) {
        return register(point, new Hook() {
            @Override public String name() { return name; }
            @Override public void on(Event event) { action.accept(event); }
        });
    }

    /**
     * Fires every hook at a point.
     *
     * <p>A throwing hook is reported and the next one still runs. Stopping the
     * chain on the first failure would mean one badly written extension silently
     * disabling every extension registered after it.
     */
    public void fire(Point point, StageId stage, TaskState state, String detail) {
        List<Hook> registered = hooks.get(point);
        if (registered == null) return;

        Event event = new Event(point, stage, state, detail);
        for (Hook hook : registered) {
            try {
                hook.on(event);
            } catch (RuntimeException e) {
                System.err.println("hook '" + hook.name() + "' failed at " + point
                        + " and was ignored: " + e.getMessage());
            }
        }
    }

    /** The registered extensions, for the run summary and the architecture document. */
    public String describe() {
        if (hooks.isEmpty()) return "  (none registered)\n";
        StringBuilder sb = new StringBuilder();
        hooks.forEach((point, list) -> list.forEach(h ->
                sb.append("  - ").append(point).append(": ").append(h.name()).append('\n')));
        return sb.toString();
    }

    public int count() {
        return hooks.values().stream().mapToInt(List::size).sum();
    }
}
