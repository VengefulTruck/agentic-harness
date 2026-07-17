package com.jay.agentic.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetedLlmClientTest {

    /** A client that reports a fixed cost per call, so the budget has something to hit. */
    private static final class PricedClient implements LlmClient {
        private final double costPerCall;
        final AtomicInteger calls = new AtomicInteger();

        PricedClient(double costPerCall) {
            this.costPerCall = costPerCall;
        }

        @Override
        public Response complete(Request request) {
            int n = calls.incrementAndGet();
            return new Response("answer " + n, "call-" + n, request.tier(), 100, 50, 10L, false);
        }

        @Override
        public Usage usage() {
            return new Usage(calls.get(), 0, calls.get() * 100, calls.get() * 50,
                    calls.get() * costPerCall);
        }
    }

    private static LlmClient.Request ask(String prompt) {
        return LlmClient.Request.of(LlmClient.Tier.FAST, "you are a planner", prompt, 100);
    }

    @Test
    @DisplayName("an identical request is served from cache without reaching the model")
    void identicalRequestIsCached() throws Exception {
        PricedClient inner = new PricedClient(0.01);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        LlmClient.Response first = client.complete(ask("decompose this requirement"));
        LlmClient.Response second = client.complete(ask("decompose this requirement"));

        assertThat(inner.calls.get()).isEqualTo(1);
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(client.usage().cachedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a request differing by one character is a different question")
    void nearMissIsNotCached() throws Exception {
        PricedClient inner = new PricedClient(0.01);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        client.complete(ask("decompose this requirement"));
        client.complete(ask("decompose this requirement."));

        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the same prompt at a different tier is a different question")
    void tierIsPartOfTheCacheKey() throws Exception {
        PricedClient inner = new PricedClient(0.01);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        client.complete(LlmClient.Request.of(LlmClient.Tier.FAST, "sys", "same prompt", 100));
        client.complete(LlmClient.Request.of(LlmClient.Tier.DEEP, "sys", "same prompt", 100));

        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the budget stops the run before the call that would exceed it")
    void budgetStopsBeforeSpending() throws Exception {
        PricedClient inner = new PricedClient(0.40);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        client.complete(ask("first"));   // $0.40
        client.complete(ask("second"));  // $0.80
        client.complete(ask("third"));   // $1.20 — allowed: check happens before, at $0.80

        assertThatThrownBy(() -> client.complete(ask("fourth")))
                .isInstanceOf(BudgetedLlmClient.BudgetExceededException.class)
                .hasMessageContaining("budget of $1.00 exhausted");

        // The refused call never reached the model.
        assertThat(inner.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("a cache hit is free — it does not count against the budget")
    void cacheHitsBypassTheBudgetCheck() throws Exception {
        PricedClient inner = new PricedClient(0.60);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        client.complete(ask("expensive question"));   // $0.60

        // Over budget for anything new...
        client.complete(ask("another expensive one")); // $1.20
        assertThatThrownBy(() -> client.complete(ask("a third")))
                .isInstanceOf(BudgetedLlmClient.BudgetExceededException.class);

        // ...but a repeat costs nothing, so it is still served.
        LlmClient.Response repeat = client.complete(ask("expensive question"));
        assertThat(repeat.cached()).isTrue();
        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("remaining budget is reported, and never goes negative")
    void reportsRemainingBudget() throws Exception {
        PricedClient inner = new PricedClient(0.75);
        BudgetedLlmClient client = new BudgetedLlmClient(inner, 1.00);

        assertThat(client.remainingUsd()).isEqualTo(1.00);

        client.complete(ask("one"));
        assertThat(client.remainingUsd()).isEqualTo(0.25);

        client.complete(ask("two"));   // now $1.50 spent against a $1.00 budget
        assertThat(client.remainingUsd()).isZero();
    }

    @Test
    @DisplayName("the deterministic client reports zero cost, not a simulated price")
    void mockRunsAreFree() throws Exception {
        DeterministicLlmClient mock = new DeterministicLlmClient()
                .when("decompose", "1. read the controller\n2. add the interceptor");
        BudgetedLlmClient client = new BudgetedLlmClient(mock, 0.01);

        LlmClient.Response r = client.complete(ask("decompose this requirement"));

        assertThat(r.content()).contains("interceptor");
        assertThat(client.usage().estimatedCostUsd()).isZero();
        assertThat(client.remainingUsd()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("a flaky model can be summoned on demand — the retry path is testable")
    void deterministicClientCanSimulateFailure() {
        DeterministicLlmClient mock = new DeterministicLlmClient()
                .whenFailingFirst("generate the patch", 2, "public class RateLimiter {}");

        assertThatThrownBy(() -> mock.complete(ask("generate the patch")))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("simulated model failure 1 of 2");

        assertThatThrownBy(() -> mock.complete(ask("generate the patch")))
                .isInstanceOf(LlmClient.LlmException.class);

        // Third time succeeds — exactly the shape the orchestrator's maxAttempts(3) handles.
        assertThat(catchContent(mock)).contains("RateLimiter");
    }

    private static String catchContent(LlmClient c) {
        try {
            return c.complete(ask("generate the patch")).content();
        } catch (LlmClient.LlmException e) {
            throw new AssertionError("expected success on third attempt", e);
        }
    }
}