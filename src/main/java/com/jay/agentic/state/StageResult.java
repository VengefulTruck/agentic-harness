package com.jay.agentic.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The record of one stage's execution within a run.
 *
 * <p>The {@code inputFingerprint} is the mechanism behind re-planning. When a
 * stage runs, it records the id and content hash of every artifact it read.
 * The orchestrator can later compare that snapshot against the artifacts as
 * they now stand: if any hash has moved, this stage's output was derived from
 * inputs that no longer exist, and it is marked {@link StageStatus#STALE}
 * rather than trusted.
 *
 * <p>{@code attempts} exists to make retries bounded. A stage that has burned
 * its budget fails for good instead of looping — the difference between a
 * retry policy and an infinite loop with optimism.
 */
public record StageResult(
        StageId stageId,
        StageStatus status,
        /** Executions so far, including the current one. Starts at 0 before the first run. */
        int attempts,
        /** Artifact id to content hash, captured at read time. Empty for stages that read nothing. */
        Map<String, String> inputFingerprint,
        /** Ids of artifacts this stage produced, in production order. */
        List<String> producedArtifactIds,
        /** Why the exit gate rejected the output, or why execution threw. Null unless FAILED. */
        String failureReason,
        Instant startedAt,
        /** Null while RUNNING or AWAITING_APPROVAL. */
        Instant finishedAt
) {

    public StageResult {
        inputFingerprint = Map.copyOf(inputFingerprint);
        producedArtifactIds = List.copyOf(producedArtifactIds);
    }

    /** The initial state of every stage when a run is planned. */
    public static StageResult pending(StageId stageId) {
        return new StageResult(stageId, StageStatus.PENDING, 0,
                Map.of(), List.of(), null, null, null);
    }

    public StageResult withStatus(StageStatus newStatus) {
        Instant finish = newStatus.isTerminal() ? Instant.now() : finishedAt;
        return new StageResult(stageId, newStatus, attempts, inputFingerprint,
                producedArtifactIds, failureReason, startedAt, finish);
    }

    /** Marks the start of an attempt. Increments the attempt counter. */
    public StageResult beginAttempt() {
        return new StageResult(stageId, StageStatus.RUNNING, attempts + 1, inputFingerprint,
                producedArtifactIds, null, Instant.now(), null);
    }

    public StageResult withInputFingerprint(Map<String, String> fingerprint) {
        return new StageResult(stageId, status, attempts, fingerprint,
                producedArtifactIds, failureReason, startedAt, finishedAt);
    }

    public StageResult withProducedArtifacts(List<String> artifactIds) {
        return new StageResult(stageId, status, attempts, inputFingerprint,
                artifactIds, failureReason, startedAt, finishedAt);
    }

    public StageResult failed(String reason) {
        return new StageResult(stageId, StageStatus.FAILED, attempts, inputFingerprint,
                producedArtifactIds, reason, startedAt, Instant.now());
    }

    /** True when this stage's recorded inputs still match the artifacts as they now stand. */
    public boolean inputsUnchanged(Map<String, String> currentHashesById) {
        return inputFingerprint.entrySet().stream()
                .allMatch(e -> e.getValue().equals(currentHashesById.get(e.getKey())));
    }

    /** Wall-clock duration of the completed attempt, or null if not finished. */
    public Duration elapsed() {
        return (startedAt == null || finishedAt == null) ? null : Duration.between(startedAt, finishedAt);
    }
}