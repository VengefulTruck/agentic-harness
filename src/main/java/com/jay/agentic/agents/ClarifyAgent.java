package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns ambiguity into recorded assumptions and open questions.
 *
 * <p>The interesting stage, because the naive answers are both wrong. Refusing
 * to proceed until a human specifies everything makes the harness useless — no
 * real requirement is fully specified. Guessing silently makes it dangerous.
 * The workable answer is to guess, to write the guess down, and to grade it: a
 * guess about a status code carries different weight from a guess about whether
 * "safer" means blocking malicious URLs or encrypting them at rest.
 *
 * <p>The risk grade is not decoration. HIGH assumptions block the release gate
 * via {@code Assumption.blocksRelease()}, so the model's own judgement about
 * consequence is wired directly to whether a human is forced to look. That is
 * the whole design: the harness may guess, but it may not ship a guess that
 * matters without someone saying yes.
 *
 * <p>Runs on {@link LlmClient.Tier#DEEP}. Judging what could go wrong if a guess
 * is wrong is exactly the reasoning worth paying for.
 */
public final class ClarifyAgent implements Agent {

    private static final String SYSTEM = """
            You are the clarification step of an automated software engineering pipeline.
            You identify what a requirement fails to specify, and you grade how much
            each gap matters.

            Rules:
            - An assumption is something you must take as true to proceed. State it as a
              claim, not as a question.
            - An open question is something a human must answer. State it as a question.
            - Grade every assumption LOW, MEDIUM or HIGH:
                LOW    - wrong guess costs a trivial rework
                MEDIUM - wrong guess invalidates a design decision
                HIGH   - wrong guess ships incorrect, unsafe or non-compliant behaviour
            - Grade honestly. Grading everything HIGH is as useless as grading nothing HIGH.
            - Do not invent ambiguity. If the requirement is clear, say so.
            - Do not propose a design.
            """;

    private final LlmClient llm;

    public ClarifyAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.CLARIFY;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        var normalised = state.latestArtifact(Artifact.Type.NORMALISED_REQUIREMENT)
                .orElseThrow(() -> new AgentException(
                        "clarify has no normalised requirement to work from"));

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.NORMALISED_REQUIREMENT, "Normalised requirement")
                .task("""
                        Identify what this requirement does not specify.

                        Use exactly this format, one item per line, no other prose:

                        ASSUMPTION | RISK | statement | why you had to assume it
                        QUESTION | the question a human must answer

                        Rules for this output:
                        - RISK is one of LOW, MEDIUM, HIGH.
                        - Every HIGH assumption must also appear as a QUESTION.
                        - At most 6 assumptions and 4 questions. Choose the ones that matter.
                        - If the requirement is genuinely unambiguous, output the single line:
                          NONE
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 2000));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("clarify could not analyse the requirement: " + e.getMessage(), e);
        }

        List<String> recorded = parse(state, response);

        Artifact artifact = Artifact.of(
                state.nextId("art"),
                StageId.CLARIFY,
                Artifact.Type.DESIGN,
                "clarification.md",
                "# Clarification\n\n" + response.content());

        return Output.of(List.of(artifact),
                Map.of(normalised.id(), normalised.contentHash()));
    }

    /**
     * Parses the pipe-delimited lines into state.
     *
     * <p>A malformed line is skipped rather than failing the stage. That is a real
     * trade-off with a real cost: a mangled HIGH assumption is silently lost, and
     * the release gate then passes something it should have blocked. The mitigation
     * is that the raw response is preserved verbatim in the artifact, so a human at
     * the approval gate sees what the model actually said even when the parser
     * missed it. Structured tool-use output would remove the gap entirely and is
     * the right answer with more time.
     */
    private List<String> parse(TaskState state, LlmClient.Response response) {
        List<String> recorded = new ArrayList<>();

        for (String raw : response.content().split("\n")) {
            String line = raw.strip();
            if (line.isBlank() || line.equalsIgnoreCase("NONE")) continue;

            String[] parts = line.split("\\|");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].strip();
            }

            if (parts[0].equalsIgnoreCase("ASSUMPTION") && parts.length >= 4) {
                Assumption.Risk risk;
                try {
                    risk = Assumption.Risk.valueOf(parts[1].toUpperCase());
                } catch (IllegalArgumentException e) {
                    // An unreadable grade is treated as HIGH, not discarded. If the model
                    // could not say how much this matters, that uncertainty is itself a
                    // reason to make a human look.
                    risk = Assumption.Risk.HIGH;
                }
                state.addAssumption(Assumption.open(
                        state.nextId("asm"), StageId.CLARIFY, parts[2], parts[3], risk,
                        List.of(EvidenceRef.llmCall(response.callId()))));
                recorded.add(parts[2]);

            } else if (parts[0].equalsIgnoreCase("QUESTION") && parts.length >= 2) {
                state.addOpenQuestion(parts[1]);
                recorded.add(parts[1]);
            }
        }
        return recorded;
    }
}