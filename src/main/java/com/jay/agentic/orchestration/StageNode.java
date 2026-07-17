package com.jay.agentic.orchestration;

import com.jay.agentic.state.StageId;

import java.util.List;
import java.util.Set;

/**
 * One node in the workflow graph: a stage, what it waits on, and what governs it.
 *
 * <p>This is where the assignment's requirements stop being prose and become
 * fields. {@code dependsOn} is the explicit dependency graph. {@code entryGate}
 * and {@code exitGate} are the gates. {@code maxAttempts} is what makes retries
 * bounded rather than hopeful. {@code requiresApproval} is where autonomy stops.
 *
 * <p>Note what is <em>not</em> here: any notion of "next stage". A node knows
 * only its own preconditions. Sequencing is derived by the orchestrator from
 * the dependencies, which is what allows parallelism to fall out for free —
 * two nodes with satisfied dependencies and no relationship to each other are
 * simply both ready at once. A "next" pointer would hard-code a linear chain
 * and quietly make the graph a list.
 */
public record StageNode(
        StageId id,
        /** Stages that must be successful before this one may start. */
        Set<StageId> dependsOn,
        /** Asked before starting: is it sensible to run at all? */
        Gate entryGate,
        /** Asked after running: is the output fit to hand downstream? */
        Gate exitGate,
        /** Total executions permitted, including the first. 1 means no retry. */
        int maxAttempts,
        /** When true, the run parks here until a human decides. */
        boolean requiresApproval,
        /** Scenarios this stage applies to. A stage outside its scenarios is SKIPPED. */
        Set<com.jay.agentic.state.TaskState.Scenario> scenarios,
        /** Human-readable purpose, surfaced in the run summary. */
        String description
) {

    public StageNode {
        dependsOn = Set.copyOf(dependsOn);
        scenarios = Set.copyOf(scenarios);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1 for " + id);
        }
    }

    public boolean appliesTo(com.jay.agentic.state.TaskState.Scenario scenario) {
        return scenarios.contains(scenario);
    }

    /** Fluent builder — the graph declaration reads better than a 8-argument constructor. */
    public static Builder of(StageId id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final StageId id;
        private Set<StageId> dependsOn = Set.of();
        private Gate entryGate = Gate.open();
        private Gate exitGate = Gate.open();
        private int maxAttempts = 1;
        private boolean requiresApproval = false;
        private Set<com.jay.agentic.state.TaskState.Scenario> scenarios =
                Set.of(com.jay.agentic.state.TaskState.Scenario.values());
        private String description = "";

        private Builder(StageId id) {
            this.id = id;
        }

        public Builder dependsOn(StageId... deps) {
            this.dependsOn = Set.of(deps);
            return this;
        }

        public Builder entryGate(Gate g) {
            this.entryGate = g;
            return this;
        }

        public Builder exitGate(Gate g) {
            this.exitGate = g;
            return this;
        }

        public Builder maxAttempts(int n) {
            this.maxAttempts = n;
            return this;
        }

        public Builder requiresApproval() {
            this.requiresApproval = true;
            return this;
        }

        public Builder onlyFor(com.jay.agentic.state.TaskState.Scenario... s) {
            this.scenarios = Set.of(s);
            return this;
        }

        public Builder describedAs(String d) {
            this.description = d;
            return this;
        }

        public StageNode build() {
            return new StageNode(id, dependsOn, entryGate, exitGate,
                    maxAttempts, requiresApproval, scenarios, description);
        }
    }
}