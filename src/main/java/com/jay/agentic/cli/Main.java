package com.jay.agentic.cli;

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
import com.jay.agentic.hooks.Hooks;
import com.jay.agentic.llm.AnthropicLlmClient;
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
import com.jay.agentic.state.RunSnapshot;
import com.jay.agentic.state.StageResult;
import com.jay.agentic.state.StateStore;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.FileReadTool;
import com.jay.agentic.tools.GrepTool;
import com.jay.agentic.tools.PatchProposalTool;
import com.jay.agentic.tools.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;

/**
 * Command-line entry point.
 *
 * <p>This is the composition root: the one place where the graph, the agents,
 * the tools, the policies, the model client and the approval port are wired
 * together. Everything else in the codebase takes its collaborators as
 * constructor arguments and knows nothing about how they were built.
 *
 * <p>That is what makes the harness testable and re-configurable. Swapping the
 * live model for the deterministic one, or the console approver for a fail-closed
 * one, is a change here and nowhere else — no agent, no gate and no policy knows
 * or cares which it got.
 */
public final class Main {

    private static final double DEFAULT_BUDGET_USD = 2.00;

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (config == null) {
            usage();
            System.exit(2);
            return;
        }

        Path runsDir = Path.of("runs");
        StateStore store = new StateStore(runsDir);

        String runId = config.runId != null ? config.runId : newRunId();
        Path proposalsDir = runsDir.resolve(runId).resolve("proposals");

        // The model client. Deterministic by default in --mock; live otherwise.
        // Both are wrapped in the same budget/cache decorator, so the thing tested
        // is the thing that runs.
        LlmClient base = config.mock ? mockClient() : new AnthropicLlmClient();
        BudgetedLlmClient llm = new BudgetedLlmClient(base, config.budgetUsd);

        ToolRegistry tools = ToolRegistry.of(
                new FileReadTool(Path.of(config.targetRepo)),
                new GrepTool(Path.of(config.targetRepo)),
                new PatchProposalTool(proposalsDir));

        Tracer tracer = new Tracer(runsDir.resolve(runId));

        // Stateful hooks are instantiated once and registered at both ends. Two
        // instances would be two hooks: the one at AFTER_STAGE would never receive
        // the BEFORE_STAGE that sets its start time, and would report the elapsed
        // time since the epoch. Found by running it.
        HookRegistry.Hook progress = Hooks.consoleProgress();
        HookRegistry.Hook timing = Hooks.slowStageWarning(30_000);
        HookRegistry.Hook announce = Hooks.runAnnouncement();

        // Extensions are registered here, in the composition root, and nowhere else.
        // A hook that registered itself would be a hook nobody chose.
        HookRegistry hooks = new HookRegistry()
                .register(HookRegistry.Point.RUN_START, announce)
                .register(HookRegistry.Point.RUN_END, announce)
                .register(HookRegistry.Point.BEFORE_STAGE, progress)
                .register(HookRegistry.Point.AFTER_STAGE, progress)
                .register(HookRegistry.Point.ON_FAILURE, progress)
                .register(HookRegistry.Point.ON_REPLAN, progress)
                .register(HookRegistry.Point.ON_POLICY_DENIAL, progress)
                .register(HookRegistry.Point.BEFORE_STAGE, timing)
                .register(HookRegistry.Point.AFTER_STAGE, timing)
                .register(HookRegistry.Point.AFTER_STAGE, Hooks.budgetWarning(llm, 0.80));

        PolicyEngine policy = PolicyEngine.standard().withHooks(hooks);

        List<Agent> agents = List.of(
                new IntakeAgent(llm),
                new ClarifyAgent(llm),
                new ExploreAgent(llm, tools, policy),
                new PlanAgent(llm),
                new ImplementAgent(llm, tools, policy),
                new TestAgent(llm),
                new DocsAgent(llm),
                new SecurityReviewAgent(llm),
                new ValidateAgent(llm),
                new SummaryAgent(llm));

        WorkflowGraph graph = SdlcGraph.build();
        ApprovalPort approvals = config.unattended
                ? ApprovalPort.denyAll()
                : new ConsoleApprovalPort();

        Orchestrator orchestrator = new Orchestrator(graph, agents, approvals, store, tracer, hooks);

        TaskState state = new TaskState(runId, config.requirement,
                Path.of(config.targetRepo).toAbsolutePath().toString(), config.scenario);

        banner(config, runId, policy, tools, hooks);

        RunSnapshot result = orchestrator.run(state);

        report(result, llm, tracer, runsDir, runId);

        // Exit code carries the outcome, so this is usable from a script or CI.
        System.exit(switch (result.runStatus()) {
            case COMPLETED -> 0;
            case AWAITING_HUMAN -> 3;
            case STOPPED -> 4;
            default -> 1;
        });
    }

    // ---- configuration ----

    private record Config(
            String requirement,
            String targetRepo,
            TaskState.Scenario scenario,
            boolean mock,
            boolean unattended,
            double budgetUsd,
            String runId
    ) {
        static Config parse(String[] args) {
            String requirement = null;
            String repo = null;
            TaskState.Scenario scenario = null;
            boolean mock = false;
            boolean unattended = false;
            double budget = DEFAULT_BUDGET_USD;
            String runId = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--requirement" -> requirement = next(args, ++i);
                    case "--repo" -> repo = next(args, ++i);
                    case "--scenario" -> {
                        String s = next(args, ++i);
                        if (s != null) scenario = TaskState.Scenario.valueOf(s.toUpperCase());
                    }
                    case "--budget" -> {
                        String b = next(args, ++i);
                        if (b != null) budget = Double.parseDouble(b);
                    }
                    case "--run-id" -> runId = next(args, ++i);
                    case "--mock" -> mock = true;
                    case "--unattended" -> unattended = true;
                    default -> {
                        System.err.println("unknown argument: " + args[i]);
                        return null;
                    }
                }
            }

            if (requirement == null || repo == null || scenario == null) return null;
            return new Config(requirement, repo, scenario, mock, unattended, budget, runId);
        }

        private static String next(String[] args, int i) {
            return i < args.length ? args[i] : null;
        }
    }

    /**
     * The deterministic client's canned answers.
     *
     * <p>Enough to walk the whole graph offline: every gate, every policy, the
     * approval checkpoint, the fan-out and the join. A demo that needs no network
     * and costs nothing is not a lesser demo — it is the one that works when the
     * wifi does not.
     */
    private static LlmClient mockClient() {
        return new DeterministicLlmClient()
                .when("Restate this requirement", """
                        ### Goal
                        Requests to create a short link are limited per client IP.

                        ### In scope
                        - A per-IP request limit on the create endpoint

                        ### Out of scope
                        - Limiting the redirect endpoint
                        - Per-user or per-API-key limits

                        ### Unstated
                        - What the limit should be
                        - What response a limited client receives

                        ### Acceptance
                        - A client exceeding the limit receives an error rather than a short link
                        """)
                .when("Name up to 3 regular expressions", """
                        @RestController
                        @PostMapping
                        RateLimit
                        """)
                .when("name up to 2 further", "DONE")
                .when("Write the impact analysis", """
                        ### Impacted files
                        - src/main/java/com/example/urlshortener/LinkController.java — the create
                          endpoint gains a limit check.

                        ### Impacted APIs and data flows
                        - POST /api/v1/links may now return 429.

                        ### Existing patterns to follow
                        - Controllers are thin; logic lives in services.

                        ### What I could not determine
                        - Whether a proxy sits in front of the service, which would make the
                          remote address unreliable.
                        """)
                .when("Identify what this requirement does not specify", """
                        ASSUMPTION | MEDIUM | the limit is 100 requests per minute | the requirement gives no number
                        ASSUMPTION | LOW | the response is HTTP 429 | conventional for rate limiting
                        QUESTION | What should the per-IP limit actually be?
                        """)
                .when("Produce the plan", """
                        ### Approach
                        Add a rate limit check to the create endpoint using an in-memory counter
                        keyed by client IP. Smallest change that satisfies the requirement.

                        ### Tasks
                        1. [src/main/java/com/example/urlshortener/RateLimiter.java] new in-memory
                           per-IP counter — depends on: none
                        2. [src/main/java/com/example/urlshortener/LinkController.java] check the
                           limit before creating — depends on: 1

                        ### Key decision
                        DECISION | in-memory counter | single instance, no dependency needed | Redis-backed counter | adds infrastructure the requirement does not justify

                        ### Risks
                        - The counter does not survive a restart.
                        - It is per-instance, so it does not hold behind a load balancer.

                        ### Out of scope
                        - Distributed rate limiting
                        """)
                .when("Write the code for this plan", """
                        FILE: src/main/java/com/example/urlshortener/RateLimiter.java
                        RATIONALE: in-memory per-IP counter, no new dependency
```java
                        package com.example.urlshortener;

                        import java.time.Instant;
                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;

                        public class RateLimiter {
                            private static final int LIMIT = 100;
                            private final Map<String, Window> windows = new ConcurrentHashMap<>();

                            public boolean allow(String ip) {
                                Window w = windows.compute(ip, (k, existing) ->
                                        existing == null || existing.isExpired()
                                                ? new Window() : existing);
                                return w.increment() <= LIMIT;
                            }

                            private static final class Window {
                                private final Instant start = Instant.now();
                                private int count = 0;

                                boolean isExpired() {
                                    return start.plusSeconds(60).isBefore(Instant.now());
                                }

                                synchronized int increment() {
                                    return ++count;
                                }
                            }
                        }
```
                        """)
                .when("Write the tests for this change", """
                        FILE: src/test/java/com/example/urlshortener/RateLimiterTest.java
                        COVERS: the limit is enforced and the window resets
```java
                        package com.example.urlshortener;

                        import org.junit.jupiter.api.Test;
                        import static org.assertj.core.api.Assertions.assertThat;

                        class RateLimiterTest {
                            @Test
                            void allowsUpToTheLimitThenRefuses() {
                                RateLimiter limiter = new RateLimiter();
                                for (int i = 0; i < 100; i++) {
                                    assertThat(limiter.allow("1.2.3.4")).isTrue();
                                }
                                assertThat(limiter.allow("1.2.3.4")).isFalse();
                            }

                            @Test
                            void limitsAreIndependentPerIp() {
                                RateLimiter limiter = new RateLimiter();
                                for (int i = 0; i < 100; i++) limiter.allow("1.2.3.4");
                                assertThat(limiter.allow("5.6.7.8")).isTrue();
                            }
                        }
```

                        ### Not covered
                        - Window expiry, which would need a clock abstraction to test without sleeping.
                        """)
                .when("Write the documentation", """
                        ### What changed
                        POST /api/v1/links now rejects a client that exceeds 100 requests per minute.

                        ### Behaviour
                        - POST /api/v1/links returns 429 once a client IP exceeds 100 requests
                          in a rolling 60-second window.

                        ### Configuration
                        - None. The limit is currently a constant.

                        ### Operational notes
                        - The counter is per-instance and in-memory. Behind a load balancer the
                          effective limit is 100 times the instance count.

                        ### Known limitations
                        - Does not survive a restart.
                        - Trusts the remote address, which is wrong behind a proxy.
                        """)
                .when("Review this change for security problems", """
                        FINDING | MEDIUM | RateLimiter.java:14 | the remote address is trusted; behind a proxy every client shares one IP | read the client IP from a validated X-Forwarded-For, or configure the proxy protocol
                        FINDING | LOW | RateLimiter.java:9 | the map grows without bound as IPs accumulate | evict expired windows

                        ### Residual risk
                        - Whether a proxy is in front of this service could not be determined from the code.
                        """)
                .when("Check these artifacts against each other", """
                        CONSISTENT

                        ### Requirement coverage
                        - A client exceeding the limit receives an error rather than a short link: yes — RateLimiter returns false above 100 and the controller returns 429.
                        """)
                .when("Write the assessment paragraph", """
                        This is mergeable with one caveat that needs a human answer first: the
                        limiter trusts the remote address, so if this service sits behind a proxy
                        the limit applies to the proxy rather than to clients, and it will be
                        useless. The chosen limit of 100 per minute is an assumption, not a
                        requirement. The in-memory counter is per-instance and will not hold
                        across a horizontal scale-out, which is acceptable for now but should be
                        recorded as known.
                        """);
    }

    // ---- output ----

    private static void banner(Config config, String runId, PolicyEngine policy,
                               ToolRegistry tools, HookRegistry hooks) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("  agentic-harness — run " + runId);
        System.out.println("=".repeat(78));
        System.out.println("  Requirement : " + config.requirement);
        System.out.println("  Target repo : " + config.targetRepo);
        System.out.println("  Scenario    : " + config.scenario);
        System.out.println("  Model       : " + (config.mock ? "deterministic (offline)" : "live"));
        System.out.println("  Budget      : $" + String.format("%.2f", config.budgetUsd));
        System.out.println("  Approvals   : " + (config.unattended ? "denied (unattended)" : "console"));
        System.out.println("\n  Tools available to agents:");
        System.out.print(tools.catalogue().indent(2));
        System.out.println("  Policies in force:");
        System.out.print(policy.describe().indent(2));
        System.out.println("  Extensions registered (" + hooks.count() + "):");
        System.out.print(hooks.describe());
        System.out.println("=".repeat(78) + "\n");
    }

    private static void report(RunSnapshot result, BudgetedLlmClient llm, Tracer tracer,
                               Path runsDir, String runId) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("  Run " + result.runId() + " — " + result.runStatus());
        if (result.stopReason() != null) {
            System.out.println("  Stopped: " + result.stopReason());
        }
        System.out.println("=".repeat(78));

        System.out.println("\n  Stages:");
        for (StageResult r : result.stages()) {
            System.out.printf("    %-16s %-18s %s%n", r.stageId(), r.status(),
                    r.failureReason() == null ? "" : r.failureReason());
        }

        System.out.println("\n  Reliability: " + tracer.metrics().report());
        System.out.println("  Cost: " + llm.report());

        System.out.println("\n  Artifacts written to: " + runsDir.resolve(runId));
        result.artifacts().stream()
                .filter(a -> a.type() == Artifact.Type.RUN_SUMMARY)
                .findFirst()
                .ifPresent(a -> System.out.println("\n" + a.content()));

        System.out.println("\n  Full run state: " + runsDir.resolve(runId).resolve("state.json"));
        System.out.println("  Execution trace: " + tracer.traceFile());
        System.out.println("  Proposed changes: " + runsDir.resolve(runId).resolve("proposals"));
        System.out.println("\n  Nothing has been applied to the target repository.\n");
    }

    private static String newRunId() {
        return "run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
    }

    private static void usage() {
        System.err.println("""

                agentic-harness — takes a requirement through an SDLC pipeline

                Usage:
                  --requirement "<text>"     what to build          (required)
                  --repo <path>              target repository      (required)
                  --scenario <type>          greenfield | brownfield | ambiguous   (required)
                  --mock                     run offline against canned responses
                  --unattended               refuse all approvals (fail closed)
                  --budget <usd>             spending cap per run   (default 2.00)
                  --run-id <id>              name this run

                Example:
                  mvn -q exec:java -Dexec.args='--mock --scenario brownfield \\
                    --repo ../url-shortener \\
                    --requirement "Add per-IP rate limiting to POST /api/v1/links"'
                """);
    }
}