package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decomposes the requirement into ordered tasks with dependencies.
 *
 * <p>The stage a human actually reviews. Everything after this is generated from
 * its output, and the approval gate sits immediately downstream — which makes
 * this the last cheap place to be wrong. A bad plan caught here costs one stage;
 * the same bad plan caught at release costs the run.
 *
 * <p>That is also why the plan is prose rather than a machine-readable structure.
 * The temptation is to emit a task graph the harness could schedule; the reason
 * not to is that nothing downstream schedules it. IMPLEMENT reads the plan as
 * context, and the human reads it as a decision. Optimising it for a consumer
 * that does not exist would make it worse for the two that do.
 *
 * <p>Runs on {@link LlmClient.Tier#DEEP}. Sequencing work against a real codebase
 * is the reasoning this pipeline exists to buy.
 */
public final class PlanAgent implements Agent {

    private static final String SYSTEM = """
            You are the planning step of an automated software engineering pipeline.
            You decompose a requirement into an ordered sequence of concrete tasks.

            Rules:
            - Every task must be small enough that its completion is unambiguous.
            - State dependencies explicitly. If two tasks are independent, say so.
            - Ground every task in a real file path from the impact analysis. Do not
              invent paths.
            - Where the impact analysis says something could not be determined, do not
              paper over it — plan around it or flag it.
            - Do not write code. This is a plan.
            - Prefer the smallest change that satisfies the requirement.
            """;

    private final LlmClient llm;

    public PlanAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.PLAN;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        var normalised = state.latestArtifact(Artifact.Type.NORMALISED_REQUIREMENT)
                .orElseThrow(() -> new AgentException("plan has no normalised requirement"));

        // Inputs read are recorded exactly, because this is what makes the plan's
        // staleness detectable. If the impact analysis is re-run and changes, this
        // plan was built on a codebase picture that no longer holds.
        Map<String, String> inputs = new HashMap<>();
        inputs.put(normalised.id(), normalised.contentHash());
        state.latestArtifact(Artifact.Type.IMPACT_ANALYSIS)
                .ifPresent(a -> inputs.put(a.id(), a.contentHash()));

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.NORMALISED_REQUIREMENT, "Normalised requirement")
                .artifact(state, Artifact.Type.IMPACT_ANALYSIS, "Impact analysis")
                .assumptions(state)
                .decisions(state)
                .task("""
                        Produce the plan under exactly these headings:

                        ### Approach
                        Two or three sentences: the shape of the change and why this shape.

                        ### Tasks
                        Numbered. One line each, in the form:
                        N. [file path] what changes — depends on: none | task numbers

                        ### Key decision
                        The most consequential choice in this plan, in the form:
                        DECISION | what was chosen | why | what was rejected | why it was rejected

                        ### Risks
                        Bullets. What could go wrong with this approach. "None" if genuinely none.

                        ### Out of scope
                        Bullets. What this plan deliberately does not do.
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 3000));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("plan could not decompose the requirement: " + e.getMessage(), e);
        }

        String content = response.content();
        if (content.isBlank()) {
            throw new AgentException("plan produced no task decomposition");
        }

        recordKeyDecision(state, content, response.callId());

        Artifact artifact = Artifact.of(
                state.nextId("art"), StageId.PLAN, Artifact.Type.TASK_PLAN,
                "task-plan.md", content);

        return Output.of(List.of(artifact), inputs);
    }

    /**
     * Lifts the model's stated key decision into the ledger.
     *
     * <p>Asking for one decision rather than all of them is deliberate. A model
     * asked to enumerate its decisions will produce fifteen, most of them
     * restatements of the task list, and the ledger becomes noise that nobody
     * reads — which is the same as having no ledger. One decision, chosen by the
     * model as the most consequential, is a claim a human can actually check.
     */
    private void recordKeyDecision(TaskState state, String content, String callId) {
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (!line.toUpperCase().startsWith("DECISION")) continue;

            String[] parts = line.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].strip();
            }

            if (parts.length >= 5) {
                state.addDecision(Decision.byAgent(
                        state.nextId("dec"), StageId.PLAN,
                        "What is the shape of this change?",
                        parts[1], parts[2],
                        List.of(new Decision.Alternative(parts[3], parts[4])),
                        List.of(EvidenceRef.llmCall(callId))));
                return;
            }
        }

        // No parseable decision line. Recorded as a fact rather than passed over:
        // a plan whose reasoning could not be extracted is a plan the human should
        // read more carefully, not less.
        state.addDecision(Decision.byAgent(
                state.nextId("dec"), StageId.PLAN,
                "What is the shape of this change?",
                "see the plan artifact",
                "The plan did not state a key decision in the expected form; its reasoning "
                        + "is in the artifact rather than in this ledger entry.",
                List.of(), List.of(EvidenceRef.llmCall(callId))));
    }
}