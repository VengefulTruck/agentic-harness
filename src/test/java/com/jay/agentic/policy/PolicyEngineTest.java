package com.jay.agentic.policy;

import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEngineTest {

    private TaskState state;
    private PolicyEngine engine;

    /** A tool that records what it was called with, so we can prove it was never reached. */
    private static final class SpyTool implements Tool {
        private final String name;
        private final boolean mutating;
        boolean wasInvoked = false;

        SpyTool(String name, boolean mutating) {
            this.name = name;
            this.mutating = mutating;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "spy"; }
        @Override public boolean isMutating() { return mutating; }

        @Override
        public Result invoke(Map<String, String> args) {
            wasInvoked = true;
            return Result.ok("done", EvidenceRef.toolCall(name));
        }
    }

    @BeforeEach
    void setUp() {
        state = new TaskState("run-1", "add rate limiting", "/tmp/repo",
                TaskState.Scenario.BROWNFIELD);
        state.initStages(List.of(StageId.APPROVAL_GATE, StageId.IMPLEMENT));
        engine = PolicyEngine.standard();
    }

    private void approvePlan() {
        state.putStage(state.stage(StageId.APPROVAL_GATE).withStatus(StageStatus.PASSED));
    }

    @Test
    @DisplayName("a read-only tool runs without approval")
    void readToolNeedsNoApproval() {
        SpyTool read = new SpyTool("read_file", false);

        Tool.Result r = engine.invoke(read, Map.of("path", "X.java"), StageId.EXPLORE, state);

        assertThat(r.success()).isTrue();
        assertThat(read.wasInvoked).isTrue();
    }

    @Test
    @DisplayName("a mutating tool is refused before the plan is approved — and never reached")
    void mutatingToolBlockedBeforeApproval() {
        SpyTool write = new SpyTool("propose_patch", true);

        Tool.Result r = engine.invoke(write, Map.of("path", "X.java", "content", "class X {}"),
                StageId.IMPLEMENT, state);

        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).contains("has not been approved");

        // The point: refusal happens before invocation, not after.
        assertThat(write.wasInvoked).isFalse();
    }

    @Test
    @DisplayName("the same tool is permitted once a human has approved the plan")
    void mutatingToolAllowedAfterApproval() {
        approvePlan();
        SpyTool write = new SpyTool("propose_patch", true);

        Tool.Result r = engine.invoke(write, Map.of("path", "X.java", "content", "class X {}"),
                StageId.IMPLEMENT, state);

        assertThat(r.success()).isTrue();
        assertThat(write.wasInvoked).isTrue();
    }

    @Test
    @DisplayName("a hardcoded API key in generated content is refused, and the key is not logged")
    void secretInGeneratedContentIsBlocked() {
        approvePlan();
        SpyTool write = new SpyTool("propose_patch", true);

        String generated = """
                public class AnalyticsClient {
                    private static final String KEY = "sk-ant-api03-fakeKeyShapedStringForTestingOnly";
                }
                """;

        Tool.Result r = engine.invoke(write, Map.of("path", "AnalyticsClient.java", "content", generated),
                StageId.IMPLEMENT, state);

        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).contains("credential");
        assertThat(write.wasInvoked).isFalse();

        // The scanner must not copy the secret into the audit trail it writes.
        assertThat(engine.denials().toString()).doesNotContain("fakeKeyShapedStringForTestingOnly");
    }

    @Test
    @DisplayName("a placeholder is not a secret — the scanner must not cry wolf")
    void placeholderIsNotBlocked() {
        approvePlan();
        SpyTool write = new SpyTool("propose_patch", true);

        String generated = """
                public class AnalyticsClient {
                    private static final String KEY = System.getenv("ANALYTICS_KEY"); // your-key-here
                }
                """;

        assertThat(engine.invoke(write, Map.of("path", "X.java", "content", generated),
                StageId.IMPLEMENT, state).success()).isTrue();
    }

    @Test
    @DisplayName("a proposal against pom.xml is refused — the supply chain is not the agent's call")
    void buildFileChangeIsBlocked() {
        approvePlan();
        SpyTool write = new SpyTool("propose_patch", true);

        Tool.Result r = engine.invoke(write, Map.of("path", "pom.xml", "content", "<project/>"),
                StageId.IMPLEMENT, state);

        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).contains("pom.xml");
        assertThat(write.wasInvoked).isFalse();
    }

    @Test
    @DisplayName("an unlisted mutating tool is refused even after approval")
    void unknownMutatorIsBlocked() {
        approvePlan();
        SpyTool rogue = new SpyTool("apply_patch_to_repo", true);

        Tool.Result r = engine.invoke(rogue, Map.of("path", "X.java", "content", "x"),
                StageId.IMPLEMENT, state);

        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).contains("not on the permitted list");
        assertThat(rogue.wasInvoked).isFalse();
    }

    @Test
    @DisplayName("a policy that throws denies the action rather than abstaining")
    void brokenPolicyFailsClosed() {
        Policy broken = new Policy() {
            @Override public String name() { return "broken-rule"; }
            @Override public String rationale() { return "throws"; }
            @Override public Verdict check(Request r) { throw new IllegalStateException("boom"); }
        };
        PolicyEngine e = new PolicyEngine(List.of(broken));
        SpyTool read = new SpyTool("read_file", false);

        Tool.Result r = e.invoke(read, Map.of(), StageId.EXPLORE, state);

        assertThat(r.success()).isFalse();
        assertThat(r.failureReason()).contains("could not be evaluated");
        assertThat(read.wasInvoked).isFalse();
    }

    @Test
    @DisplayName("every denial surfaces as an open question, so the human learns the agent tried")
    void denialsBecomeOpenQuestions() {
        SpyTool write = new SpyTool("propose_patch", true);
        engine.invoke(write, Map.of("path", "X.java", "content", "class X {}"),
                StageId.IMPLEMENT, state);

        assertThat(state.openQuestions())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("Policy refused")
                .contains("no-mutation-before-approval");
    }
}