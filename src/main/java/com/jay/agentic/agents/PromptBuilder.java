package com.jay.agentic.agents;

import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.TaskState;

import java.util.List;

/**
 * Assembles the context handed to a model for a stage.
 *
 * <p>Centralised rather than each agent building its own prompt, for a reason
 * that is about cost and correctness rather than tidiness. Context is the
 * expensive part of a model call — it is paid for on every request — and an
 * agent left to assemble its own will reach for everything it can see. Eleven
 * agents each independently deciding to include "the full run state, to be
 * safe" is how a harness ends up costing ten dollars a run and losing the
 * important detail in the noise.
 *
 * <p>The rule enforced here: an agent gets what it needs and nothing else.
 * That is the assignment's "avoidance of stale or irrelevant context" made
 * mechanical instead of aspirational.
 */
public final class PromptBuilder {

    /** Content longer than this is summarised rather than passed whole. */
    private static final int MAX_ARTIFACT_CHARS = 8_000;

    private final StringBuilder sb = new StringBuilder();

    private PromptBuilder() {
    }

    public static PromptBuilder create() {
        return new PromptBuilder();
    }

    /** The requirement, always. Every stage needs to know what was asked. */
    public PromptBuilder requirement(TaskState state) {
        sb.append("## The requirement\n\n")
                .append(state.requirement()).append("\n\n")
                .append("Scenario type: ").append(state.scenario()).append("\n\n");
        return this;
    }

    /** A named artifact, truncated if it is large. */
    public PromptBuilder artifact(TaskState state, Artifact.Type type, String heading) {
        state.latestArtifact(type).ifPresent(a -> {
            sb.append("## ").append(heading).append("\n\n");
            String content = a.content();
            if (content.length() > MAX_ARTIFACT_CHARS) {
                sb.append(content, 0, MAX_ARTIFACT_CHARS)
                        .append("\n\n[truncated at ").append(MAX_ARTIFACT_CHARS)
                        .append(" characters of ").append(content.length()).append("]\n\n");
            } else {
                sb.append(content).append("\n\n");
            }
        });
        return this;
    }

    /**
     * Assumptions made so far.
     *
     * <p>Included so a later stage does not re-guess something an earlier stage
     * already guessed — or worse, guess the opposite. Two stages holding
     * contradictory assumptions produce a patch and a test that disagree, and
     * nothing downstream would catch it.
     */
    public PromptBuilder assumptions(TaskState state) {
        List<Assumption> open = state.assumptions().stream()
                .filter(a -> a.status() == Assumption.Status.OPEN)
                .toList();
        if (open.isEmpty()) return this;

        sb.append("## Assumptions already made — treat these as settled\n\n");
        for (Assumption a : open) {
            sb.append("- [").append(a.risk()).append("] ").append(a.statement())
                    .append(" (because: ").append(a.reason()).append(")\n");
        }
        sb.append('\n');
        return this;
    }

    /** Decisions taken, so a later stage does not silently reverse an earlier one. */
    public PromptBuilder decisions(TaskState state) {
        List<Decision> decisions = state.decisions();
        if (decisions.isEmpty()) return this;

        sb.append("## Decisions already taken — do not revisit these\n\n");
        for (Decision d : decisions) {
            sb.append("- ").append(d.question()).append(" → ").append(d.choice())
                    .append(" (").append(d.rationale()).append(")");
            if (d.decidedBy() == Decision.Actor.HUMAN) {
                sb.append(" [decided by a human]");
            }
            sb.append('\n');
        }
        sb.append('\n');
        return this;
    }

    /** Free-form section. */
    public PromptBuilder section(String heading, String body) {
        if (body == null || body.isBlank()) return this;
        sb.append("## ").append(heading).append("\n\n").append(body).append("\n\n");
        return this;
    }

    /** The instruction, last. Models attend most reliably to the end of a prompt. */
    public PromptBuilder task(String instruction) {
        sb.append("## Your task\n\n").append(instruction).append('\n');
        return this;
    }

    public String build() {
        return sb.toString();
    }

    public int approximateTokens() {
        return sb.length() / 4;
    }
}