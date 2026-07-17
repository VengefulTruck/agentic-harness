package com.jay.agentic.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskStateTest {

    private TaskState state;

    @BeforeEach
    void setUp() {
        state = new TaskState("run-1", "Add per-IP rate limiting to POST /api/v1/links",
                "/tmp/url-shortener", TaskState.Scenario.BROWNFIELD);
    }

    @Test
    @DisplayName("ids are sequential and prefixed, so runs diff cleanly")
    void generatesDeterministicIds() {
        assertThat(state.nextId("dec")).isEqualTo("dec-1");
        assertThat(state.nextId("asm")).isEqualTo("asm-2");
        assertThat(state.nextId("dec")).isEqualTo("dec-3");
    }

    @Test
    @DisplayName("every stage starts PENDING with zero attempts")
    void initStagesSetsPending() {
        state.initStages(List.of(StageId.INTAKE, StageId.PLAN));

        assertThat(state.stage(StageId.INTAKE).status()).isEqualTo(StageStatus.PENDING);
        assertThat(state.stage(StageId.INTAKE).attempts()).isZero();
        assertThat(state.stage(StageId.EXPLORE)).isNull();
    }

    @Test
    @DisplayName("latestArtifact returns the newest of a type, not the first")
    void latestArtifactWins() {
        state.addArtifact(Artifact.of("art-1", StageId.IMPLEMENT,
                Artifact.Type.PATCH, "RateLimiter.java", "v1"));
        state.addArtifact(Artifact.of("art-2", StageId.IMPLEMENT,
                Artifact.Type.PATCH, "RateLimiter.java", "v2"));

        assertThat(state.latestArtifact(Artifact.Type.PATCH))
                .get()
                .extracting(Artifact::content)
                .isEqualTo("v2");
    }

    @Test
    @DisplayName("an open HIGH-risk assumption blocks release; resolving it unblocks")
    void highRiskAssumptionBlocksRelease() {
        Assumption a = Assumption.open("asm-1", StageId.CLARIFY,
                "rate limit is per-IP, not per-API-key",
                "requirement does not say which",
                Assumption.Risk.HIGH,
                List.of(EvidenceRef.requirement("add per-IP rate limiting")));
        state.addAssumption(a);

        assertThat(state.releaseBlockers()).hasSize(1);

        state.replaceAssumption(a.resolve(Assumption.Status.CONFIRMED, "confirmed by operator"));

        assertThat(state.releaseBlockers()).isEmpty();
        assertThat(state.assumptions()).hasSize(1);   // resolved, not deleted
    }

    @Test
    @DisplayName("a LOW-risk assumption never blocks release")
    void lowRiskAssumptionDoesNotBlock() {
        state.addAssumption(Assumption.open("asm-1", StageId.PLAN,
                "429 is the right status code", "not specified",
                Assumption.Risk.LOW, List.of()));

        assertThat(state.releaseBlockers()).isEmpty();
    }

    @Test
    @DisplayName("humanDecisions isolates exactly where autonomy stopped")
    void humanDecisionsFiltersByActor() {
        state.addDecision(Decision.byAgent("dec-1", StageId.PLAN,
                "where to enforce the limit?", "interceptor",
                "needs the parsed request",
                List.of(new Decision.Alternative("servlet filter", "runs before body parsing")),
                List.of()));
        state.addDecision(Decision.byHuman("dec-2", StageId.APPROVAL_GATE,
                "apply this patch?", "approved", "reviewed the diff"));

        assertThat(state.decisions()).hasSize(2);
        assertThat(state.humanDecisions())
                .singleElement()
                .extracting(Decision::id)
                .isEqualTo("dec-2");
    }

    @Test
    @DisplayName("resolving an unknown assumption fails loudly rather than silently")
    void replaceUnknownAssumptionThrows() {
        Assumption ghost = Assumption.open("asm-99", StageId.CLARIFY,
                "x", "y", Assumption.Risk.LOW, List.of());

        assertThatThrownBy(() -> state.replaceAssumption(ghost))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asm-99");
    }
}