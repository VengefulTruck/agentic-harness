package com.jay.agentic.llm;

import java.util.List;

/**
 * The boundary between the harness and a language model.
 *
 * <p>Everything upstream of this interface — the graph, the gates, the policies,
 * the state model — is deterministic and testable without a network. That is not
 * an accident of layering; it is the reason the harness can be evaluated at all.
 * A system that reaches for a live model at every decision point cannot be unit
 * tested, cannot be run in CI, and cannot be demonstrated offline.
 *
 * <p>Two implementations exist. {@code AnthropicLlmClient} makes real calls and
 * is what runs in a demo. {@code DeterministicLlmClient} returns canned responses
 * and is what runs in the test suite. Neither is the "real" one — they are the
 * production path and the test path, and both are load-bearing.
 */
public interface LlmClient {

    /** Model tiers, named by role rather than by vendor model string. */
    enum Tier {
        /**
         * Cheap and fast. Classification, extraction, yes/no judgements.
         * Wrong answers here are cheap to detect and cheap to retry.
         */
        FAST,
        /**
         * Expensive and capable. Architecture, code generation, review.
         * Wrong answers here are expensive, so the cost is worth paying.
         */
        DEEP
    }

    /** One turn in a conversation. */
    record Message(Role role, String content) {
        public enum Role { USER, ASSISTANT }

        public static Message user(String content) {
            return new Message(Role.USER, content);
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content);
        }
    }

    /** What to ask, and how much it is allowed to cost. */
    record Request(
            Tier tier,
            /** Instructions that frame the task. Kept separate so it can be cached. */
            String system,
            List<Message> messages,
            int maxTokens
    ) {
        public Request {
            messages = List.copyOf(messages);
        }

        public static Request of(Tier tier, String system, String prompt, int maxTokens) {
            return new Request(tier, system, List.of(Message.user(prompt)), maxTokens);
        }
    }

    /**
     * The model's answer, with what it cost.
     *
     * <p>Token counts are returned rather than logged internally, because the
     * caller is what knows which stage spent them. A client that logged its own
     * usage would produce a total; returning them produces a breakdown, and a
     * breakdown is what tells you which stage is expensive.
     */
    record Response(
            String content,
            String callId,
            Tier tier,
            int inputTokens,
            int outputTokens,
            long latencyMillis,
            /** True when this was served from cache and cost nothing. */
            boolean cached
    ) {
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** Thrown when the model could not be reached or refused the request. */
    class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Response complete(Request request) throws LlmException;

    /** Cumulative usage for the run, for the budget check and the final summary. */
    Usage usage();

    /** Running totals. */
    record Usage(int calls, int cachedCalls, int inputTokens, int outputTokens, double estimatedCostUsd) {
        public static Usage zero() {
            return new Usage(0, 0, 0, 0, 0.0);
        }
    }
}