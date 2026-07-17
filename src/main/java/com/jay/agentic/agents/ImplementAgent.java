package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.policy.PolicyEngine;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;
import com.jay.agentic.tools.Tool;
import com.jay.agentic.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the proposed change.
 *
 * <p>Everything upstream exists to constrain this stage, and everything
 * downstream exists to check it. It is also the only stage that invokes a
 * mutating tool, which means it is the only stage where the approval gate has
 * teeth: if {@code APPROVAL_GATE} has not passed, every {@code propose_patch}
 * call here is refused by policy and the stage fails. The gate is not advice
 * this agent chooses to follow — it is a wall this agent runs into.
 *
 * <p>Note what it still cannot do, even with approval: it proposes a file into
 * the run's proposals directory. It has no way to touch the target repository,
 * because no such tool exists. Approval widens what may be recorded, not what
 * may be changed.
 *
 * <p>Runs on {@link LlmClient.Tier#DEEP} and with {@code maxAttempts(3)} in the
 * graph. Code generation is the flakiest step in the pipeline and the most
 * expensive to get wrong.
 */
public final class ImplementAgent implements Agent {

    private static final String SYSTEM = """
            You are the implementation step of an automated software engineering pipeline.
            You write production-quality Java against an existing codebase.

            Rules:
            - Follow the plan. If the plan is wrong, say so rather than silently deviating.
            - Match the conventions the impact analysis found in this codebase.
            - Never write a credential, key, token or password into code. Read them from
              the environment and reference them by name.
            - Do not modify build files or add dependencies.
            - Write the complete file, not a fragment or a diff.
            - Prefer the smallest change that satisfies the plan.
            """;

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final PolicyEngine policy;

    public ImplementAgent(LlmClient llm, ToolRegistry tools, PolicyEngine policy) {
        this.llm = llm;
        this.tools = tools;
        this.policy = policy;
    }

    @Override
    public StageId stage() {
        return StageId.IMPLEMENT;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        var plan = state.latestArtifact(Artifact.Type.TASK_PLAN)
                .orElseThrow(() -> new AgentException("implement has no plan to work from"));

        Map<String, String> inputs = new HashMap<>();
        inputs.put(plan.id(), plan.contentHash());
        state.latestArtifact(Artifact.Type.IMPACT_ANALYSIS)
                .ifPresent(a -> inputs.put(a.id(), a.contentHash()));

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.IMPACT_ANALYSIS, "Impact analysis")
                .artifact(state, Artifact.Type.TASK_PLAN, "The approved plan")
                .assumptions(state)
                .decisions(state)
                .task("""
                        Write the code for this plan.

                        Use exactly this format for each file, and no other prose:

                        FILE: <repo-relative path>
                        RATIONALE: <one line: why this file, why this way>
```java
                        <the complete file contents>
```

                        Rules for this output:
                        - Implement every task in the plan. If a task cannot be done, say why
                          on a final line beginning "DEFERRED:" — do not silently omit it.
                        - At most 6 files.
                        - Every file must be complete and compile on its own.
                        - No placeholder comments such as "// implementation here".
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 8000));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("implement could not generate code: " + e.getMessage(), e);
        }

        List<ProposedFile> files = parse(response.content());
        if (files.isEmpty()) {
            // The classic silent failure: the model returned an apology, a question, or
            // prose. The stage "ran" and produced nothing. Fail loudly so the retry
            // budget is spent on it rather than the exit gate discovering it later.
            throw new AgentException("implement produced no parseable file; the model returned: "
                    + firstLine(response.content()));
        }

        List<Artifact> artifacts = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        for (ProposedFile file : files) {
            Tool.Result result = invoke("propose_patch", Map.of(
                    "path", file.path(),
                    "content", file.content(),
                    "rationale", file.rationale()), state);

            if (result.success()) {
                artifacts.add(Artifact.of(
                        state.nextId("art"), StageId.IMPLEMENT, Artifact.Type.PATCH,
                        file.path(), file.content()));
            } else {
                refused.add(file.path() + ": " + result.failureReason());
            }
        }

        if (artifacts.isEmpty()) {
            // Every proposal was refused by policy — a hardcoded secret, a build file,
            // or an unapproved plan. Not a model failure, and the reason matters, so it
            // is carried into the message rather than reported as "implement failed".
            throw new AgentException("every proposed file was refused: " + refused);
        }

        if (!refused.isEmpty()) {
            // Partial success. The run continues with what was permitted, and the human
            // is told what was not — a refusal nobody hears about is a refusal that
            // teaches nobody anything.
            state.addOpenQuestion("IMPLEMENT: " + refused.size()
                    + " proposed file(s) were refused by policy: " + refused);
        }

        return Output.of(artifacts, inputs);
    }

    private record ProposedFile(String path, String rationale, String content) {}

    /**
     * Pulls FILE / RATIONALE / fenced-code triples out of the response.
     *
     * <p>Line-oriented and deliberately strict: a block missing its path or its
     * fence is skipped rather than guessed at. Guessing here would mean writing a
     * file to a path the model did not actually name, which is a worse outcome
     * than the stage retrying.
     */
    private static List<ProposedFile> parse(String content) {
        List<ProposedFile> files = new ArrayList<>();
        String[] lines = content.split("\n");

        String path = null;
        String rationale = "";
        StringBuilder code = null;

        for (String raw : lines) {
            String line = raw.strip();

            if (line.startsWith("FILE:")) {
                path = line.substring(5).strip();
                rationale = "";
                code = null;
            } else if (line.startsWith("RATIONALE:") && path != null) {
                rationale = line.substring(10).strip();
            } else if (line.startsWith("```") && path != null && code == null) {
                code = new StringBuilder();
            } else if (line.startsWith("```") && code != null) {
                if (!code.isEmpty()) {
                    files.add(new ProposedFile(path, rationale, code.toString()));
                }
                path = null;
                code = null;
            } else if (code != null) {
                code.append(raw).append('\n');
            }
        }
        return files;
    }

    private Tool.Result invoke(String toolName, Map<String, String> args, TaskState state) {
        return tools.find(toolName)
                .map(t -> policy.invoke(t, args, StageId.IMPLEMENT, state))
                .orElse(Tool.Result.failed("tool not registered: " + toolName));
    }

    private static String firstLine(String s) {
        return s == null || s.isBlank() ? "(empty)" : s.lines().findFirst().orElse("(empty)").strip();
    }
}