package com.jay.agentic.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StateStoreTest {

    @TempDir
    Path runsDir;

    private TaskState sampleRun() {
        TaskState s = new TaskState("run-42", "Add per-IP rate limiting to POST /api/v1/links",
                "/tmp/url-shortener", TaskState.Scenario.BROWNFIELD);
        s.initStages(List.of(StageId.INTAKE, StageId.PLAN, StageId.IMPLEMENT));

        Artifact patch = Artifact.of("art-1", StageId.IMPLEMENT,
                Artifact.Type.PATCH, "RateLimitInterceptor.java", "public class X {}");
        s.addArtifact(patch);
        s.putStage(s.stage(StageId.IMPLEMENT)
                .withProducedArtifacts(List.of("art-1"))
                .withStatus(StageStatus.PASSED));

        s.addAssumption(Assumption.open("asm-1", StageId.CLARIFY,
                "limit is per-IP, not per-API-key", "requirement is silent",
                Assumption.Risk.HIGH, List.of(EvidenceRef.requirement("per-IP"))));

        s.addDecision(Decision.byAgent("dec-1", StageId.PLAN,
                "where to enforce?", "interceptor", "needs the parsed request",
                List.of(new Decision.Alternative("servlet filter", "runs before body parsing")),
                List.of(EvidenceRef.sourceFile("src/main/java/.../LinkController.java", "40-52"))));

        s.addOpenQuestion("what should the limit be per minute?");
        s.setRunStatus(TaskState.RunStatus.AWAITING_HUMAN);
        return s;
    }

    @Test
    @DisplayName("a parked run survives a round trip to disk with its reasoning intact")
    void roundTripsFullRun() {
        StateStore store = new StateStore(runsDir);
        store.save(RunSnapshot.of(sampleRun()));

        RunSnapshot loaded = store.load("run-42");

        assertThat(loaded.runId()).isEqualTo("run-42");
        assertThat(loaded.scenario()).isEqualTo(TaskState.Scenario.BROWNFIELD);
        assertThat(loaded.runStatus()).isEqualTo(TaskState.RunStatus.AWAITING_HUMAN);
        assertThat(loaded.stages()).hasSize(3);
        assertThat(loaded.openQuestions()).containsExactly("what should the limit be per minute?");

        // The reasoning is what makes the run auditable — it has to survive, not just the status.
        assertThat(loaded.decisions()).singleElement().satisfies(d -> {
            assertThat(d.choice()).isEqualTo("interceptor");
            assertThat(d.alternatives()).singleElement()
                    .extracting(Decision.Alternative::rejectedBecause)
                    .isEqualTo("runs before body parsing");
            assertThat(d.evidence()).singleElement()
                    .extracting(EvidenceRef::kind).isEqualTo(EvidenceRef.Kind.SOURCE_FILE);
        });

        assertThat(loaded.assumptions()).singleElement()
                .extracting(Assumption::risk).isEqualTo(Assumption.Risk.HIGH);
    }

    @Test
    @DisplayName("content hashes survive, so staleness can still be detected after a resume")
    void hashesSurviveReload() {
        StateStore store = new StateStore(runsDir);
        TaskState original = sampleRun();
        String hashBefore = original.artifact("art-1").orElseThrow().contentHash();

        store.save(RunSnapshot.of(original));
        RunSnapshot loaded = store.load("run-42");

        assertThat(loaded.artifacts()).singleElement()
                .extracting(Artifact::contentHash).isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("a stage's recorded inputs are compared against artifacts as they now stand")
    void detectsStaleInputs() {
        Artifact v1 = Artifact.of("art-1", StageId.IMPLEMENT, Artifact.Type.PATCH, "X.java", "v1");
        StageResult tests = StageResult.pending(StageId.TEST)
                .withInputFingerprint(Map.of("art-1", v1.contentHash()))
                .withStatus(StageStatus.PASSED);

        assertThat(tests.inputsUnchanged(Map.of("art-1", v1.contentHash()))).isTrue();

        // IMPLEMENT re-ran and produced different content — the tests describe code that is gone.
        Artifact v2 = Artifact.of("art-1", StageId.IMPLEMENT, Artifact.Type.PATCH, "X.java", "v2");
        assertThat(tests.inputsUnchanged(Map.of("art-1", v2.contentHash()))).isFalse();

        // The artifact vanished entirely — also stale, not silently OK.
        assertThat(tests.inputsUnchanged(Map.of())).isFalse();
    }

    @Test
    @DisplayName("saving twice leaves no temp file behind")
    void writesAtomically() throws Exception {
        StateStore store = new StateStore(runsDir);
        store.save(RunSnapshot.of(sampleRun()));
        store.save(RunSnapshot.of(sampleRun()));

        assertThat(store.exists("run-42")).isTrue();
        try (var files = Files.list(runsDir.resolve("run-42"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly("state.json");
        }
    }
}