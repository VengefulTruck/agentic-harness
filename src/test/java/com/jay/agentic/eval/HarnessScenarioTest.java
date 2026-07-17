package com.jay.agentic.eval;

import com.jay.agentic.agents.Agent;
import com.jay.agentic.agents.ClarifyAgent;
import com.jay.agentic.agents.DocsAgent;
import com.jay.agentic.agents.ExploreAgent;
import com.jay.agentic.agents.ImplementAgent;
import com.jay.agentic.agents.IntakeAgent;
import com.jay.agentic.agents.PlanAgent;
import com.jay.agentic.agents.SecurityReviewAgent;
import com.jay.agentic.agents.SummaryAgent;
import com.jay.agentic.agents.TestAgent;
import com.jay.agentic.agents.ValidateAgent;
import com.jay.agentic.hooks.HookRegistry;
import com.jay.agentic.llm.BudgetedLlmClient;
import com.jay.agentic.llm.DeterministicLlmClient;
import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.observability.Tracer;
import com.jay.agentic.orchestration.ApprovalPort;
import com.jay.agentic.orchestration.Orchestrator;
import com.jay.agentic.orchestration.SdlcGraph;
import com.jay.agentic.orchestration.WorkflowGraph;
import com.jay.agentic.policy.PolicyEngine;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.RunSnapshot;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageResult;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.StateStore;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.FileReadTool;
import com.jay.agentic.tools.GrepTool;
import com.jay.agentic.tools.PatchProposalTool;
import com.jay.agentic.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runs of the whole harness against a deterministic model.
 *
 * <p>This is the suite that answers "does the thing work", as distinct from the
 * unit tests that answer "does this class work". It exercises the real graph, the
 * real orchestrator, the real policy engine and the real tools — only the model is
 * substituted, and only because a live model would make every assertion here a
 * coin toss.
 *
 * <p>The scenarios are chosen to cover what the assignment asks for and what a
 * reviewer would doubt: the three requirement shapes, and then the paths that
 * only exist because something went wrong — a refused action, a blocked release,
 * a rejected plan, an exhausted budget, a stage that fails and retries. A suite
 * that only demonstrated the happy path would be evidence of a demo, not of a
 * system.
 */
class HarnessScenarioTest {

    @TempDir
    Path workspace;

    // ---- fixtures ------------------------------------------------------------

    /**
     * A miniature target repository.
     *
     * <p>Real files on disk, not a mock filesystem: the tools resolve paths, walk
     * directories and read bytes, and a mocked filesystem would test the mock.
     */
    private Path targetRepo() throws IOException {
        Path repo = workspace.resolve("target-repo");
        Path pkg = repo.resolve("src/main/java/com/example/app");
        Files.createDirectories(pkg);

        Files.writeString(pkg.resolve("LinkController.java"), """
                package com.example.app;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class LinkController {

                    private final LinkService service;

                    public LinkController(LinkService service) {
                        this.service = service;
                    }

                    @PostMapping("/api/v1/links")
                    public String create(String url) {
                        return service.create(url);
                    }
                }
                """);

        Files.writeString(pkg.resolve("LinkService.java"), """
                package com.example.app;

                public class LinkService {
                    public String create(String url) {
                        return "abc123";
                    }
                }
                """);

        // A file the read tool must refuse even though it is inside the repo.
        Files.writeString(repo.resolve(".env"), "SECRET_TOKEN=sk-ant-api03-thisIsNotARealKeyItIsATestFixture\n");

        return repo;
    }

    /** The canned pipeline. Registration order matches execution order. */
    private DeterministicLlmClient model() {
        return new DeterministicLlmClient()
                .when("Restate this requirement", """
                        ### Goal
                        Requests to create a short link are limited per client IP.

                        ### In scope
                        - A per-IP request limit on the create endpoint

                        ### Out of scope
                        - Limiting the redirect endpoint

                        ### Unstated
                        - What the limit should be

                        ### Acceptance
                        - A client over the limit receives an error rather than a short link
                        """)
                .when("Identify what this requirement does not specify", """
                        ASSUMPTION | MEDIUM | the limit is 100 requests per minute | the requirement gives no number
                        ASSUMPTION | LOW | the response is HTTP 429 | conventional for rate limiting
                        QUESTION | What should the per-IP limit actually be?
                        """)
                .when("Name up to 3 regular expressions", "@RestController\n@PostMapping\n")
                .when("name up to 2 further", "DONE")
                .when("Write the impact analysis", """
                        ### Impacted files
                        - src/main/java/com/example/app/LinkController.java — the create endpoint
                          gains a limit check.

                        ### Impacted APIs and data flows
                        - POST /api/v1/links may now return 429.

                        ### Existing patterns to follow
                        - Constructor injection.

                        ### What I could not determine
                        - Whether a proxy sits in front of the service.
                        """)
                .when("Produce the plan", """
                        ### Approach
                        Add an in-memory per-IP counter and check it in the create endpoint.

                        ### Tasks
                        1. [src/main/java/com/example/app/RateLimiter.java] new counter — depends on: none
                        2. [src/test/java/com/example/app/RateLimiterTest.java] tests — depends on: 1

                        ### Key decision
                        DECISION | in-memory counter | no new dependency | Redis | unjustified infrastructure

                        ### Risks
                        - Does not hold across instances.

                        ### Out of scope
                        - Distributed limiting
                        """)
                .when("Write the code for this plan", """
                        FILE: src/main/java/com/example/app/RateLimiter.java
                        RATIONALE: in-memory per-IP counter, no new dependency
                        ```java
                        package com.example.app;

                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;
                        import java.util.concurrent.atomic.AtomicInteger;

                        public class RateLimiter {
                            private static final int LIMIT = 100;
                            private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

                            public boolean allow(String ip) {
                                return counts.computeIfAbsent(ip, k -> new AtomicInteger())
                                        .incrementAndGet() <= LIMIT;
                            }
                        }
                        ```
                        """)
                .when("Write the tests for this change", """
                        FILE: src/test/java/com/example/app/RateLimiterTest.java
                        COVERS: the limit is enforced per IP
                        ```java
                        package com.example.app;

                        import org.junit.jupiter.api.Test;
                        import static org.assertj.core.api.Assertions.assertThat;

                        class RateLimiterTest {
                            @Test
                            void refusesAboveTheLimit() {
                                RateLimiter limiter = new RateLimiter();
                                for (int i = 0; i < 100; i++) {
                                    assertThat(limiter.allow("1.2.3.4")).isTrue();
                                }
                                assertThat(limiter.allow("1.2.3.4")).isFalse();
                            }
                        }
                        ```

                        ### Not covered
                        - Window expiry, which would need a clock abstraction.
                        """)
                .when("Write the documentation", """
                        ### What changed
                        POST /api/v1/links now rejects clients above 100 requests per minute.

                        ### Behaviour
                        - Returns 429 once the limit is exceeded.

                        ### Configuration
                        - None.

                        ### Operational notes
                        - The counter is per-instance.

                        ### Known limitations
                        - Does not survive a restart.
                        """)
                .when("Review this change for security problems", """
                        FINDING | LOW | RateLimiter.java:9 | the map grows without bound | evict expired entries

                        ### Residual risk
                        - Whether a proxy is in front of this service is not determinable from the code.
                        """)
                .when("Check these artifacts against each other", """
                        CONSISTENT

                        ### Requirement coverage
                        - A client over the limit receives an error: yes — allow() returns false above 100.
                        """)
                .when("Write the assessment paragraph",
                        "Mergeable once the limit value is confirmed. The counter is per-instance.");
    }

    /** An approver that answers the same way every time and settles nothing. */
    private record ScriptedApprover(ApprovalPort.Decision decision) implements ApprovalPort {
        @Override
        public Response requestApproval(Request request, TaskState state) {
            return new Response(decision, "scripted: " + decision, java.util.Map.of());
        }
    }

    /**
     * An approver that confirms every high-risk assumption put to it.
     *
     * <p>Stands in for a human who reads the questions and answers them, as opposed
     * to {@link ScriptedApprover}, who clicks yes without settling anything.
     */
    private record AnsweringApprover(ApprovalPort.Decision decision) implements ApprovalPort {
        @Override
        public Response requestApproval(Request request, TaskState state) {
            java.util.Map<String, Resolution> answers = new java.util.LinkedHashMap<>();
            for (Assumption a : state.assumptions()) {
                if (a.status() == Assumption.Status.OPEN && a.risk() == Assumption.Risk.HIGH) {
                    answers.put(a.id(), Resolution.confirm("confirmed by the operator"));
                }
            }
            return new Response(decision, "scripted: " + decision, answers);
        }
    }

    /** Records what it was asked, so a test can assert on where the run stopped. */
    private static final class RecordingApprover implements ApprovalPort {
        final List<StageId> asked = new ArrayList<>();
        private final ApprovalPort.Decision decision;

        RecordingApprover(ApprovalPort.Decision decision) {
            this.decision = decision;
        }

        @Override
        public Response requestApproval(Request request, TaskState state) {
            asked.add(request.stage());
            return new Response(decision, "scripted: " + decision, java.util.Map.of());
        }
    }

    /**
     * Assembles the real harness around a substituted model and approver.
     *
     * <p>Note what is <em>not</em> substituted: the graph, the orchestrator, the
     * gates, the policies and the tools are the production objects. Only the two
     * things that reach outside the process — the model and the human — are stood
     * in for, which is the narrowest possible seam.
     */
    private RunSnapshot run(TaskState state, LlmClient llm, ApprovalPort approver) throws IOException {
        Path runsDir = workspace.resolve("runs");
        StateStore store = new StateStore(runsDir);
        Tracer tracer = new Tracer(runsDir.resolve(state.runId()));
        HookRegistry hooks = new HookRegistry();

        BudgetedLlmClient budgeted = new BudgetedLlmClient(llm, 1.00);
        PolicyEngine policy = PolicyEngine.standard().withHooks(hooks);

        ToolRegistry tools = ToolRegistry.of(
                new FileReadTool(Path.of(state.targetRepoPath())),
                new GrepTool(Path.of(state.targetRepoPath())),
                new PatchProposalTool(runsDir.resolve(state.runId()).resolve("proposals")));

        List<Agent> agents = List.of(
                new IntakeAgent(budgeted),
                new ClarifyAgent(budgeted),
                new ExploreAgent(budgeted, tools, policy),
                new PlanAgent(budgeted),
                new ImplementAgent(budgeted, tools, policy),
                new TestAgent(budgeted),
                new DocsAgent(budgeted),
                new SecurityReviewAgent(budgeted),
                new ValidateAgent(budgeted),
                new SummaryAgent(budgeted));

        WorkflowGraph graph = SdlcGraph.build();
        return new Orchestrator(graph, agents, approver, store, tracer, hooks).run(state);
    }

    private TaskState task(String runId, TaskState.Scenario scenario, Path repo) {
        return new TaskState(runId, "Add per-IP rate limiting to POST /api/v1/links",
                repo.toString(), scenario);
    }

    private static StageStatus statusOf(RunSnapshot snap, StageId id) {
        return snap.stages().stream()
                .filter(s -> s.stageId() == id)
                .map(StageResult::status)
                .findFirst().orElse(null);
    }

    private static Optional<Artifact> artifactOf(RunSnapshot snap, Artifact.Type type) {
        return snap.artifacts().stream().filter(a -> a.type() == type).findFirst();
    }

    // ---- the three required scenarios ---------------------------------------

    @Test
    @DisplayName("BROWNFIELD: reasons about an existing codebase and completes end to end")
    void brownfieldRunCompletes() throws Exception {
        Path repo = targetRepo();
        TaskState state = task("run-brownfield", TaskState.Scenario.BROWNFIELD, repo);

        RunSnapshot snap = run(state, model(), new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.COMPLETED);

        // EXPLORE is the stage that distinguishes brownfield from greenfield.
        assertThat(statusOf(snap, StageId.EXPLORE)).isEqualTo(StageStatus.PASSED);
        assertThat(statusOf(snap, StageId.CLARIFY)).isEqualTo(StageStatus.SKIPPED);

        // Every producing stage delivered.
        assertThat(artifactOf(snap, Artifact.Type.IMPACT_ANALYSIS)).isPresent();
        assertThat(artifactOf(snap, Artifact.Type.TASK_PLAN)).isPresent();
        assertThat(artifactOf(snap, Artifact.Type.PATCH)).isPresent();
        assertThat(artifactOf(snap, Artifact.Type.TEST_SOURCE)).isPresent();
        assertThat(artifactOf(snap, Artifact.Type.DOCUMENTATION)).isPresent();
        assertThat(artifactOf(snap, Artifact.Type.RUN_SUMMARY)).isPresent();

        // Both human checkpoints were reached and recorded.
        List<Decision> human = snap.decisions().stream()
                .filter(d -> d.decidedBy() == Decision.Actor.HUMAN).toList();
        assertThat(human).hasSize(2);
    }

    @Test
    @DisplayName("GREENFIELD: still explores, because new code lands in an existing codebase")
    void greenfieldStillLearnsTheConventions() throws Exception {
        Path repo = targetRepo();
        TaskState state = task("run-greenfield", TaskState.Scenario.GREENFIELD, repo);

        RunSnapshot snap = run(state, model(), new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.COMPLETED);

        // EXPLORE runs. This was not always so: it originally applied only to
        // brownfield and ambiguous runs, and a live greenfield run then produced a
        // plan naming `requirements.txt` and `app/routes/` against a Java Spring
        // repository. The code is new; the conventions are not.
        assertThat(statusOf(snap, StageId.EXPLORE)).isEqualTo(StageStatus.PASSED);
        assertThat(artifactOf(snap, Artifact.Type.IMPACT_ANALYSIS)).isPresent();

        // CLARIFY is what greenfield actually skips — the requirement is specific,
        // and an empty clarification round is noise rather than diligence.
        assertThat(statusOf(snap, StageId.CLARIFY)).isEqualTo(StageStatus.SKIPPED);

        // And the rule that makes the skip safe: PLAN depends on CLARIFY and runs
        // anyway, because SKIPPED counts as successful. Leaving it PENDING would
        // deadlock the run on a stage that is never going to happen.
        assertThat(statusOf(snap, StageId.PLAN)).isEqualTo(StageStatus.PASSED);
        assertThat(artifactOf(snap, Artifact.Type.PATCH)).isPresent();
    }

    @Test
    @DisplayName("AMBIGUOUS: records assumptions and questions instead of guessing silently")
    void ambiguousRunRecordsAssumptions() throws Exception {
        Path repo = targetRepo();
        TaskState state = task("run-ambiguous", TaskState.Scenario.AMBIGUOUS, repo);

        RunSnapshot snap = run(state, model(), new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(statusOf(snap, StageId.CLARIFY)).isEqualTo(StageStatus.PASSED);

        // The guesses are first-class objects, graded by consequence — not prose
        // buried in a summary.
        List<Assumption> fromClarify = snap.assumptions().stream()
                .filter(a -> a.madeBy() == StageId.CLARIFY).toList();
        assertThat(fromClarify).hasSize(2);

        assertThat(fromClarify.stream().anyMatch(a -> a.risk() == Assumption.Risk.MEDIUM)).isTrue();
        assertThat(fromClarify.stream().anyMatch(a -> a.risk() == Assumption.Risk.LOW)).isTrue();

        // The question the requirement failed to answer is surfaced, not resolved.
        assertThat(snap.openQuestions().stream()
                .anyMatch(q -> q.contains("What should the per-IP limit actually be?"))).isTrue();
    }

    // ---- the paths that only exist because something went wrong -------------

    @Test
    @DisplayName("a HIGH security finding blocks the release gate before a human is asked")
    void highSeverityFindingBlocksReleaseBeforeAsking() throws Exception {
        Path repo = targetRepo();

        // Same pipeline, one stage swapped: the review now finds something serious.
        DeterministicLlmClient llm = model()
                .when("Review this change for security problems", """
                        FINDING | HIGH | RateLimiter.java:12 | the counter never resets, so a client is \
                        blocked permanently after 100 requests | reset the window on expiry

                        ### Residual risk
                        - None.
                        """);

        RecordingApprover approver = new RecordingApprover(ApprovalPort.Decision.APPROVE);
        RunSnapshot snap = run(task("run-blocked", TaskState.Scenario.BROWNFIELD, repo), llm, approver);

        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.FAILED);
        assertThat(statusOf(snap, StageId.RELEASE_GATE)).isEqualTo(StageStatus.FAILED);
        assertThat(statusOf(snap, StageId.SUMMARY)).isEqualTo(StageStatus.BLOCKED);

        // This is the assertion that matters. The approver would have said yes — it
        // always says yes. It was never asked, because the gate refused to open. A
        // control that merely warns is one a hurried reviewer waves through; this
        // one removes the opportunity.
        assertThat(approver.asked).containsExactly(StageId.APPROVAL_GATE);
        assertThat(approver.asked).doesNotContain(StageId.RELEASE_GATE);
    }

    @Test
    @DisplayName("a hardcoded credential is refused before it reaches disk, and is not logged")
    void secretInGeneratedCodeIsRefused() throws Exception {
        Path repo = targetRepo();

        DeterministicLlmClient llm = model()
                .when("Write the code for this plan", """
                        FILE: src/main/java/com/example/app/RateLimiter.java
                        RATIONALE: in-memory counter reporting to the analytics service
                        ```java
                        package com.example.app;

                        public class RateLimiter {
                            private static final String ANALYTICS_KEY =
                                    "sk-ant-api03-fakeKeyForTestingOnlyNotARealCredential";

                            public boolean allow(String ip) {
                                return true;
                            }
                        }
                        ```
                        """);

        RunSnapshot snap = run(task("run-secret", TaskState.Scenario.BROWNFIELD, repo), llm,
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        // IMPLEMENT exhausted its retries: the model returned the same secret each
        // time and policy refused it each time. The run halts.
        assertThat(statusOf(snap, StageId.IMPLEMENT)).isEqualTo(StageStatus.FAILED);
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.FAILED);

        // No patch artifact exists — the refusal happened before the write, which is
        // the difference between prevention and detection.
        assertThat(artifactOf(snap, Artifact.Type.PATCH)).isEmpty();

        // The human is told the agent tried. A guardrail that fires silently has
        // taught nobody anything.
        assertThat(snap.openQuestions().stream()
                .anyMatch(q -> q.contains("no-secrets-in-generated-content"))).isTrue();

        // And the audit trail must not become the second place the secret lives.
        assertThat(String.join(" ", snap.openQuestions()))
                .doesNotContain("fakeKeyForTestingOnlyNotARealCredential");
    }

    @Test
    @DisplayName("a rejected plan stops the run with nothing generated")
    void humanRejectionStopsTheRun() throws Exception {
        Path repo = targetRepo();

        RunSnapshot snap = run(task("run-rejected", TaskState.Scenario.BROWNFIELD, repo),
                model(), new ScriptedApprover(ApprovalPort.Decision.REJECT));

        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.STOPPED);
        assertThat(snap.stopReason()).contains("rejected by human");

        assertThat(statusOf(snap, StageId.APPROVAL_GATE)).isEqualTo(StageStatus.FAILED);
        assertThat(statusOf(snap, StageId.IMPLEMENT)).isEqualTo(StageStatus.BLOCKED);

        // Nothing was produced. The gate sits before generation for exactly this
        // reason: rejecting a plan costs one stage, rejecting a finished branch
        // costs the whole run.
        assertThat(artifactOf(snap, Artifact.Type.PATCH)).isEmpty();

        // A rejection is a decision too, and it is in the ledger.
        assertThat(snap.decisions().stream()
                .anyMatch(d -> d.decidedBy() == Decision.Actor.HUMAN
                        && d.choice().equals("REJECT"))).isTrue();
    }

    @Test
    @DisplayName("an unattended run refuses the checkpoint rather than proceeding")
    void unattendedRunFailsClosed() throws Exception {
        Path repo = targetRepo();

        RunSnapshot snap = run(task("run-unattended", TaskState.Scenario.BROWNFIELD, repo),
                model(), ApprovalPort.denyAll());

        // Nobody was there to look, so nothing was approved. The tempting default —
        // proceed when unattended so the pipeline does not stall — is the one that
        // makes the checkpoint decorative.
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.STOPPED);
        assertThat(artifactOf(snap, Artifact.Type.PATCH)).isEmpty();
    }

    @Test
    @DisplayName("a flaky stage retries within its budget and recovers")
    void flakyStageRecoversWithinItsRetryBudget() throws Exception {
        Path repo = targetRepo();

        // IMPLEMENT has maxAttempts(3). Fail twice, succeed on the third.
        DeterministicLlmClient llm = model()
                .whenFailingFirst("Write the code for this plan", 2, """
                        FILE: src/main/java/com/example/app/RateLimiter.java
                        RATIONALE: recovered on the third attempt
                        ```java
                        package com.example.app;

                        public class RateLimiter {
                            public boolean allow(String ip) {
                                return true;
                            }
                        }
                        ```
                        """);

        RunSnapshot snap = run(task("run-flaky", TaskState.Scenario.BROWNFIELD, repo), llm,
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(statusOf(snap, StageId.IMPLEMENT)).isEqualTo(StageStatus.PASSED);

        // Three attempts recorded, not one. A run that reported only the outcome
        // would hide the flakiness the retry budget is paying for.
        StageResult implement = snap.stages().stream()
                .filter(s -> s.stageId() == StageId.IMPLEMENT).findFirst().orElseThrow();
        assertThat(implement.attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a stage that never recovers exhausts its budget and blocks the run")
    void exhaustedRetriesBlockTheDownstreamCone() throws Exception {
        Path repo = targetRepo();

        // Fails more times than IMPLEMENT is allowed to try.
        DeterministicLlmClient llm = model()
                .whenFailingFirst("Write the code for this plan", 99, "never reached");

        RunSnapshot snap = run(task("run-exhausted", TaskState.Scenario.BROWNFIELD, repo), llm,
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(statusOf(snap, StageId.IMPLEMENT)).isEqualTo(StageStatus.FAILED);
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.FAILED);

        // One failure blocks the entire downstream cone in a single operation,
        // rather than each stage discovering the problem for itself.
        assertThat(statusOf(snap, StageId.TEST)).isEqualTo(StageStatus.BLOCKED);
        assertThat(statusOf(snap, StageId.DOCS)).isEqualTo(StageStatus.BLOCKED);
        assertThat(statusOf(snap, StageId.SECURITY_REVIEW)).isEqualTo(StageStatus.BLOCKED);
        assertThat(statusOf(snap, StageId.VALIDATE)).isEqualTo(StageStatus.BLOCKED);
        assertThat(statusOf(snap, StageId.SUMMARY)).isEqualTo(StageStatus.BLOCKED);
    }

    @Test
    @DisplayName("an incomplete change fails at the stage that caused it, not four stages later")
    void planCoverageGateCatchesAnOmittedTask() throws Exception {
        Path repo = targetRepo();

        // The plan names RateLimiter.java; the change touches something else entirely.
        // This is the real failure this gate was added for: a live run produced a
        // limiter that was never wired to the endpoint, every stage passed, and the
        // omission only surfaced at VALIDATE as a contradiction.
        DeterministicLlmClient llm = model()
                .when("Write the code for this plan", """
                        FILE: src/main/java/com/example/app/Unrelated.java
                        RATIONALE: not the file the plan asked for
                        ```java
                        package com.example.app;

                        public class Unrelated {
                        }
                        ```
                        """);

        RunSnapshot snap = run(task("run-incomplete", TaskState.Scenario.BROWNFIELD, repo), llm,
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        assertThat(statusOf(snap, StageId.IMPLEMENT)).isEqualTo(StageStatus.FAILED);
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.FAILED);

        StageResult implement = snap.stages().stream()
                .filter(s -> s.stageId() == StageId.IMPLEMENT).findFirst().orElseThrow();
        assertThat(implement.failureReason()).contains("RateLimiter.java");
        assertThat(implement.failureReason()).contains("incomplete");
    }

    // ---- properties of every run --------------------------------------------

    @Test
    @DisplayName("the target repository is never modified, whatever the run does")
    void targetRepositoryIsNeverTouched() throws Exception {
        Path repo = targetRepo();

        List<Path> before;
        try (var walk = Files.walk(repo)) {
            before = walk.filter(Files::isRegularFile).sorted().toList();
        }
        String controllerBefore = Files.readString(
                repo.resolve("src/main/java/com/example/app/LinkController.java"));

        run(task("run-untouched", TaskState.Scenario.BROWNFIELD, repo), model(),
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        List<Path> after;
        try (var walk = Files.walk(repo)) {
            after = walk.filter(Files::isRegularFile).sorted().toList();
        }

        // The strongest claim in the design, asserted rather than described: there
        // is no code path from this harness to the target working tree, so a run
        // that completes with an approved patch still changes nothing.
        assertThat(after).isEqualTo(before);
        assertThat(Files.readString(repo.resolve("src/main/java/com/example/app/LinkController.java")))
                .isEqualTo(controllerBefore);
    }

    @Test
    @DisplayName("the proposed change is written where a human can review it")
    void proposalsAreWrittenForReview() throws Exception {
        Path repo = targetRepo();

        run(task("run-proposals", TaskState.Scenario.BROWNFIELD, repo), model(),
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        Path proposals = workspace.resolve("runs/run-proposals/proposals");
        assertThat(Files.isDirectory(proposals)).isTrue();

        try (var files = Files.list(proposals)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            // The proposal and its rationale — a diff with no reason is not reviewable.
            assertThat(names).isNotEmpty();
            assertThat(names.stream().anyMatch(n -> n.contains("RateLimiter"))).isTrue();
            assertThat(names.stream().anyMatch(n -> n.endsWith(".rationale.txt"))).isTrue();
        }
    }

    @Test
    @DisplayName("a parked run is written to disk and can be read back")
    void deferredRunIsResumable() throws Exception {
        Path repo = targetRepo();
        Path runsDir = workspace.resolve("runs");

        run(task("run-deferred", TaskState.Scenario.BROWNFIELD, repo), model(),
                new ScriptedApprover(ApprovalPort.Decision.DEFER));

        // The run parked and the JVM was free to exit. What makes the checkpoint a
        // checkpoint rather than a blocking prompt is that the state outlives it.
        StateStore store = new StateStore(runsDir);
        assertThat(store.exists("run-deferred")).isTrue();

        RunSnapshot reloaded = store.load("run-deferred");
        assertThat(reloaded.runStatus()).isEqualTo(TaskState.RunStatus.AWAITING_HUMAN);
        assertThat(statusOf(reloaded, StageId.APPROVAL_GATE)).isEqualTo(StageStatus.AWAITING_APPROVAL);

        // The reasoning survived the round trip, not just the status.
        assertThat(reloaded.decisions()).isNotEmpty();
        assertThat(artifactOf(reloaded, Artifact.Type.TASK_PLAN)).isPresent();
    }

    @Test
    @DisplayName("every run leaves an append-only trace with reliability metrics")
    void everyRunIsTraceable() throws Exception {
        Path repo = targetRepo();

        run(task("run-traced", TaskState.Scenario.BROWNFIELD, repo), model(),
                new ScriptedApprover(ApprovalPort.Decision.APPROVE));

        Path trace = workspace.resolve("runs/run-traced/trace.jsonl");
        assertThat(Files.exists(trace)).isTrue();

        List<String> lines = Files.readAllLines(trace);
        assertThat(lines).isNotEmpty();

        // JSONL: one self-contained record per line, so a crash costs the last line
        // and nothing else.
        assertThat(lines.stream().allMatch(l -> l.startsWith("{") && l.endsWith("}"))).isTrue();

        String all = String.join("\n", lines);
        assertThat(all).contains("STAGE");
        assertThat(all).contains("GATE");
        assertThat(all).contains("APPROVAL");
    }

    @Test
    @DisplayName("the read tool refuses a path that escapes the repository root")
    void readToolRefusesTraversal() throws Exception {
        Path repo = targetRepo();
        Files.writeString(workspace.resolve("outside.txt"), "not yours");

        FileReadTool tool = new FileReadTool(repo);

        assertThat(tool.invoke(java.util.Map.of("path", "../outside.txt")).success()).isFalse();
        assertThat(tool.invoke(java.util.Map.of("path", "src/main/java/com/example/app/LinkService.java"))
                .success()).isTrue();
    }

    @Test
    @DisplayName("the read tool refuses a secrets file even though it is inside the repository")
    void readToolRefusesSecretsFile() throws Exception {
        Path repo = targetRepo();
        FileReadTool tool = new FileReadTool(repo);

        // Containment is the primary control; the deny-list is defence in depth for
        // things that are technically inside the repo and still nobody's business.
        var result = tool.invoke(java.util.Map.of("path", ".env"));
        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("denied by policy");
    }

    // ---- ambiguity: the loop must close ------------------------------------

    @Test
    @DisplayName("a HIGH-risk guess does not stop the harness planning — it stops it shipping")
    void ambiguityBlocksReleaseNotPlanning() throws Exception {
        Path repo = targetRepo();

        // CLARIFY now grades the ambiguity HIGH, as it did on a live run of
        // "make the links safer".
        DeterministicLlmClient llm = model()
                .when("Identify what this requirement does not specify", """
                        ASSUMPTION | HIGH | "safer" means blocking malicious destinations, not encrypting at rest | the requirement does not say which
                        QUESTION | What kind of safety is being asked for?
                        """);

        RecordingApprover approver = new RecordingApprover(ApprovalPort.Decision.APPROVE);
        RunSnapshot snap = run(task("run-ambiguous-open", TaskState.Scenario.AMBIGUOUS, repo),
                llm, approver);

        // PLAN must still plan. Refusing to work until a requirement is fully
        // specified would make the harness useless — no real requirement is.
        assertThat(statusOf(snap, StageId.PLAN)).isEqualTo(StageStatus.PASSED);
        assertThat(artifactOf(snap, Artifact.Type.TASK_PLAN)).isPresent();

        // And the human sees the plan and the question together.
        assertThat(approver.asked).contains(StageId.APPROVAL_GATE);

        // This approver approves without answering. The guess stays open, so the
        // release gate refuses.
        assertThat(statusOf(snap, StageId.RELEASE_GATE)).isEqualTo(StageStatus.FAILED);
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.FAILED);
    }

    @Test
    @DisplayName("a human answering the question unblocks the run — detection needs resolution")
    void resolvedAssumptionUnblocksRelease() throws Exception {
        Path repo = targetRepo();

        DeterministicLlmClient llm = model()
                .when("Identify what this requirement does not specify", """
                        ASSUMPTION | HIGH | "safer" means blocking malicious destinations, not encrypting at rest | the requirement does not say which
                        QUESTION | What kind of safety is being asked for?
                        """);

        RunSnapshot snap = run(task("run-ambiguous-answered", TaskState.Scenario.AMBIGUOUS, repo),
                llm, new AnsweringApprover(ApprovalPort.Decision.APPROVE));

        // The same run, with a human who answered rather than clicked. Without a
        // resolution path the harness could ask but not listen, and any run that
        // surfaced real ambiguity was dead on arrival at the release gate.
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.COMPLETED);
        assertThat(statusOf(snap, StageId.RELEASE_GATE)).isEqualTo(StageStatus.PASSED);

        // The guess is settled, not erased.
        Assumption settled = snap.assumptions().stream()
                .filter(a -> a.madeBy() == StageId.CLARIFY)
                .findFirst().orElseThrow();
        assertThat(settled.status()).isEqualTo(Assumption.Status.CONFIRMED);
        assertThat(settled.resolution()).isEqualTo("confirmed by the operator");

        // And the answer is attributed to the human in the ledger, alongside the
        // approval itself — so the trail shows what was settled, not just that
        // someone said yes.
        assertThat(snap.decisions().stream()
                .anyMatch(d -> d.decidedBy() == Decision.Actor.HUMAN
                        && d.question().startsWith("Assumption:")
                        && d.choice().equals("CONFIRMED"))).isTrue();
    }

    @Test
    @DisplayName("a human overturning a guess records it as rejected rather than erasing it")
    void rejectedAssumptionIsRetained() throws Exception {
        Path repo = targetRepo();

        DeterministicLlmClient llm = model()
                .when("Identify what this requirement does not specify", """
                        ASSUMPTION | HIGH | "safer" means blocking malicious destinations, not encrypting at rest | the requirement does not say which
                        """);

        ApprovalPort correcting = (request, state) -> {
            java.util.Map<String, ApprovalPort.Resolution> answers = new java.util.LinkedHashMap<>();
            for (Assumption a : state.assumptions()) {
                if (a.status() == Assumption.Status.OPEN && a.risk() == Assumption.Risk.HIGH) {
                    answers.put(a.id(), ApprovalPort.Resolution.reject(
                            "no — it means link expiry"));
                }
            }
            return new ApprovalPort.Response(ApprovalPort.Decision.APPROVE,
                    "approved with a correction", answers);
        };

        RunSnapshot snap = run(task("run-ambiguous-corrected", TaskState.Scenario.AMBIGUOUS, repo),
                llm, correcting);

        Assumption settled = snap.assumptions().stream()
                .filter(a -> a.madeBy() == StageId.CLARIFY)
                .findFirst().orElseThrow();

        // Rejected, not deleted. "We guessed wrong and a human caught it" is the most
        // useful line an audit trail can contain; erasing it would leave a record
        // showing only the guesses that happened to be right.
        assertThat(settled.status()).isEqualTo(Assumption.Status.REJECTED);
        assertThat(settled.resolution()).isEqualTo("no — it means link expiry");
        assertThat(settled.statement()).contains("blocking malicious destinations");

        // A rejected guess no longer blocks: the human has spoken, and the run
        // proceeds on their answer rather than on the harness's.
        assertThat(snap.runStatus()).isEqualTo(TaskState.RunStatus.COMPLETED);
    }
}
