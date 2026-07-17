package com.jay.agentic.orchestration;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphTest {

    private final WorkflowGraph graph = SdlcGraph.build();

    private TaskState runOf(TaskState.Scenario scenario) {
        TaskState s = new TaskState("run-1", "add per-IP rate limiting", "/tmp/repo", scenario);
        s.initStages(List.copyOf(graph.stageIds()));
        return s;
    }

    private void pass(TaskState s, StageId id) {
        s.putStage(s.stage(id).withStatus(StageStatus.PASSED));
    }

    @Test
    @DisplayName("a cyclic graph is rejected at construction, with the path named")
    void rejectsCycles() {
        assertThatThrownBy(() -> WorkflowGraph.of(List.of(
                StageNode.of(StageId.PLAN).dependsOn(StageId.IMPLEMENT).build(),
                StageNode.of(StageId.IMPLEMENT).dependsOn(StageId.PLAN).build()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("PLAN");
    }

    @Test
    @DisplayName("a dependency on an undeclared stage is rejected at construction")
    void rejectsDanglingDependency() {
        assertThatThrownBy(() -> WorkflowGraph.of(List.of(
                StageNode.of(StageId.TEST).dependsOn(StageId.IMPLEMENT).build()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
    }

    @Test
    @DisplayName("the real SDLC graph is acyclic and fully declared")
    void sdlcGraphIsValid() {
        assertThat(graph.stageIds()).hasSize(12);
    }

    @Test
    @DisplayName("only INTAKE is ready at the start of a run")
    void startsAtIntake() {
        assertThat(graph.readyStages(runOf(TaskState.Scenario.BROWNFIELD)))
                .containsExactly(StageId.INTAKE);
    }

    @Test
    @DisplayName("IMPLEMENT fans out to three stages at once - parallelism is emergent")
    void implementFansOutToThree() {
        TaskState s = runOf(TaskState.Scenario.BROWNFIELD);
        pass(s, StageId.INTAKE);
        pass(s, StageId.EXPLORE);
        pass(s, StageId.PLAN);
        pass(s, StageId.APPROVAL_GATE);
        pass(s, StageId.IMPLEMENT);

        assertThat(graph.readyStages(s))
                .containsExactlyInAnyOrder(StageId.TEST, StageId.DOCS, StageId.SECURITY_REVIEW);
    }

    @Test
    @DisplayName("VALIDATE joins: it waits for the slowest of the three branches")
    void validateWaitsForAllBranches() {
        TaskState s = runOf(TaskState.Scenario.BROWNFIELD);
        pass(s, StageId.INTAKE);
        pass(s, StageId.EXPLORE);
        pass(s, StageId.PLAN);
        pass(s, StageId.APPROVAL_GATE);
        pass(s, StageId.IMPLEMENT);
        pass(s, StageId.TEST);
        pass(s, StageId.DOCS);

        assertThat(graph.readyStages(s)).doesNotContain(StageId.VALIDATE);

        pass(s, StageId.SECURITY_REVIEW);

        assertThat(graph.readyStages(s)).contains(StageId.VALIDATE);
    }

    @Test
    @DisplayName("a SKIPPED stage satisfies its dependents - greenfield skips EXPLORE")
    void skippedStageUnblocksDependents() {
        TaskState s = runOf(TaskState.Scenario.GREENFIELD);
        pass(s, StageId.INTAKE);
        s.putStage(s.stage(StageId.EXPLORE).withStatus(StageStatus.SKIPPED));
        s.putStage(s.stage(StageId.CLARIFY).withStatus(StageStatus.SKIPPED));

        assertThat(graph.readyStages(s)).contains(StageId.PLAN);
    }

    @Test
    @DisplayName("one failure blocks the entire downstream cone in a single pass")
    void failurePropagatesTransitively() {
        assertThat(graph.blockedBy(StageId.IMPLEMENT))
                .containsExactlyInAnyOrder(StageId.TEST, StageId.DOCS, StageId.SECURITY_REVIEW,
                        StageId.VALIDATE, StageId.RELEASE_GATE, StageId.SUMMARY);
    }

    @Test
    @DisplayName("a STALE stage becomes re-runnable - this is the whole re-plan mechanism")
    void staleStagesAreRescheduled() {
        TaskState s = runOf(TaskState.Scenario.BROWNFIELD);
        pass(s, StageId.INTAKE);
        pass(s, StageId.EXPLORE);
        pass(s, StageId.PLAN);
        pass(s, StageId.APPROVAL_GATE);
        pass(s, StageId.IMPLEMENT);
        pass(s, StageId.TEST);
        pass(s, StageId.DOCS);
        pass(s, StageId.SECURITY_REVIEW);

        assertThat(graph.readyStages(s)).containsExactly(StageId.VALIDATE);

        // IMPLEMENT re-ran: its patch changed, so everything derived from it is invalid.
        s.putStage(s.stage(StageId.TEST).withStatus(StageStatus.STALE));

        assertThat(graph.readyStages(s)).contains(StageId.TEST);
    }

    @Test
    @DisplayName("dependents are direct; transitive dependents are the full blast radius")
    void directVersusTransitiveDependents() {
        assertThat(graph.dependentsOf(StageId.IMPLEMENT))
                .containsExactlyInAnyOrder(StageId.TEST, StageId.DOCS, StageId.SECURITY_REVIEW);

        assertThat(graph.transitiveDependentsOf(StageId.TEST))
                .containsExactlyInAnyOrder(StageId.VALIDATE, StageId.RELEASE_GATE, StageId.SUMMARY);
    }
}