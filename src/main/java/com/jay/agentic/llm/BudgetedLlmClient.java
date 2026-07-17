package com.jay.agentic.llm;

import com.jay.agentic.state.Artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps a client with a cache and a spending limit.
 *
 * <p>A decorator rather than logic inside {@link AnthropicLlmClient}, because
 * caching and budgeting are policy and the HTTP call is mechanism. Mixing them
 * would mean the deterministic client either duplicates the budget logic or
 * cannot be budget-tested at all. Here, the same wrapper governs both — so the
 * budget can be tested at zero cost against the mock, and the thing tested is
 * the thing that runs.
 *
 * <p>The cache key is the full request, hashed. That is deliberately strict: a
 * request differing by one character is a different question and gets a fresh
 * answer. Fuzzy matching would be cheaper and occasionally wrong, and a cache
 * that occasionally returns the answer to a similar-but-different question is
 * a bug generator, not an optimisation.
 */
public final class BudgetedLlmClient implements LlmClient {

    /** Raised when a run has spent its allowance. Not retryable. */
    public static final class BudgetExceededException extends LlmException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    private final LlmClient delegate;
    private final double budgetUsd;
    private final Map<String, Response> cache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger();

    public BudgetedLlmClient(LlmClient delegate, double budgetUsd) {
        this.delegate = delegate;
        this.budgetUsd = budgetUsd;
    }

    @Override
    public Response complete(Request request) throws LlmException {
        String key = cacheKey(request);

        Response hit = cache.get(key);
        if (hit != null) {
            cacheHits.incrementAndGet();
            // Re-stamped as cached so the tracer can tell a hit from a call.
            return new Response(hit.content(), hit.callId(), hit.tier(),
                    hit.inputTokens(), hit.outputTokens(), 0L, true);
        }

        // Checked before the call, not after. A budget enforced after spending is
        // a report, not a limit.
        Usage current = delegate.usage();
        if (current.estimatedCostUsd() >= budgetUsd) {
            throw new BudgetExceededException(String.format(
                    "run budget of $%.2f exhausted (spent $%.4f over %d calls); stopping safely",
                    budgetUsd, current.estimatedCostUsd(), current.calls()));
        }

        Response response = delegate.complete(request);
        cache.put(key, response);
        return response;
    }

    /**
     * SHA-256 of tier, system prompt, and every message.
     *
     * <p>Reuses {@link Artifact#sha256} rather than growing a second hashing
     * helper — one hash function in the codebase means one thing to reason about.
     */
    private static String cacheKey(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.tier()).append('\u0000');
        sb.append(request.system() == null ? "" : request.system()).append('\u0000');
        sb.append(request.maxTokens()).append('\u0000');
        for (Message m : request.messages()) {
            sb.append(m.role()).append(':').append(m.content()).append('\u0000');
        }
        return Artifact.sha256(sb.toString());
    }

    @Override
    public Usage usage() {
        Usage u = delegate.usage();
        return new Usage(u.calls(), cacheHits.get(), u.inputTokens(), u.outputTokens(),
                u.estimatedCostUsd());
    }

    public double budgetUsd() {
        return budgetUsd;
    }

    public double remainingUsd() {
        return Math.max(0, budgetUsd - delegate.usage().estimatedCostUsd());
    }

    /** A one-line cost report for the run summary. */
    public String report() {
        Usage u = usage();
        return String.format(
                "%d model calls (%d served from cache), %d in / %d out tokens, ~$%.4f of $%.2f budget",
                u.calls(), u.cachedCalls(), u.inputTokens(), u.outputTokens(),
                u.estimatedCostUsd(), budgetUsd);
    }
}