package com.jay.agentic.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A model stand-in that returns the same answer for the same question, always.
 *
 * <p>This is what makes the harness testable. A run driven by a live model
 * produces different text every time, which means an assertion on its behaviour
 * can only ever be "something came back" — and a test that asserts that a
 * non-deterministic system did something is not a test.
 *
 * <p>Responses are matched by substring against the prompt, in registration
 * order, first match wins. Deliberately crude: this is a test double, and a
 * clever matcher would become something that needs its own tests.
 *
 * <p>It also serves a second purpose that is not about testing at all. A run
 * against this client exercises every gate, policy, retry and approval path in
 * the harness at zero cost and zero latency. When the network is down or the
 * budget is spent, the harness still demonstrates.
 */
public final class DeterministicLlmClient implements LlmClient {

    /** Prompt substring to response. Ordered — earlier registrations win. */
    private final Map<String, Function<Request, String>> responders = new LinkedHashMap<>();
    private final String fallback;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();

    public DeterministicLlmClient(String fallback) {
        this.fallback = fallback;
    }

    public DeterministicLlmClient() {
        this("");
    }

    /** Registers a canned answer for any prompt containing {@code trigger}. */
    public DeterministicLlmClient when(String trigger, String response) {
        responders.put(trigger, req -> response);
        return this;
    }

    /** Registers a computed answer, for cases where the response depends on the prompt. */
    public DeterministicLlmClient when(String trigger, Function<Request, String> responder) {
        responders.put(trigger, responder);
        return this;
    }

    /**
     * Registers an answer that fails the first {@code n} times it is asked.
     *
     * <p>This is how the retry and staleness paths get tested without waiting for
     * a real model to have a bad day. Flakiness that can be summoned on demand is
     * the only kind you can write an assertion about.
     */
    public DeterministicLlmClient whenFailingFirst(String trigger, int n, String eventualResponse) {
        AtomicInteger attempts = new AtomicInteger();
        responders.put(trigger, req -> {
            if (attempts.getAndIncrement() < n) {
                throw new IllegalStateException("simulated model failure "
                        + attempts.get() + " of " + n);
            }
            return eventualResponse;
        });
        return this;
    }

    @Override
    public Response complete(Request request) throws LlmException {
        String prompt = request.messages().stream()
                .map(Message::content)
                .reduce("", (a, b) -> a + "\n" + b);
        String haystack = (request.system() == null ? "" : request.system()) + "\n" + prompt;

        String content = fallback;
        for (var entry : responders.entrySet()) {
            if (haystack.contains(entry.getKey())) {
                try {
                    content = entry.getValue().apply(request);
                } catch (IllegalStateException e) {
                    throw new LlmException(e.getMessage(), e);
                }
                break;
            }
        }

        int in = estimateTokens(haystack);
        int out = estimateTokens(content);
        calls.incrementAndGet();
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);

        return new Response(content, "mock-" + calls.get(), request.tier(), in, out, 0L, false);
    }

    @Override
    public Usage usage() {
        // Cost is zero, and reporting it as zero is the honest answer rather than
        // a simulated price. A run against this client did not cost anything.
        return new Usage(calls.get(), 0, inputTokens.get(), outputTokens.get(), 0.0);
    }

    /** Rough, and only used so the mock's usage numbers are not all zero. */
    private static int estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }

    /** The prompts this client knows about — useful when a test fails on the fallback. */
    public List<String> registeredTriggers() {
        return List.copyOf(responders.keySet());
    }
}