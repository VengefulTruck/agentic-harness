package com.jay.agentic.orchestration;

import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SDLC pipeline this harness runs, declared as a graph.
 *
 * <pre>
 *   INTAKE
 *     |
 *     +----------------+
 *     |                |
 *   CLARIFY         EXPLORE          &lt;-- scenario-dependent; SKIPPED when not applicable
 *     |                |
 *     +--------+-------+
 *              |
 *            PLAN                    &lt;-- waits for both: a plan needs the codebase picture
 *              |
 *        APPROVAL_GATE               &lt;-- human. Nothing is written before this point.
 *              |
 *          IMPLEMENT
 *              |
 *     +--------------+------------------+
 *     |              |                  |
 *   TEST           DOCS        SECURITY_REVIEW     &lt;-- parallel, no mutual dependency
 *     |              |                  |
 *     +--------------+------------------+
 *                    |
 *                VALIDATE                          &lt;-- join barrier: waits for all three
 *                    |
 *              RELEASE_GATE   &lt;-- human. Refuses while high-risk assumptions are open.
 *                    |
 *                 SUMMARY
 * </pre>
 *
 * <p>The fan-out after IMPLEMENT is not configured anywhere. TEST, DOCS and
 * SECURITY_REVIEW each depend on IMPLEMENT and on nothing else, so all three
 * become ready in the same scheduling pass. VALIDATE depends on all three, so
 * it cannot start until the slowest finishes. The join is a consequence of the
 * dependency set, not a barrier object someone had to remember to insert.
 *
 * <p>PLAN's dependency on CLARIFY and EXPLORE was added after a live run: with
 * PLAN depending only on INTAKE, it became ready in the same pass as EXPLORE and
 * ran concurrently with it, so it planned against an impact analysis that did not
 * yet exist. The emergent parallelism that makes the fan-out free made that
 * mistake free too. It works across all three scenarios because SKIPPED counts as
 * successful — a greenfield run skips both and PLAN proceeds regardless.
 */
public final class SdlcGraph {

    private SdlcGraph() {
    }

    public static WorkflowGraph build() {
        return WorkflowGraph.of(List.of(

                StageNode.of(StageId.INTAKE)
                        .describedAs("Normalise the raw requirement into a structured problem statement")
                        .exitGate(hasArtifact(Artifact.Type.NORMALISED_REQUIREMENT,
                                "intake produced no normalised requirement"))
                        .build(),

                // Only meaningful when the requirement is under-specified. On a well-defined
                // run this is SKIPPED rather than run and passed — an empty clarification
                // round is noise in the audit log, not evidence of diligence.
                StageNode.of(StageId.CLARIFY)
                        .describedAs("Surface ambiguities as open questions and recorded assumptions")
                        .dependsOn(StageId.INTAKE)
                        .onlyFor(TaskState.Scenario.AMBIGUOUS)
                        .exitGate(state -> state.assumptions().isEmpty() && state.openQuestions().isEmpty()
                                ? Gate.Verdict.fail("clarify found no ambiguity in a run marked ambiguous")
                                : Gate.Verdict.pass())
                        .build(),

                // Applies to every scenario, including greenfield.
                //
                // This originally ran only for BROWNFIELD and AMBIGUOUS, on the reasoning
                // that a new capability has no existing code to reason about. A live
                // greenfield run proved that wrong: with no codebase picture, PLAN guessed
                // the stack — it proposed `requirements.txt` and `app/routes/` against a
                // Java Spring repository — and IMPLEMENT then refused to write code it knew
                // was founded on nothing.
                //
                // The mistake was conflating "a new system, from nothing" with "a new
                // feature, in something". Almost every real greenfield task is the second:
                // the code is new, the conventions are not. EXPLORE for a greenfield run is
                // not looking for code to modify — it is looking for the patterns the new
                // code must match, and for confirmation that the capability does not
                // already exist.
                //
                // When there genuinely is no target repository, the searches return nothing
                // and the impact analysis says so. That is a finding, not a failure.
                StageNode.of(StageId.EXPLORE)
                        .describedAs("Identify impacted modules, APIs, data flows and conventions in the target repo")
                        .dependsOn(StageId.INTAKE)
                        .exitGate(hasArtifact(Artifact.Type.IMPACT_ANALYSIS,
                                "explore produced no impact analysis"))
                        .maxAttempts(2)
                        .build(),

                // Note what this gate does NOT check: unresolved high-risk assumptions.
                //
                // It originally did, and that was a contradiction with the rest of the
                // design. A live run of "make the links safer" proved it: CLARIFY correctly
                // graded two assumptions HIGH — whether "safer" meant phishing protection
                // or data leakage, and whether "links" even meant hyperlinks — and PLAN
                // then refused to plan. The run died without a human ever seeing the
                // questions.
                //
                // That is the "refuse until fully specified" failure mode this harness
                // exists to avoid. Ambiguity is not a reason to stop working; it is a
                // reason to work visibly and stop before shipping. So the plan is built on
                // the assumptions, the human sees both at APPROVAL_GATE and resolves them
                // there, and RELEASE_GATE is where an unresolved guess still blocks.
                StageNode.of(StageId.PLAN)
                        .describedAs("Decompose into ordered tasks with dependencies")
                        .dependsOn(StageId.INTAKE, StageId.CLARIFY, StageId.EXPLORE)
                        .exitGate(hasArtifact(Artifact.Type.TASK_PLAN, "plan produced no task plan"))
                        .maxAttempts(2)
                        .build(),

                // The autonomy boundary. A human sees the plan and the reasoning before
                // any code exists. Placing it here rather than before the merge is
                // deliberate: rejecting a plan costs one stage, rejecting a finished
                // branch costs the whole run.
                StageNode.of(StageId.APPROVAL_GATE)
                        .describedAs("Human reviews the plan, assumptions and impact before implementation")
                        .dependsOn(StageId.PLAN)
                        .requiresApproval()
                        .build(),

                StageNode.of(StageId.IMPLEMENT)
                        .describedAs("Produce a patch against the target repo — proposed, never applied")
                        .dependsOn(StageId.APPROVAL_GATE)
                        .exitGate(hasArtifact(Artifact.Type.PATCH, "implement produced no patch")
                                .and(planTasksCovered()))
                        .maxAttempts(3)
                        .build(),

                StageNode.of(StageId.TEST)
                        .describedAs("Author unit and integration tests for the proposed change")
                        .dependsOn(StageId.IMPLEMENT)
                        .exitGate(hasArtifact(Artifact.Type.TEST_SOURCE, "no tests were produced"))
                        .maxAttempts(3)
                        .build(),

                StageNode.of(StageId.DOCS)
                        .describedAs("Update API documentation and the change record")
                        .dependsOn(StageId.IMPLEMENT)
                        .exitGate(hasArtifact(Artifact.Type.DOCUMENTATION, "no documentation was produced"))
                        .maxAttempts(2)
                        .build(),

                StageNode.of(StageId.SECURITY_REVIEW)
                        .describedAs("Scan the proposed patch for secrets, injection and unsafe operations")
                        .dependsOn(StageId.IMPLEMENT)
                        .exitGate(hasArtifact(Artifact.Type.REVIEW_REPORT, "no security review report"))
                        .build(),

                // The join. Depends on all three parallel branches, so it cannot begin
                // until every one is successful — and if any is BLOCKED, so is this.
                StageNode.of(StageId.VALIDATE)
                        .describedAs("Check the patch, tests, docs and review together for coherence")
                        .dependsOn(StageId.TEST, StageId.DOCS, StageId.SECURITY_REVIEW)
                        .maxAttempts(2)
                        .build(),

                // The second human checkpoint, and the one with teeth. Its entry gate
                // refuses to open while an unresolved high-risk assumption remains, so
                // a guess the harness made cannot be shipped by an approver who did not
                // notice it — the gate blocks before a human is even asked.
                StageNode.of(StageId.RELEASE_GATE)
                        .describedAs("Human approves the branch; blocked while high-risk assumptions are open")
                        .dependsOn(StageId.VALIDATE)
                        .entryGate(noOpenHighRiskAssumptions())
                        .requiresApproval()
                        .build(),

                StageNode.of(StageId.SUMMARY)
                        .describedAs("Emit the final engineering summary: plan, rationale, risks, limitations")
                        .dependsOn(StageId.RELEASE_GATE)
                        .exitGate(hasArtifact(Artifact.Type.RUN_SUMMARY, "no run summary was produced"))
                        .build()
        ));
    }

    // ---- reusable gate conditions ----

    /** The minimum bar for any generative stage: it must have produced the thing it exists to produce. */
    private static Gate hasArtifact(Artifact.Type type, String failureMessage) {
        return state -> state.latestArtifact(type)
                .filter(a -> !a.content().isBlank())
                .map(a -> Gate.Verdict.pass("produced " + a.name() + " (" + a.shortHash() + ")"))
                .orElseGet(() -> Gate.Verdict.fail(failureMessage));
    }

    /** Unresolved guesses that would ship incorrect behaviour must be settled by a human first. */
    private static Gate noOpenHighRiskAssumptions() {
        return state -> {
            var blockers = state.releaseBlockers();
            if (blockers.isEmpty()) return Gate.Verdict.pass();
            String detail = blockers.stream().map(a -> a.id() + ": " + a.statement()).toList().toString();
            return Gate.Verdict.fail(blockers.size() + " unresolved high-risk assumption(s) " + detail);
        };
    }

    /**
     * Every non-test file the plan names must appear in the proposed change.
     *
     * <p>Added after a live run produced a rate limiter that was never wired to the
     * endpoint it was meant to protect. Every stage passed its own exit gate, the
     * code compiled, and the change did nothing — IMPLEMENT had run out of its file
     * budget and quietly dropped the integration task. VALIDATE caught it, four
     * stages and several model calls later, as a contradiction.
     *
     * <p>The gate exists so that "the model omitted a planned task" and "the stage
     * failed" are the same event. A failure is retryable at the stage that caused
     * it; a contradiction discovered downstream is not.
     *
     * <p>The match is deliberately loose — bare filename, not full path. The plan
     * and the patch can legitimately disagree about path prefixes, and a strict
     * comparison would fail honest work. It is a completeness check, not a
     * conformance one.
     */
    private static Gate planTasksCovered() {
        // A file path inside the plan's task list: at least one directory segment,
        // then a Java filename.
        Pattern javaPath = Pattern.compile("[\\w/.-]+/(\\w+\\.java)");

        return state -> {
            var plan = state.latestArtifact(Artifact.Type.TASK_PLAN);
            if (plan.isEmpty()) return Gate.Verdict.pass("no plan to check against");

            List<String> proposed = state.artifactsOfType(Artifact.Type.PATCH).stream()
                    .map(a -> a.name().toLowerCase())
                    .toList();

            List<String> missing = new ArrayList<>();
            Matcher matcher = javaPath.matcher(plan.get().content());

            while (matcher.find()) {
                String fileName = matcher.group(1);
                String lower = fileName.toLowerCase();

                // Test files are TEST's responsibility, not IMPLEMENT's.
                if (lower.endsWith("test.java")) continue;
                if (missing.contains(fileName)) continue;

                if (proposed.stream().noneMatch(p -> p.contains(lower))) {
                    missing.add(fileName);
                }
            }

            return missing.isEmpty()
                    ? Gate.Verdict.pass("all " + proposed.size() + " planned file(s) proposed")
                    : Gate.Verdict.fail("the plan names " + missing.size()
                            + " file(s) the change does not touch: " + missing
                            + " — the change is incomplete as proposed");
        };
    }
}