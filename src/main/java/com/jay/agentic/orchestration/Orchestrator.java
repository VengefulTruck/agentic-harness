package com.jay.agentic.orchestration;

import com.jay.agentic.agents.Agent;
import com.jay.agentic.hooks.HookRegistry;
import com.jay.agentic.observability.Tracer;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.RunSnapshot;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageResult;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.StateStore;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes a run against the workflow graph.
 *
 * <p>The loop is deliberately simple: ask the graph what is ready, run all of it,
 * repeat until nothing is. Everything interesting — sequencing, parallelism,
 * joins, re-planning — is a consequence of the graph's shape rather than logic
 * written here. This class knows how to run a stage and how to react to the
 * outcome; it does not know the pipeline's shape, which is why the pipeline can
 * change without touching it.
 *
 * <p>Three behaviours are worth naming, because they are what the loop buys:
 *
 * <ul>
 *   <li><b>Parallelism</b> — a pass may find three ready stages and submits all
 *       three at once. Nothing here says "run TEST and DOCS together"; they simply
 *       have no dependency on each other.
 *   <li><b>Re-planning</b> — after each pass, stages whose recorded input hashes
 *       no longer match reality are marked STALE, which makes them ready again.
 *       The loop re-runs them on the next pass with no special code path.
 *   <li><b>Safe-stop</b> — a failure that exhausts its attempts blocks its whole
 *       downstream cone in one operation, so the run halts rather than limping
 *       forward producing work built on a failure.
 * </ul>
 */
public final class Orchestrator {

    private final WorkflowGraph graph;
    private final Map<StageId, Agent> agents;
    private final ApprovalPort approvals;
    private final StateStore store;
    private final Tracer tracer;
    private final HookRegistry hooks;

    public Orchestrator(WorkflowGraph graph, List<Agent> agentList,
                        ApprovalPort approvals, StateStore store, Tracer tracer,
                        HookRegistry hooks) {
        this.graph = graph;
        this.approvals = approvals;
        this.store = store;
        this.tracer = tracer;
        this.hooks = hooks;
        this.agents = new EnumMap<>(StageId.class);
        for (Agent a : agentList) {
            if (agents.put(a.stage(), a) != null) {
                throw new IllegalArgumentException("two agents registered for stage " + a.stage());
            }
        }
    }

    // ---- entry point ----

    public RunSnapshot run(TaskState state) {
        initialise(state);
        state.setRunStatus(TaskState.RunStatus.RUNNING);
        hooks.fire(HookRegistry.Point.RUN_START, null, state, state.requirement());
        checkpoint(state);

        while (state.runStatus() == TaskState.RunStatus.RUNNING) {
            Set<StageId> ready = graph.readyStages(state);
            if (ready.isEmpty()) break;

            executePass(state, ready);
            propagateStaleness(state);
            checkpoint(state);
        }

        finalise(state);
        hooks.fire(HookRegistry.Point.RUN_END, null, state,
                state.runStatus() + (state.stopReason() == null ? "" : " — " + state.stopReason()));
        checkpoint(state);
        return RunSnapshot.of(state);
    }

    /**
     * Prepares every stage in the graph, marking those outside this run's scenario
     * SKIPPED up front.
     *
     * <p>Skipping eagerly rather than lazily matters: SKIPPED counts as successful,
     * so a dependent of a skipped stage unblocks naturally. Leaving inapplicable
     * stages PENDING would silently deadlock anything downstream of them — on a
     * greenfield run, PLAN would wait forever on an EXPLORE that is never going
     * to happen.
     */
    private void initialise(TaskState state) {
        state.initStages(List.copyOf(graph.stageIds()));
        for (StageNode node : graph.nodes()) {
            if (!node.appliesTo(state.scenario())) {
                state.putStage(state.stage(node.id()).withStatus(StageStatus.SKIPPED));
                tracer.record(node.id(), Tracer.Span.Kind.STAGE, node.description(),
                        "SKIPPED", "not applicable to a " + state.scenario() + " run", 0);
            }
        }
    }

    // ---- one scheduling pass ----

    private void executePass(TaskState state, Set<StageId> ready) {
        // Stages block on model calls rather than burning CPU, so the pool is sized
        // to the fan-out (never more than 4) instead of to core count. Virtual threads
        // would suit this better, but they are Java 21 and the target repo runs 17 —
        // matching the target's LTS was worth more than the ergonomics.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(ready.size(), 4));
        try {
            List<Future<?>> inFlight = new ArrayList<>();
            for (StageId id : ready) {
                inFlight.add(pool.submit(() -> runStage(state, id)));
            }
            for (Future<?> f : inFlight) {
                try {
                    f.get();
                } catch (Exception e) {
                    // A stage that threw outside its own error handling is a harness
                    // bug, not a workflow failure. Stop rather than guess.
                    state.stop("orchestrator fault: " + e.getMessage());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private void runStage(TaskState state, StageId id) {
        StageNode node = graph.node(id);

        Gate.Verdict entry = node.entryGate().evaluate(state);
        tracer.record(id, Tracer.Span.Kind.GATE, "entry gate",
                entry.passed() ? "OK" : "FAILED", entry.reason(), 0);

        if (!entry.passed()) {
            state.putStage(state.stage(id).failed("entry gate: " + entry.reason()));
            hooks.fire(HookRegistry.Point.ON_FAILURE, id, state, "entry gate: " + entry.reason());
            blockDownstream(state, id, "entry gate refused " + id);
            return;
        }

        if (node.requiresApproval()) {
            handleApproval(state, node);
            return;
        }

        hooks.fire(HookRegistry.Point.BEFORE_STAGE, id, state, node.description());

        Agent agent = agents.get(id);
        if (agent == null) {
            state.putStage(state.stage(id).withStatus(StageStatus.SKIPPED));
            tracer.record(id, Tracer.Span.Kind.STAGE, node.description(),
                    "SKIPPED", "no agent registered for this stage", 0);
            return;
        }

        attemptWithRetries(state, node, agent);
    }

    /**
     * Runs an agent, retrying up to the node's limit.
     *
     * <p>The retry budget covers both a throwing agent and one that returns
     * output the exit gate rejects. Those are the same failure from the run's
     * point of view — the stage did not produce acceptable work — and treating
     * them differently would mean an agent that returns an apology instead of a
     * patch gets unlimited attempts while one that throws gets three.
     */
    private void attemptWithRetries(TaskState state, StageNode node, Agent agent) {
        StageId id = node.id();
        String lastFailure = "not attempted";

        while (state.stage(id).attempts() < node.maxAttempts()) {
            state.putStage(state.stage(id).beginAttempt());
            long started = System.currentTimeMillis();

            try {
                Agent.Output output = agent.execute(state);

                for (Artifact a : output.artifacts()) {
                    state.addArtifact(a);
                }
                state.putStage(state.stage(id)
                        .withInputFingerprint(output.inputsRead())
                        .withProducedArtifacts(output.artifacts().stream().map(Artifact::id).toList()));

                Gate.Verdict exit = node.exitGate().evaluate(state);
                tracer.record(id, Tracer.Span.Kind.GATE, "exit gate",
                        exit.passed() ? "OK" : "FAILED", exit.reason(), 0);

                if (exit.passed()) {
                    state.putStage(state.stage(id).withStatus(StageStatus.PASSED));
                    tracer.record(id, Tracer.Span.Kind.STAGE, node.description(), "OK", null,
                            System.currentTimeMillis() - started);
                    hooks.fire(HookRegistry.Point.AFTER_STAGE, id, state, exit.reason());
                    return;
                }
                lastFailure = "exit gate: " + exit.reason();

            } catch (Agent.AgentException e) {
                lastFailure = e.getMessage();
            }

            // Recorded per attempt, not per stage. A stage that passed on its third try
            // is a different fact from one that passed first time, and a trace that only
            // showed the outcome would hide the flakiness the retry budget is paying for.
            tracer.record(id, Tracer.Span.Kind.STAGE, node.description(), "FAILED", lastFailure,
                    System.currentTimeMillis() - started);
        }

        state.putStage(state.stage(id).failed(lastFailure));
        hooks.fire(HookRegistry.Point.ON_FAILURE, id, state, lastFailure);
        blockDownstream(state, id, id + " failed after " + node.maxAttempts() + " attempt(s): " + lastFailure);
    }

    // ---- human checkpoints ----

    private void handleApproval(TaskState state, StageNode node) {
        StageId id = node.id();
        state.putStage(state.stage(id).withStatus(StageStatus.AWAITING_APPROVAL));
        state.setRunStatus(TaskState.RunStatus.AWAITING_HUMAN);
        checkpoint(state);

        ApprovalPort.Request request = new ApprovalPort.Request(
                id, node.description(), approvalContext(state), consequenceOf(id));

        long started = System.currentTimeMillis();
        ApprovalPort.Response response = approvals.requestApproval(request, state);
        long waited = System.currentTimeMillis() - started;

        // The wait is recorded because it is a real cost of the checkpoint. A gate that
        // parks a run for an hour is doing its job; a gate nobody answers is a bottleneck,
        // and the trace is the only place that distinction shows up as data.
        tracer.record(id, Tracer.Span.Kind.APPROVAL, node.description(),
                response.decision().name(), response.reason(), waited);

        hooks.fire(HookRegistry.Point.ON_APPROVAL, id, state,
                response.decision() + ": " + response.reason());

        applyResolutions(state, id, response);

        state.addDecision(Decision.byHuman(state.nextId("dec"), id,
                node.description(), response.decision().name(), response.reason()));

        switch (response.decision()) {
            case APPROVE -> {
                state.putStage(state.stage(id).withStatus(StageStatus.PASSED));
                state.setRunStatus(TaskState.RunStatus.RUNNING);
            }
            case REJECT -> {
                state.putStage(state.stage(id).failed("rejected by human: " + response.reason()));
                blockDownstream(state, id, "human rejected at " + id);
                state.stop("rejected by human at " + id + ": " + response.reason());
            }
            // Park. State is already written; the JVM may exit and the run resume later.
            case DEFER -> state.setRunStatus(TaskState.RunStatus.AWAITING_HUMAN);
        }
    }

    /**
     * Records the human's answers to the assumptions the harness had to guess at.
     *
     * <p>Each becomes a decision in its own right, attributed to the human, so the
     * ledger shows not only that a run was approved but which of its guesses were
     * confirmed and which were overturned. A rejected assumption is retained as
     * REJECTED rather than deleted: "we guessed wrong and a human caught it" is the
     * most useful line an audit trail can contain, and erasing it would leave a
     * record showing only the guesses that happened to be right.
     */
    private void applyResolutions(TaskState state, StageId stage, ApprovalPort.Response response) {
        if (response.resolutions().isEmpty()) return;

        for (var assumption : state.assumptions()) {
            ApprovalPort.Resolution answer = response.resolutions().get(assumption.id());
            if (answer == null || assumption.status() != Assumption.Status.OPEN) continue;

            Assumption.Status outcome = answer.confirmed()
                    ? Assumption.Status.CONFIRMED
                    : Assumption.Status.REJECTED;

            state.replaceAssumption(assumption.resolve(outcome, answer.note()));

            state.addDecision(Decision.byHuman(state.nextId("dec"), stage,
                    "Assumption: " + assumption.statement(),
                    outcome.name(), answer.note()));

            tracer.record(stage, Tracer.Span.Kind.APPROVAL,
                    "resolve " + assumption.id(), outcome.name(), answer.note(), 0);
        }
    }

    /** Everything the human needs to answer without reading the code. */
    private List<String> approvalContext(TaskState state) {
        List<String> ctx = new ArrayList<>();
        state.latestArtifact(Artifact.Type.TASK_PLAN)
                .ifPresent(a -> ctx.add("Plan:\n" + a.content()));
        state.latestArtifact(Artifact.Type.IMPACT_ANALYSIS)
                .ifPresent(a -> ctx.add("Impact:\n" + a.content()));
        state.latestArtifact(Artifact.Type.PATCH)
                .ifPresent(a -> ctx.add("Proposed patch (" + a.name() + "):\n" + a.content()));
        for (var asm : state.assumptions()) {
            ctx.add("Assumption [" + asm.risk() + "] " + asm.statement() + " — " + asm.reason());
        }
        for (var d : state.decisions()) {
            if (d.decidedBy() == Decision.Actor.AGENT) {
                ctx.add("Decided: " + d.choice() + " — " + d.rationale());
            }
        }
        return ctx;
    }

    private String consequenceOf(StageId id) {
        return switch (id) {
            case APPROVAL_GATE -> "Approving allows the harness to generate a patch. Nothing is written to the target repo.";
            case RELEASE_GATE -> "Approving marks the change ready to apply to a branch. It is still not pushed or merged.";
            default -> "Approving allows " + id + " to proceed.";
        };
    }

    // ---- failure and staleness ----

    private void blockDownstream(TaskState state, StageId failed, String reason) {
        for (StageId dependent : graph.blockedBy(failed)) {
            StageResult r = state.stage(dependent);
            if (r != null && !r.status().isTerminal()) {
                state.putStage(r.withStatus(StageStatus.BLOCKED));
                tracer.record(dependent, Tracer.Span.Kind.STAGE, "blocked",
                        "BLOCKED", "upstream " + failed + " failed", 0);
            }
        }
        state.addOpenQuestion(reason);
    }

    /**
     * Marks any passed stage whose inputs have since changed as STALE, and
     * propagates that to everything downstream of it.
     *
     * <p>This runs after every pass, not on a notification. There is no observer,
     * no event bus, no dirty flag an agent could forget to set — the orchestrator
     * simply re-asks whether each stage's recorded inputs still hash to what they
     * hashed when it read them. State that cannot lie beats a protocol that must
     * be remembered.
     */
    private void propagateStaleness(TaskState state) {
        Map<String, String> current = state.currentHashes();

        for (StageId id : graph.stageIds()) {
            StageResult r = state.stage(id);
            if (r == null || r.status() != StageStatus.PASSED) continue;
            if (r.inputFingerprint().isEmpty()) continue;

            if (!r.inputsUnchanged(current)) {
                state.putStage(r.withStatus(StageStatus.STALE));
                tracer.record(id, Tracer.Span.Kind.STAGE, "marked stale",
                        "STALE", "an input this stage consumed has changed", 0);
                hooks.fire(HookRegistry.Point.ON_REPLAN, id, state,
                        "an input this stage consumed has changed");

                for (StageId downstream : graph.transitiveDependentsOf(id)) {
                    StageResult d = state.stage(downstream);
                    if (d != null && d.status() == StageStatus.PASSED) {
                        state.putStage(d.withStatus(StageStatus.STALE));
                        tracer.record(downstream, Tracer.Span.Kind.STAGE, "marked stale",
                                "STALE", "upstream " + id + " went stale", 0);
                        hooks.fire(HookRegistry.Point.ON_REPLAN, downstream, state,
                                "upstream " + id + " went stale");
                    }
                }
            }
        }
    }

    // ---- completion ----

    private void finalise(TaskState state) {
        if (state.runStatus() != TaskState.RunStatus.RUNNING) return;

        boolean anyFailed = state.allStages().values().stream()
                .anyMatch(r -> r.status() == StageStatus.FAILED);
        boolean anyBlocked = state.allStages().values().stream()
                .anyMatch(r -> r.status() == StageStatus.BLOCKED);

        state.setRunStatus(anyFailed || anyBlocked
                ? TaskState.RunStatus.FAILED
                : TaskState.RunStatus.COMPLETED);
    }

    /** Writes state after every transition, so a crash loses at most one pass. */
    private void checkpoint(TaskState state) {
        store.save(RunSnapshot.of(state));
    }
}