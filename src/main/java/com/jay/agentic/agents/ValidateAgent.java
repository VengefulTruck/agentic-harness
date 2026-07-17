package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the patch, the tests, the docs and the review against each other.
 *
 * <p>The join, and the only stage that sees all three branches at once. That is
 * its whole reason for existing: every stage before it was individually correct
 * about its own output and blind to everyone else's. The patch enforces a limit
 * of 100 per minute, the tests assert 60, and the docs say 100 — each artifact
 * passed its own exit gate, and the change is incoherent. Nothing upstream could
 * have caught that, because nothing upstream saw more than one of them.
 *
 * <p>Coherence, not quality. Whether the patch is good was TEST and
 * SECURITY_REVIEW's question. Whether the four artifacts describe the same
 * change is this one's, and no other stage is positioned to ask it.
 */
public final class ValidateAgent implements Agent {

    private static final String SYSTEM = """
            You are the validation step of an automated software engineering pipeline.
            Several stages produced work independently. You check whether they agree.

            Rules:
            - Look for contradictions between the artifacts: a value in the code that
              differs from the tests, behaviour in the docs that the code does not
              implement, a review finding the code does not address.
            - Check the change against the original requirement. Stages drift; a plan
              can be followed faithfully and still end up solving a different problem.
            - Do not re-review the code for quality. That has been done.
            - Report only contradictions you can point at. Say CONSISTENT if there are none.
            """;

    private final LlmClient llm;

    public ValidateAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.VALIDATE;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        List<Artifact> patches = state.artifactsOfType(Artifact.Type.PATCH);
        if (patches.isEmpty()) {
            throw new AgentException("validate has no proposed change to check");
        }

        // Fingerprints everything from all three branches. This stage is downstream of
        // the whole fan-out, so any of it moving makes this verdict stale.
        Map<String, String> inputs = new HashMap<>();
        for (Artifact a : patches) inputs.put(a.id(), a.contentHash());
        for (Artifact a : state.artifactsOfType(Artifact.Type.TEST_SOURCE)) {
            inputs.put(a.id(), a.contentHash());
        }
        state.latestArtifact(Artifact.Type.DOCUMENTATION)
                .ifPresent(a -> inputs.put(a.id(), a.contentHash()));
        state.latestArtifact(Artifact.Type.REVIEW_REPORT)
                .ifPresent(a -> inputs.put(a.id(), a.contentHash()));

        StringBuilder bundle = new StringBuilder();
        for (Artifact p : patches) {
            bundle.append("## Proposed code: ").append(p.name()).append("\n```java\n")
                    .append(p.content()).append("\n```\n\n");
        }
        for (Artifact t : state.artifactsOfType(Artifact.Type.TEST_SOURCE)) {
            bundle.append("## Tests: ").append(t.name()).append("\n```java\n")
                    .append(t.content()).append("\n```\n\n");
        }

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.TASK_PLAN, "The approved plan")
                .section("The change bundle", bundle.toString())
                .artifact(state, Artifact.Type.DOCUMENTATION, "The documentation")
                .artifact(state, Artifact.Type.REVIEW_REPORT, "The security review")
                .assumptions(state)
                .task("""
                        Check these artifacts against each other and against the requirement.

                        One contradiction per line, in exactly this form, no other prose:

                        CONFLICT | SEVERITY | between which artifacts | what disagrees

                        Rules for this output:
                        - SEVERITY is one of LOW, MEDIUM, HIGH.
                        - HIGH means the change would not do what the requirement asked, or
                          the artifacts describe materially different behaviour.
                        - If everything agrees, output the single line: CONSISTENT

                        Then, always, a final section:

                        ### Requirement coverage
                        For each acceptance condition in the requirement: does this change
                        satisfy it? State the condition and yes / no / partial, with a reason.
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 3000));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("validate could not check the change: " + e.getMessage(), e);
        }

        int high = recordConflicts(state, response);

        state.addDecision(Decision.byAgent(
                state.nextId("dec"), StageId.VALIDATE,
                "Do the code, tests, documentation and review describe the same change?",
                high > 0 ? high + " high-severity contradiction(s) — release gate will block"
                        : "artifacts are consistent",
                "Each upstream stage validated its own output and could not see the others. "
                        + "This is the only point at which the change is examined as a whole.",
                List.of(new Decision.Alternative(
                        "Trust each stage's own exit gate",
                        "Every artifact can pass its own gate while collectively describing "
                                + "different behaviour")),
                List.of(EvidenceRef.llmCall(response.callId()))));

        Artifact artifact = Artifact.of(
                state.nextId("art"), StageId.VALIDATE, Artifact.Type.REVIEW_REPORT,
                "validation.md",
                "# Validation\n\n" + response.content()
                        + "\n\n---\nEvidence: model call " + response.callId() + "\n");

        return Output.of(List.of(artifact), inputs);
    }

    /** HIGH conflicts become blocking assumptions, same mechanism as security findings. */
    private int recordConflicts(TaskState state, LlmClient.Response response) {
        int highCount = 0;

        for (String raw : response.content().split("\n")) {
            String line = raw.strip();
            if (line.isBlank() || line.equalsIgnoreCase("CONSISTENT")) continue;
            if (!line.toUpperCase().startsWith("CONFLICT")) continue;

            String[] parts = line.split("\\|");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
            if (parts.length < 4) continue;

            Assumption.Risk risk;
            try {
                risk = Assumption.Risk.valueOf(parts[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                risk = Assumption.Risk.HIGH;
            }
            if (risk == Assumption.Risk.HIGH) highCount++;

            state.addAssumption(Assumption.open(
                    state.nextId("asm"), StageId.VALIDATE,
                    parts[3] + " (between " + parts[2] + ")",
                    "validation found the artifacts disagree",
                    risk,
                    List.of(EvidenceRef.llmCall(response.callId()))));
        }

        for (String gap : extractSection(response.content(), "### Requirement coverage")) {
            String lower = gap.toLowerCase();
            // Only the shortfalls become questions. Recording every satisfied condition
            // would bury the two that are not in fifteen that are.
            if (lower.contains("no") || lower.contains("partial")) {
                state.addOpenQuestion("Requirement coverage: " + gap);
            }
        }
        return highCount;
    }

    private static List<String> extractSection(String content, String heading) {
        List<String> found = new ArrayList<>();
        boolean in = false;
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("###")) {
                in = line.equalsIgnoreCase(heading);
                continue;
            }
            if (!in || line.isBlank()) continue;
            String text = line.startsWith("-") || line.startsWith("*")
                    ? line.substring(1).strip() : line;
            if (!text.isBlank()) found.add(text);
        }
        return List.copyOf(found);
    }
}