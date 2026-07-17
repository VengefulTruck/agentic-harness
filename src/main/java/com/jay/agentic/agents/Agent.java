package com.jay.agentic.agents;

import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.List;
import java.util.Map;

/**
 * A unit of work bound to one stage of the SDLC graph.
 *
 * <p>The contract is deliberately narrow. An agent reads the run state, does its
 * job, and returns what it produced. It does not decide whether it should run,
 * whether its output is acceptable, whether to retry, or what happens next —
 * those belong to the graph, the gates, and the orchestrator respectively.
 *
 * <p>That separation is the point. An agent that could schedule itself would
 * make the dependency graph advisory, and an agent that judged its own output
 * would make the exit gate ceremonial. Keeping agents ignorant of orchestration
 * is what lets the governance layer actually govern.
 */
public interface Agent {

    /** The stage this agent implements. One agent per stage; the registry enforces it. */
    StageId stage();

    /**
     * What this agent produced, plus the record of what it read.
     *
     * <p>{@code inputsRead} is not bookkeeping — the orchestrator writes it into
     * the stage's fingerprint, and that fingerprint is what later detects the
     * agent's work has gone stale. An agent that under-reports its inputs will
     * appear fresh when it isn't.
     */
    record Output(
            List<Artifact> artifacts,
            /** Artifact id to content hash, as read by this agent. */
            Map<String, String> inputsRead
    ) {
        public Output {
            artifacts = List.copyOf(artifacts);
            inputsRead = Map.copyOf(inputsRead);
        }

        public static Output of(List<Artifact> artifacts, Map<String, String> inputsRead) {
            return new Output(artifacts, inputsRead);
        }

        /** For agents that read nothing from prior stages — INTAKE, essentially. */
        public static Output of(Artifact... artifacts) {
            return new Output(List.of(artifacts), Map.of());
        }
    }

    /**
     * Executes the stage.
     *
     * <p>The agent may append assumptions, decisions and open questions to the
     * state — that is how reasoning gets recorded. It must not set stage statuses
     * or touch other stages' results; the orchestrator owns those.
     *
     * @throws AgentException when the work could not be completed. The orchestrator
     *         decides whether that is retryable, not the agent.
     */
    Output execute(TaskState state) throws AgentException;

    /** Thrown when an agent cannot complete its work. Carries a human-readable cause. */
    class AgentException extends Exception {
        public AgentException(String message) {
            super(message);
        }

        public AgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}