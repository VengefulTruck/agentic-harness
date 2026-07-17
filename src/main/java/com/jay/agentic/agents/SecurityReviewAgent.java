package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.policy.SecretScanner;
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
 * Reviews the proposed change for security problems.
 *
 * <p>Two passes, deliberately unlike each other. The deterministic scan finds
 * what patterns find and never has an off day. The model pass finds what patterns
 * cannot — a missing authorisation check, an ordering bug, a limit that can be
 * trivially bypassed — and is occasionally wrong in both directions.
 *
 * <p>Running only the model pass would mean a review that misses a hardcoded key
 * because it was distracted by an interesting logic question. Running only the
 * scanner would mean a review that catches nothing a regex cannot express. The
 * pair covers each other's blind spot, and neither is the "real" one.
 *
 * <p>{@code maxAttempts(1)} in the graph. A security review that fails is a
 * broken review, not an unlucky one — retrying a deterministic scan spends money
 * to get the same answer, and retrying the model pass until it comes back clean
 * is not a review, it is shopping for a verdict.
 */
public final class SecurityReviewAgent implements Agent {

    private static final String SYSTEM = """
            You are the security review step of an automated software engineering pipeline.
            You find what an attacker would find.

            Rules:
            - Report only what you can point at in the code. A finding without a line is
              speculation, and speculation is what makes reviews get ignored.
            - Consider: injection, authorisation gaps, resource exhaustion, unsafe
              deserialisation, secrets, information disclosure in errors, race conditions,
              and controls that can be bypassed rather than broken.
            - Rate honestly. A review where everything is critical is a review nobody reads.
            - If the change is genuinely sound, say so. Inventing a finding to look
              thorough is worse than finding nothing.
            """;

    private final LlmClient llm;

    public SecurityReviewAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.SECURITY_REVIEW;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        List<Artifact> patches = state.artifactsOfType(Artifact.Type.PATCH);
        if (patches.isEmpty()) {
            throw new AgentException("security review has no proposed change to review");
        }

        Map<String, String> inputs = new HashMap<>();
        for (Artifact p : patches) {
            inputs.put(p.id(), p.contentHash());
        }

        // Pass 1: deterministic. Costs nothing, never wrong about what it knows.
        List<String> scanFindings = new ArrayList<>();
        for (Artifact p : patches) {
            for (SecretScanner.Finding f : SecretScanner.scan(p.content())) {
                scanFindings.add(p.name() + " — " + f);
            }
        }

        // A secret reaching here at all means the policy engine was bypassed, which is
        // a harness fault rather than a review finding. Recorded as HIGH so the release
        // gate blocks regardless of what the model pass concludes.
        if (!scanFindings.isEmpty()) {
            state.addAssumption(Assumption.open(
                    state.nextId("asm"), StageId.SECURITY_REVIEW,
                    "credential-shaped strings are present in the proposed code",
                    "the scanner found " + scanFindings.size() + " match(es): " + scanFindings,
                    Assumption.Risk.HIGH,
                    List.of(EvidenceRef.toolCall("secret-scanner"))));
        }

        StringBuilder code = new StringBuilder();
        for (Artifact p : patches) {
            code.append("### ").append(p.name()).append("\n```java\n")
                    .append(p.content()).append("\n```\n\n");
        }

        // Pass 2: the model.
        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.IMPACT_ANALYSIS, "Impact analysis")
                .section("The proposed code", code.toString())
                .section("What the deterministic scanner found",
                        scanFindings.isEmpty() ? "Nothing." : String.join("\n", scanFindings))
                .assumptions(state)
                .task("""
                        Review this change for security problems.

                        One finding per line, in exactly this form, no other prose:

                        FINDING | SEVERITY | file:line | what is wrong | how to fix it

                        Rules for this output:
                        - SEVERITY is one of LOW, MEDIUM, HIGH.
                        - HIGH means exploitable as written, or a credential exposure.
                        - At most 8 findings. Choose the ones that matter.
                        - If you find nothing, output the single line: CLEAN

                        Then, always, a final section:

                        ### Residual risk
                        Bullets: what this review could not determine from the code alone.
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 2500));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("security review could not complete: " + e.getMessage(), e);
        }

        int high = recordFindings(state, response);

        String report = "# Security review\n\n"
                + "## Deterministic scan\n\n"
                + (scanFindings.isEmpty() ? "No credential patterns found.\n"
                        : String.join("\n", scanFindings) + "\n")
                + "\n## Review\n\n" + response.content()
                + "\n\n---\nEvidence: model call " + response.callId() + "\n";

        state.addDecision(Decision.byAgent(
                state.nextId("dec"), StageId.SECURITY_REVIEW,
                "Is this change safe to propose for merge?",
                high > 0 ? high + " high-severity finding(s) — release gate will block"
                        : "no high-severity findings",
                "Reviewed by a deterministic scan for known credential shapes and by a model "
                        + "for logic-level issues the scan cannot express. Neither alone is "
                        + "sufficient.",
                List.of(new Decision.Alternative(
                        "Rely on the model review alone",
                        "Misses a hardcoded key whenever the model is attending to something "
                                + "more interesting")),
                List.of(EvidenceRef.llmCall(response.callId()),
                        EvidenceRef.toolCall("secret-scanner"))));

        Artifact artifact = Artifact.of(
                state.nextId("art"), StageId.SECURITY_REVIEW, Artifact.Type.REVIEW_REPORT,
                "security-review.md", report);

        return Output.of(List.of(artifact), inputs);
    }

    /**
     * Lifts findings into state. HIGH becomes a blocking assumption.
     *
     * <p>This is where the review acquires teeth. A HIGH finding becomes an OPEN
     * HIGH-risk assumption, and {@code RELEASE_GATE}'s entry gate refuses to open
     * while one exists — so the run stops before a human is even asked. A review
     * that only wrote a report would be a review that a hurried approver skips.
     */
    private int recordFindings(TaskState state, LlmClient.Response response) {
        int highCount = 0;

        for (String raw : response.content().split("\n")) {
            String line = raw.strip();
            if (line.isBlank() || line.equalsIgnoreCase("CLEAN")) continue;
            if (!line.toUpperCase().startsWith("FINDING")) continue;

            String[] parts = line.split("\\|");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].strip();
            if (parts.length < 5) continue;

            Assumption.Risk risk;
            try {
                risk = Assumption.Risk.valueOf(parts[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                risk = Assumption.Risk.HIGH;   // unreadable severity is treated as serious
            }

            if (risk == Assumption.Risk.HIGH) highCount++;

            state.addAssumption(Assumption.open(
                    state.nextId("asm"), StageId.SECURITY_REVIEW,
                    parts[3] + " (at " + parts[2] + ")",
                    "security review finding; suggested fix: " + parts[4],
                    risk,
                    List.of(EvidenceRef.llmCall(response.callId()),
                            EvidenceRef.sourceFile(parts[2], null))));
        }

        for (String risk : extractSection(response.content(), "### Residual risk")) {
            state.addOpenQuestion("Residual security risk: " + risk);
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
            if (!in || line.isBlank() || line.equalsIgnoreCase("none")) continue;
            String text = line.startsWith("-") || line.startsWith("*")
                    ? line.substring(1).strip() : line;
            if (!text.isBlank()) found.add(text);
        }
        return List.copyOf(found);
    }
}