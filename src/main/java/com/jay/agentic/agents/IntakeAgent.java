package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.List;
import java.util.Map;

/**
 * Turns a raw requirement into a structured problem statement.
 *
 * <p>The cheapest stage in the pipeline and arguably the most valuable. A
 * requirement like "make the links safer" is not a task — it is the start of a
 * conversation. Everything downstream is generated from this stage's output, so
 * a vague normalisation here becomes a vague plan, a vague patch, and a vague
 * test, and none of them will fail a gate because each is consistent with the
 * thing above it. Ambiguity is cheap to catch here and expensive everywhere else.
 *
 * <p>Runs on {@link LlmClient.Tier#FAST}: restating a sentence in structured form
 * is not a reasoning task. Paying deep-model rates for it would be spending money
 * where it makes no difference to the answer.
 */
public final class IntakeAgent implements Agent {

    private static final String SYSTEM = """
            You are the intake step of an automated software engineering pipeline.
            Your job is to restate a requirement precisely, not to solve it.

            Rules:
            - Do not propose a design, an implementation, or a file to change.
            - Do not invent detail that is not present or clearly implied.
            - If something material is unstated, say so plainly rather than guessing.
            - Be brief. This is a restatement, not an essay.
            """;

    private final LlmClient llm;

    public IntakeAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.INTAKE;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        String prompt = PromptBuilder.create()
                .requirement(state)
                .task("""
                        Restate this requirement under exactly these headings:

                        ### Goal
                        One sentence: what should be true when this is done.

                        ### In scope
                        Bullets. Only what the requirement asks for.

                        ### Out of scope
                        Bullets. Things a reader might reasonably assume are included but are not.

                        ### Unstated
                        Bullets. Material questions the requirement does not answer.
                        Write "None" if the requirement is fully specified.

                        ### Acceptance
                        Bullets. Observable conditions that would show this is done.
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.FAST, SYSTEM, prompt, 1500));
        } catch (LlmClient.LlmException e) {
            // The orchestrator decides whether this is worth retrying — not this agent.
            throw new AgentException("intake could not normalise the requirement: " + e.getMessage(), e);
        }

        String content = response.content();
        if (content.isBlank()) {
            throw new AgentException("intake produced an empty normalisation");
        }

        // The record of what this stage decided, with a pointer to the call that decided it.
        state.addDecision(Decision.byAgent(
                state.nextId("dec"),
                StageId.INTAKE,
                "What is actually being asked for?",
                summarise(content),
                "Normalised from the raw requirement; scope boundaries made explicit "
                        + "so later stages cannot quietly widen them.",
                List.of(new Decision.Alternative(
                        "Pass the raw requirement through unchanged",
                        "Leaves every later stage to interpret it independently, and they "
                                + "will not interpret it the same way")),
                List.of(EvidenceRef.llmCall(response.callId()),
                        EvidenceRef.requirement(state.requirement()))));

        // Unstated items become open questions. On an ambiguous run, CLARIFY picks
        // these up; on a well-defined run, an unstated item appearing here is itself
        // a signal worth surfacing to the human at the approval gate.
        for (String question : extractSection(content, "### Unstated")) {
            state.addOpenQuestion(question);
        }

        Artifact artifact = Artifact.of(
                state.nextId("art"),
                StageId.INTAKE,
                Artifact.Type.NORMALISED_REQUIREMENT,
                "normalised-requirement.md",
                content);

        // Reads nothing from prior stages — this is the first one.
        return Output.of(List.of(artifact), Map.of());
    }

    /** First line of the Goal section, for the decision ledger. */
    private static String summarise(String content) {
        List<String> goal = extractSection(content, "### Goal");
        return goal.isEmpty() ? content.lines().findFirst().orElse("(no goal stated)") : goal.get(0);
    }

    /**
     * Pulls bullet lines from a markdown section.
     *
     * <p>Parsing markdown rather than asking for JSON is a deliberate trade. JSON
     * would parse cleanly but this artifact's primary reader is a human at the
     * approval gate, and a wall of escaped JSON is not something anyone reviews
     * properly. The parsing is lenient by design: a section it cannot read yields
     * nothing rather than failing the stage, because a mis-parsed heading is not
     * a reason to throw away a good normalisation.
     */
    private static List<String> extractSection(String content, String heading) {
        String[] lines = content.split("\n");
        List<String> found = new java.util.ArrayList<>();
        boolean inSection = false;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("###")) {
                inSection = trimmed.equalsIgnoreCase(heading);
                continue;
            }
            if (!inSection || trimmed.isBlank()) continue;
            if (trimmed.equalsIgnoreCase("none")) continue;

            String text = trimmed.startsWith("-") || trimmed.startsWith("*")
                    ? trimmed.substring(1).strip()
                    : trimmed;
            if (!text.isBlank()) found.add(text);
        }
        return List.copyOf(found);
    }
}