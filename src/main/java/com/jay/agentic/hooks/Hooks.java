package com.jay.agentic.hooks;

import com.jay.agentic.llm.BudgetedLlmClient;

/**
 * The hooks this harness ships with.
 *
 * <p>Each is also an example of the shape a new one takes. They are registered in
 * {@code Main} rather than here, so the composition root remains the single place
 * that decides what a given run does — a hook that registered itself would be a
 * hook nobody chose.
 */
public final class Hooks {

    private Hooks() {
    }

    /**
     * Prints stage transitions as they happen.
     *
     * <p>Without this the run is silent for a minute at a time while a model
     * thinks, and silence is indistinguishable from a hang. This is a hook rather
     * than a println in the orchestrator because progress reporting is a property
     * of the console front-end, not of the workflow: a web front-end would want
     * events, and a CI run would want neither.
     */
    public static HookRegistry.Hook consoleProgress() {
        return new HookRegistry.Hook() {
            @Override
            public String name() {
                return "console-progress";
            }

            @Override
            public void on(HookRegistry.Event e) {
                switch (e.point()) {
                    case BEFORE_STAGE -> System.out.println("  ▸ " + e.stage() + " …");
                    case AFTER_STAGE -> System.out.println("  ✓ " + e.stage());
                    case ON_FAILURE -> System.out.println("  ✗ " + e.stage() + " — " + e.detail());
                    case ON_REPLAN -> System.out.println("  ↻ " + e.stage()
                            + " invalidated — " + e.detail());
                    case ON_POLICY_DENIAL -> System.out.println("  ⛔ " + e.stage()
                            + " — refused: " + e.detail());
                    default -> { }
                }
            }
        };
    }

    /**
     * Warns once when the run has spent most of its allowance.
     *
     * <p>The budget cap stops the run; this warns before it does. The distinction
     * matters — a run that safe-stops at the cap has produced nothing usable, and
     * a warning at 80% is the difference between noticing and being surprised.
     */
    public static HookRegistry.Hook budgetWarning(BudgetedLlmClient llm, double threshold) {
        return new HookRegistry.Hook() {
            private boolean warned = false;

            @Override
            public String name() {
                return "budget-warning";
            }

            @Override
            public void on(HookRegistry.Event e) {
                if (warned) return;
                double spent = llm.budgetUsd() - llm.remainingUsd();
                if (spent >= llm.budgetUsd() * threshold) {
                    warned = true;
                    System.out.printf("  ⚠ budget: $%.4f of $%.2f spent (%.0f%%) — "
                                    + "the run will safe-stop at the cap%n",
                            spent, llm.budgetUsd(), threshold * 100);
                }
            }
        };
    }

    /**
     * Flags any stage slower than a threshold.
     *
     * <p>An example of the observability category being extensible rather than
     * fixed: the tracer records every duration, and this decides which of them a
     * human should be told about. What counts as slow is a policy question, and
     * policy questions belong outside the thing being measured.
     */
    public static HookRegistry.Hook slowStageWarning(long millis) {
        return new HookRegistry.Hook() {
            private long startedAt;

            @Override
            public String name() {
                return "slow-stage-warning";
            }

            @Override
            public void on(HookRegistry.Event e) {
                if (e.point() == HookRegistry.Point.BEFORE_STAGE) {
                    startedAt = System.currentTimeMillis();
                } else if (e.point() == HookRegistry.Point.AFTER_STAGE) {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (elapsed > millis) {
                        System.out.printf("  ⏱ %s took %.1fs%n", e.stage(), elapsed / 1000.0);
                    }
                }
            }
        };
    }

    /**
     * Announces what a run is about to do.
     *
     * <p>Trivial, and it is here to make the point that RUN_START is a real
     * extension point: an enterprise deployment would use it to check that the
     * target repository is one this harness is permitted to touch, before a single
     * token is spent.
     */
    public static HookRegistry.Hook runAnnouncement() {
        return new HookRegistry.Hook() {
            @Override
            public String name() {
                return "run-announcement";
            }

            @Override
            public void on(HookRegistry.Event e) {
                if (e.point() == HookRegistry.Point.RUN_START) {
                    System.out.println("  Starting " + e.state().scenario()
                            + " run against " + e.state().targetRepoPath() + "\n");
                } else if (e.point() == HookRegistry.Point.RUN_END) {
                    System.out.println("\n  Run finished: " + e.detail());
                }
            }
        };
    }
}
