package com.jay.agentic.agents;

import com.jay.agentic.llm.LlmClient;
import com.jay.agentic.state.Artifact;
import com.jay.agentic.state.EvidenceRef;
import com.jay.agentic.state.Decision;
import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes tests for the proposed change.
 *
 * <p>Runs concurrently with DOCS and SECURITY_REVIEW. Nothing here coordinates
 * with them, and nothing needs to: all three depend on IMPLEMENT and on nothing
 * else, so the graph makes them ready together and the orchestrator submits them
 * together. This agent does not know it is running in parallel, which is the
 * point — an agent that knew would be an agent that could be wrong about it.
 *
 * <p>It reads the patch and fingerprints it. That fingerprint is why a rejected
 * patch does not ship stale tests: IMPLEMENT re-runs, the patch hash changes,
 * and these tests are marked STALE and re-run against the code that actually
 * exists rather than the code that used to.
 */
public final class TestAgent implements Agent {

    private static final String SYSTEM = """
            You are the test authoring step of an automated software engineering pipeline.
            You write tests that would catch this change being wrong.

            Rules:
            - Test behaviour, not implementation. A test that asserts a method was called
              tells you nothing about whether the code works.
            - Cover the failure paths, not only the happy path. The happy path is the
              case least likely to be broken.
            - Use JUnit 5 and AssertJ. Match the conventions visible in the impact analysis.
            - Every test needs a name that states what it proves.
            - Do not test the framework. Do not assert that a getter returns what was set.
            - If the change is untestable as written, say so rather than writing a test
              that passes regardless.
            """;

    private final LlmClient llm;

    public TestAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public StageId stage() {
        return StageId.TEST;
    }

    @Override
    public Output execute(TaskState state) throws AgentException {
        List<Artifact> patches = state.artifactsOfType(Artifact.Type.PATCH);
        if (patches.isEmpty()) {
            throw new AgentException("test has no proposed change to write tests for");
        }

        // Fingerprint every patch, not just the latest. Tests are written against the
        // whole change; if any part of it moves, these tests are stale.
        Map<String, String> inputs = new HashMap<>();
        for (Artifact p : patches) {
            inputs.put(p.id(), p.contentHash());
        }
        state.latestArtifact(Artifact.Type.TASK_PLAN)
                .ifPresent(a -> inputs.put(a.id(), a.contentHash()));

        StringBuilder code = new StringBuilder();
        for (Artifact p : patches) {
            code.append("### ").append(p.name()).append("\n```java\n")
                    .append(p.content()).append("\n```\n\n");
        }

        String prompt = PromptBuilder.create()
                .requirement(state)
                .artifact(state, Artifact.Type.TASK_PLAN, "The approved plan")
                .section("The proposed code", code.toString())
                .assumptions(state)
                .task("""
                        Write the tests for this change.

                        Use exactly this format for each test file, and no other prose:

                        FILE: <repo-relative path under src/test/java>
                        COVERS: <one line: what these tests would catch>
```java
                        <the complete test file>
```

                        Rules for this output:
                        - At most 2 files.
                        - Each file complete and compilable on its own.
                        - Include at least one test for a failure or edge case.
                        - After the files, one final section:

                        ### Not covered
                        Bullets: what these tests do not check, and why. "None" if complete.
                        """)
                .build();

        LlmClient.Response response;
        try {
            response = llm.complete(LlmClient.Request.of(
                    LlmClient.Tier.DEEP, SYSTEM, prompt, 6000));
        } catch (LlmClient.LlmException e) {
            throw new AgentException("test could not author tests: " + e.getMessage(), e);
        }

        List<Artifact> artifacts = new ArrayList<>();
        for (var file : parse(response.content())) {
            artifacts.add(Artifact.of(
                    state.nextId("art"), StageId.TEST, Artifact.Type.TEST_SOURCE,
                    file.path(), file.content()));
        }

        if (artifacts.isEmpty()) {
            throw new AgentException("test produced no parseable test file; the model returned: "
                    + firstLine(response.content()));
        }

        // The gaps the model admits to are more useful than the tests it wrote. A
        // stated gap is something the human can weigh at the release gate; an unstated
        // one is a coverage number that means nothing.
        for (String gap : extractSection(response.content(), "### Not covered")) {
            state.addOpenQuestion("Untested: " + gap);
        }

        state.addDecision(Decision.byAgent(
                state.nextId("dec"), StageId.TEST,
                "What would catch this change being wrong?",
                artifacts.size() + " test file(s): "
                        + artifacts.stream().map(Artifact::name).toList(),
                "Tests target the behaviour the plan specifies and its failure paths, "
                        + "rather than the shape of the implementation.",
                List.of(new Decision.Alternative(
                        "Assert on the implementation's internals",
                        "Passes until the code is refactored, then fails without anything "
                                + "being broken")),
                List.of(EvidenceRef.llmCall(response.callId()))));

        return Output.of(artifacts, inputs);
    }

    private record TestFile(String path, String covers, String content) {}

    private static List<TestFile> parse(String content) {
        List<TestFile> files = new ArrayList<>();
        String path = null;
        String covers = "";
        StringBuilder code = null;

        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("FILE:")) {
                path = line.substring(5).strip();
                covers = "";
                code = null;
            } else if (line.startsWith("COVERS:") && path != null) {
                covers = line.substring(7).strip();
            } else if (line.startsWith("```") && path != null && code == null) {
                code = new StringBuilder();
            } else if (line.startsWith("```") && code != null) {
                if (!code.isEmpty()) files.add(new TestFile(path, covers, code.toString()));
                path = null;
                code = null;
            } else if (code != null) {
                code.append(raw).append('\n');
            }
        }
        return files;
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
        return found;
    }

    private static String firstLine(String s) {
        return s == null || s.isBlank() ? "(empty)" : s.lines().findFirst().orElse("(empty)").strip();
    }
}