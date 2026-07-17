package com.jay.agentic.state;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A serialisable, immutable picture of a run at a point in time.
 *
 * <p>Deliberately separate from {@link TaskState} rather than annotating that
 * class for Jackson. Two reasons, both worth stating out loud:
 *
 * <p>First, {@code TaskState} is a live, synchronized, mutable aggregate.
 * Serialising it directly would mean Jackson walking its internals while other
 * threads append to them — a data race hidden inside a library call. Taking a
 * snapshot under the lock and handing Jackson a frozen copy removes the race.
 *
 * <p>Second, it separates the persistence format from the in-memory model. The
 * run file is an audit artifact that outlives the code; pinning it to a record
 * means refactoring {@code TaskState} cannot silently change what was written
 * to disk last week.
 */
public record RunSnapshot(
        String runId,
        String requirement,
        String targetRepoPath,
        TaskState.Scenario scenario,
        TaskState.RunStatus runStatus,
        String stopReason,
        Instant createdAt,
        Instant snapshotAt,
        List<StageResult> stages,
        List<Artifact> artifacts,
        List<Assumption> assumptions,
        List<Decision> decisions,
        List<String> openQuestions
) {

    public RunSnapshot {
        stages = List.copyOf(stages);
        artifacts = List.copyOf(artifacts);
        assumptions = List.copyOf(assumptions);
        decisions = List.copyOf(decisions);
        openQuestions = List.copyOf(openQuestions);
    }

    /** Freezes a live state. Callers hold no lock — {@code TaskState}'s accessors are synchronized. */
    public static RunSnapshot of(TaskState state) {
        Map<StageId, StageResult> stageMap = state.allStages();
        List<Artifact> arts = new ArrayList<>();
        for (StageId id : StageId.values()) {
            StageResult r = stageMap.get(id);
            if (r == null) continue;
            for (String artId : r.producedArtifactIds()) {
                state.artifact(artId).ifPresent(arts::add);
            }
        }
        return new RunSnapshot(
                state.runId(),
                state.requirement(),
                state.targetRepoPath(),
                state.scenario(),
                state.runStatus(),
                state.stopReason(),
                state.createdAt(),
                Instant.now(),
                List.copyOf(stageMap.values()),
                arts,
                state.assumptions(),
                state.decisions(),
                state.openQuestions()
        );
    }
}