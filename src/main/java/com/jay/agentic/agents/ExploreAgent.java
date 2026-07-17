package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.policy.PolicyEngine;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.Tool;
import com.jay.agentic.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which parts of the target codebase a change touches.
 *
 * <p>This is the stage that separates a code generator from an engineering
 * assistant. Asked to add rate limiting, a generator writes a plausible rate
 * limiter; this stage first goes and finds out that the endpoint already has an
 * interceptor chain, that the service is behind a cache, and that there is an
 * existing config class the limit belongs in. The patch that follows is a change
 * to a real codebase rather than a well-formed guess about one.
 *
 * <p>It runs a bounded search-then-read loop rather than a free agent loop. Two
 * rounds of searching, then read at most six files. The bound is the point: an
 * unbounded explorer will read the whole repository into context, because from
 * inside the loop there is always one more file that might matter. Cost control
 * and context hygiene are the same constraint here.
 */
public final class ExploreAgent implements Agent {

    private static final int MAX_SEARCH_ROUNDS = 2;
    private static final int MAX_FILES_READ = 6;

    private static final String SYSTEM = """
            You are the codebase exploration step of an automated software engineering
            pipeline. You find out what a change would touch. You do not design it.

            Rules:
            - Reason from what the tools return. Do not assert anything about the
              codebase you have not seen.
            - If a search returns nothing, that is information: your assumption about
              the codebase was wrong. Say so and search differently.
            - Do not propose an implementation.
            - Prefer few, precise searches over many broad ones.
            """;

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final PolicyEngine policy;

    public ExploreAgent(LlmClient llm, ToolRegistry tools, PolicyEngine policy) {
        this.llm = llm;
        this.tools = tools;
        this.policy = policy;
    }

    @Override
    public StageId stage() {
        return StageId.EXPLORE;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        var normalised = state.latestArtifact(Artifact.Type.NORMALISED_REQUIREMENT)
                .orElseThrow(() -> new AgentException("explore has no normalised requirement"));

        StringBuilder findings = new StringBuilder();
        Set<String> filesSeen = new LinkedHashSet<>();
        List<EvidenceRef> evidence = new ArrayList<>();

        // Round 1..n: ask what to search for, run it, feed results back.
        for (int round = 1; round <= MAX_SEARCH_ROUNDS; round++) {
            List<String> patterns = proposeSearches(state, findings.toString(), round);
            if (patterns.isEmpty()) break;

            for (String pattern : patterns) {
                Tool.Result result = invoke("search_repo",
                        Map.of("pattern", pattern, "glob", "*.java"), state);

                findings.append("\n### Search: /").append(pattern).append("/\n")
                        .append(result.success() ? result.output() : "failed: " + result.failureReason())
                        .append('\n');

                if (result.success()) {
                    evidence.add(EvidenceRef.toolCall("search_repo:" + pattern));
                    filesSeen.addAll(pathsFrom(result.output()));
                }
            }
        }

        // Then read the files that came up, up to the cap.
        List<String> toRead = filesSeen.stream().limit(MAX_FILES_READ).toList();
        for (String path : toRead) {
            Tool.Result result = invoke("read_file", Map.of("path", path), state);
            if (result.success()) {
                findings.append("\n### File: ").append(path).append("\n```java\n")
                        .append(result.output()).append("\n```\n");
                evidence.add(EvidenceRef.sourceFile(path, null));
            }
        }

        if (filesSeen.size() > MAX_FILES_READ) {
            // Said out loud rather than silently dropped — the analysis is partial and
            // whoever reads it needs to know which way it is partial.
            findings.append("\n[").append(filesSeen.size() - MAX_FILES_READ)
                    .append(" further matching files were not read; read cap is ")
                    .append(MAX_FILES_READ).append("]\n");
        }

        String analysis = synthesise(state, findings.toString(), evidence);

        state.addDecision(Decision.byAgent(
                state.nextId("dec"), StageId.EXPLORE,
                "Which parts of the codebase does this change touch?",
                toRead.isEmpty() ? "no impacted files identified" : String.join(", ", toRead),
                "Identified by searching for the concepts in the requirement, then reading "
                        + "the files that matched. Limited to " + MAX_FILES_READ + " files to "
                        + "bound context size.",
                List.of(new Decision.Alternative(
                        "Read the whole repository into context",
                        "Unaffordable, and buries the relevant three files in noise")),
                evidence));

        Artifact artifact = Artifact.of(
                state.nextId("art"), StageId.EXPLORE, Artifact.Type.IMPACT_ANALYSIS,
                "impact-analysis.md", analysis);

        return Output.of(List.of(artifact),
                Map.of(normalised.id(), normalised.contentHash()));
    }

    /** Asks the model what to look for, given what has been found so far. */
    private List<String> proposeSearches(TaskState state, String soFar, int round) throws AgentException {
        String task = round == 1
                ? """
                  Name up to 3 regular expressions to search this Java codebase for, to find
                  what this change would touch. Think about class names, annotations, and
                  method names the relevant code would plausibly contain.

                  Output one regex per line. No prose, no numbering, no explanation.
                  """
                : """
                  Given what the previous searches returned, name up to 2 further regular
                  expressions that would fill a gap in your understanding.

                  Output one regex per line. No prose. If the previous searches were
                  sufficient, output the single word: DONE
                  """;

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.NORMALISED_REQUIREMENT, "Normalised requirement")
                .section("What the searches have returned so far", soFar)
                .task(task)
                .build();

        try {
            // FAST tier: choosing a search term is pattern-matching, not reasoning.
            LlmClient.Response r = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.FAST, SYSTEM, prompt, 300));

            List<String> patterns = new ArrayList<>();
            for (String line : r.content().split("\n")) {
                String p = line.strip();
                if (p.isBlank() || p.equalsIgnoreCase("DONE")) continue;
                if (p.startsWith("`") && p.endsWith("`") && p.length() > 2) {
                    p = p.substring(1, p.length() - 1);
                }
                patterns.add(p);
            }
            return patterns;
        } catch (LlmClient.LlmException e) {
            throw new AgentException("explore could not propose searches: " + e.getMessage(), e);
        }
    }

    /** Turns raw tool output into an impact analysis a human can read. */
    private String synthesise(TaskState state, String findings, List<EvidenceRef> evidence)
            throws AgentException {

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.NORMALISED_REQUIREMENT, "Normalised requirement")
                .section("What the tools returned", findings)
                .task("""
                        Write the impact analysis under exactly these headings:

                        ### Impacted files
                        Bullets: path — what would change in it, and why.

                        ### Impacted APIs and data flows
                        Bullets. Public behaviour that would change. "None" if nothing.

                        ### Existing patterns to follow
                        Bullets. Conventions in this codebase the change should match.

                        ### What I could not determine
                        Bullets. Gaps in what the searches showed you. "None" if complete.

                        Cite a file path for every claim. Do not assert anything you did not see.
                        """)
                .build();

        try {
            // DEEP tier: this is the reasoning, and it is what everything downstream reads.
            LlmClient.Response r = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 3000));

            if (r.content().isBlank()) {
                throw new AgentException("explore produced an empty impact analysis");
            }
            return r.content();
        } catch (LlmClient.LlmException e) {
            throw new AgentException("explore could not synthesise findings: " + e.getMessage(), e);
        }
    }

    /** Every tool call goes through the policy engine. There is no direct path. */
    private Tool.Result invoke(String toolName, Map<String, String> args, TaskState state) {
        return tools.find(toolName)
                .map(t -> policy.invoke(t, args, StageId.EXPLORE, state))
                .orElse(Tool.Result.failed("tool not registered: " + toolName));
    }

    /** Pulls "path:line: text" back to just the path. */
    private static Set<String> pathsFrom(String grepOutput) {
        Set<String> paths = new LinkedHashSet<>();
        for (String line : grepOutput.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) paths.add(line.substring(0, colon));
        }
        return paths;
    }
}