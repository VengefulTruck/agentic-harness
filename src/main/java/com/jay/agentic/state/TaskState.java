package com.jay.agentic.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The complete state of a single run: what was asked, what has been decided,
 * what has been assumed, what has been produced, and where every stage stands.
 *
 * <p>This is deliberately a mutable class rather than a record, unlike the
 * value types it holds. Stages run in parallel and all write here, so the
 * alternative — copy-on-write with an atomic reference — would mean concurrent
 * stages silently overwriting each other's additions unless every write became
 * a compare-and-swap retry loop. A single lock over an append-mostly aggregate
 * is simpler to reason about and simpler to defend.
 *
 * <p>The values it holds stay immutable, so anything handed to an agent cannot
 * be modified behind the orchestrator's back. Mutability stops at this boundary.
 */
public final class TaskState {

    /** Which of the assignment's scenario shapes this run is. Drives which stages apply. */
    public enum Scenario {
        /** New capability, no existing code to reason about. */
        GREENFIELD,
        /** A change to an existing codebase. */
        BROWNFIELD,
        /** Under-specified; expected to raise assumptions and questions. */
        AMBIGUOUS
    }

    public enum RunStatus {
        PLANNED, RUNNING, AWAITING_HUMAN, COMPLETED, FAILED,
        /** Halted deliberately by a guardrail — budget, policy, or safe-stop. */
        STOPPED
    }

    private final String runId;
    private final String requirement;
    private final String targetRepoPath;
    private final Scenario scenario;
    private final Instant createdAt;

    private RunStatus runStatus = RunStatus.PLANNED;
    private String stopReason;

    private final Map<StageId, StageResult> stageResults = new EnumMap<>(StageId.class);
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final List<Assumption> assumptions = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final List<String> openQuestions = new ArrayList<>();

    private int sequence = 0;

    public TaskState(String runId, String requirement, String targetRepoPath, Scenario scenario) {
        this.runId = runId;
        this.requirement = requirement;
        this.targetRepoPath = targetRepoPath;
        this.scenario = scenario;
        this.createdAt = Instant.now();
    }

    // ---- identity ----

    public String runId() { return runId; }
    public String requirement() { return requirement; }
    public String targetRepoPath() { return targetRepoPath; }
    public Scenario scenario() { return scenario; }
    public Instant createdAt() { return createdAt; }

    // ---- run status ----

    public synchronized RunStatus runStatus() { return runStatus; }
    public synchronized String stopReason() { return stopReason; }

    public synchronized void setRunStatus(RunStatus status) {
        this.runStatus = status;
    }

    /** Halts the run deliberately. Used by budget caps, policy denials and safe-stop. */
    public synchronized void stop(String reason) {
        this.runStatus = RunStatus.STOPPED;
        this.stopReason = reason;
    }

    // ---- id generation ----

    /** Run-scoped, monotonic, human-readable ids. Deterministic — no UUIDs, so runs diff cleanly. */
    public synchronized String nextId(String prefix) {
        return prefix + "-" + (++sequence);
    }

    // ---- stages ----

    public synchronized void initStages(List<StageId> stages) {
        for (StageId s : stages) {
            stageResults.put(s, StageResult.pending(s));
        }
    }

    public synchronized StageResult stage(StageId id) {
        return stageResults.get(id);
    }

    public synchronized void putStage(StageResult result) {
        stageResults.put(result.stageId(), result);
    }

    public synchronized Map<StageId, StageResult> allStages() {
        return Collections.unmodifiableMap(new EnumMap<>(stageResults));
    }

    // ---- artifacts ----

    public synchronized void addArtifact(Artifact a) {
        artifacts.put(a.id(), a);
    }

    public synchronized Optional<Artifact> artifact(String id) {
        return Optional.ofNullable(artifacts.get(id));
    }

    public synchronized List<Artifact> artifactsOfType(Artifact.Type type) {
        return artifacts.values().stream().filter(a -> a.type() == type).toList();
    }

    /** The most recent artifact of a type — what a downstream stage almost always wants. */
    public synchronized Optional<Artifact> latestArtifact(Artifact.Type type) {
        List<Artifact> all = artifactsOfType(type);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /** Artifact id to current content hash. The input to {@link StageResult#inputsUnchanged}. */
    public synchronized Map<String, String> currentHashes() {
        return artifacts.values().stream()
                .collect(Collectors.toMap(Artifact::id, Artifact::contentHash));
    }

    // ---- assumptions ----

    public synchronized void addAssumption(Assumption a) {
        assumptions.add(a);
    }

    public synchronized List<Assumption> assumptions() {
        return List.copyOf(assumptions);
    }

    /** Replaces an assumption in place, preserving order. Used when a human resolves one. */
    public synchronized void replaceAssumption(Assumption resolved) {
        for (int i = 0; i < assumptions.size(); i++) {
            if (assumptions.get(i).id().equals(resolved.id())) {
                assumptions.set(i, resolved);
                return;
            }
        }
        throw new IllegalArgumentException("no such assumption: " + resolved.id());
    }

    /** Unresolved high-risk guesses. The release gate refuses to pass while this is non-empty. */
    public synchronized List<Assumption> releaseBlockers() {
        return assumptions.stream().filter(Assumption::blocksRelease).toList();
    }

    // ---- decisions ----

    public synchronized void addDecision(Decision d) {
        decisions.add(d);
    }

    public synchronized List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** Every point where the harness stopped and asked. The autonomy boundary, enumerated. */
    public synchronized List<Decision> humanDecisions() {
        return decisions.stream().filter(d -> d.decidedBy() == Decision.Actor.HUMAN).toList();
    }

    // ---- open questions ----

    public synchronized void addOpenQuestion(String q) {
        openQuestions.add(q);
    }

    public synchronized List<String> openQuestions() {
        return List.copyOf(openQuestions);
    }

    public synchronized void clearOpenQuestions() {
        openQuestions.clear();
    }
}