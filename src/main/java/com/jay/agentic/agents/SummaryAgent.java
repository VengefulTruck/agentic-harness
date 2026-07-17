package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageResult;
import com.jay.agentic.state.StageStatus;
import com.jay.agentic.state.TaskState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the final engineering summary.
 *
 * <p>Mostly not a model call. The facts of the run — what each stage did, what
 * was decided, what was assumed, what was refused, where a human intervened —
 * are already recorded in state, and a model asked to narrate them would
 * paraphrase, embellish, and occasionally invent. The summary is assembled from
 * the ledger directly; the model is used only for the one paragraph that is
 * genuinely a judgement rather than a fact.
 *
 * <p>That is the honest division of labour: the model writes the prose that
 * needs writing, and the state writes everything that needs to be true.
 */
public final class SummaryAgent implements Agent {

    private static final String SYSTEM = """
            You are the closing step of an automated software engineering pipeline.
            You write the assessment paragraph a reviewer reads first.

            Rules:
            - State plainly whether this change is ready for a human to merge, and why.
            - Lead with what is wrong or uncertain, not with what went well.
            - Do not restate the facts you are given. They are already in the report.
            - No praise, no hedging, no summary of the summary. Three or four sentences.
            """;

    private final LlmClient llm;

    public SummaryAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.SUMMARY;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        Map<String, String> inputs = new HashMap<>();
        for (Artifact a : state.artifactsOfType(Artifact.Type.PATCH)) {
            inputs.put(a.id(), a.contentHash());
        }

        String facts = assembleFacts(state);
        String assessment = assess(state, facts);

        String summary = """
                # Engineering summary

                **Run:** %s
                **Requirement:** %s
                **Scenario:** %s
                **Target repository:** %s

                ## Assessment

                %s

                %s

                ---

                _Produced by the agentic harness. Every artifact referenced above is a
                proposal. Nothing has been applied to the target repository._
                """.formatted(
                state.runId(),
                state.requirement(),
                state.scenario(),
                state.targetRepoPath(),
                assessment,
                facts);

        Artifact artifact = Artifact.of(
                state.nextId("art"), StageId.SUMMARY, Artifact.Type.RUN_SUMMARY,
                "engineering-summary.md", summary);

        return Output.of(List.of(artifact), inputs);
    }

    /** Everything factual, straight from the ledger. No model involved. */
    private String assembleFacts(TaskState state) {
        StringBuilder sb = new StringBuilder();

        sb.append("## What was produced\n\n");
        for (Artifact.Type type : List.of(Artifact.Type.PATCH, Artifact.Type.TEST_SOURCE,
                Artifact.Type.DOCUMENTATION, Artifact.Type.REVIEW_REPORT)) {
            for (Artifact a : state.artifactsOfType(type)) {
                sb.append("- `").append(a.name()).append("` — ")
                        .append(type).append(" (").append(a.shortHash()).append(")\n");
            }
        }

        sb.append("\n## Stages\n\n| Stage | Outcome | Attempts |\n|---|---|---|\n");
        for (StageId id : StageId.values()) {
            StageResult r = state.stage(id);
            if (r == null) continue;

            // SUMMARY reports itself as RUNNING because this table is built while it
            // is still executing. Labelled rather than doctored: claiming it had
            // passed would be the summary's first lie.
            String note = id == StageId.SUMMARY
                    ? " (in progress — this summary)"
                    : (r.failureReason() == null ? "" : " — " + r.failureReason());

            sb.append("| ").append(id).append(" | ").append(r.status()).append(note)
                    .append(" | ").append(r.attempts()).append(" |\n");
        }

        sb.append("\n## Decisions\n\n");
        for (Decision d : state.decisions()) {
            sb.append("- **").append(d.question()).append("** → ").append(d.choice())
                    .append("\n  - ").append(d.rationale()).append('\n');
            for (Decision.Alternative alt : d.alternatives()) {
                sb.append("  - Rejected: ").append(alt.option())
                        .append(" — ").append(alt.rejectedBecause()).append('\n');
            }
            if (d.decidedBy() == Decision.Actor.HUMAN) {
                sb.append("  - _Decided by a human._\n");
            }
        }

        // Where autonomy stopped, enumerated rather than described. This is the answer
        // to "where does the human come in" — not a paragraph, a list.
        List<Decision> human = state.humanDecisions();
        sb.append("\n## Human checkpoints\n\n");
        if (human.isEmpty()) {
            sb.append("_None reached in this run._\n");
        } else {
            for (Decision d : human) {
                sb.append("- ").append(d.stage()).append(": ").append(d.choice())
                        .append(" — ").append(d.rationale()).append('\n');
            }
        }

        sb.append("\n## Assumptions\n\n");
        if (state.assumptions().isEmpty()) {
            sb.append("_None recorded._\n");
        } else {
            for (Assumption a : state.assumptions()) {
                sb.append("- [").append(a.risk()).append("/").append(a.status()).append("] ")
                        .append(a.statement()).append("\n  - _").append(a.reason()).append("_\n");
                if (a.resolution() != null) {
                    sb.append("  - Resolved: ").append(a.resolution()).append('\n');
                }
            }
        }

        List<Assumption> blockers = state.releaseBlockers();
        if (!blockers.isEmpty()) {
            sb.append("\n> **This run is blocked.** ").append(blockers.size())
                    .append(" unresolved high-risk assumption(s) must be settled by a human "
                            + "before release.\n");
        }

        sb.append("\n## Open questions and limitations\n\n");
        if (state.openQuestions().isEmpty()) {
            sb.append("_None._\n");
        } else {
            for (String q : state.openQuestions()) {
                sb.append("- ").append(q).append('\n');
            }
        }

        return sb.toString();
    }

    /** The one paragraph that is a judgement rather than a fact. */
    private String assess(TaskState state, String facts) throws AgentException {
        String prompt = PromptBuilder.create()
                .requirement(state)
                .section("The facts of this run", facts)
                .task("""
                        Write the assessment paragraph. Three or four sentences.

                        Say whether a human should merge this, what they must check first,
                        and what you are least confident about. Lead with the problems.
                        """)
                .build();

        try {
            // FAST: the reasoning is already done and recorded. This is prose.
            LlmClient.Response r = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.FAST, SYSTEM, prompt, 600));

            return r.content().isBlank()
                    ? "_The assessment step returned nothing. The facts below stand on their own._"
                    : r.content();

        } catch (LlmClient.LlmException e) {
            // A failed assessment must not lose the report. The facts are the valuable
            // part and they came from state, not from a model — losing them because a
            // narration call failed would be absurd.
            return "_The assessment step failed (" + e.getMessage()
                    + "). The facts below are unaffected._";
        }
    }
}