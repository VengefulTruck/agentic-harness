package com.jay.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Calls the Anthropic Messages API.
 *
 * <p>Written directly against {@code java.net.http.HttpClient} rather than using
 * a vendor SDK. The API is one POST with a JSON body; a hand-rolled client is
 * about eighty lines that are fully understood and fully owned, against a
 * dependency whose retry, timeout and error semantics would have to be learned
 * before they could be defended. Under a rubric that weights ownership, the
 * eighty lines are the cheaper option.
 *
 * <p>The API key is read from the environment and never held anywhere else. It
 * is not a constructor parameter, not a config field, and not written to any
 * log — a key that only exists in {@code System.getenv} cannot be serialised
 * into a run file by accident.
 */
public final class AnthropicLlmClient implements LlmClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /**
     * Model strings live here and nowhere else.
     *
     * <p>Agents ask for a {@link Tier}, not for a model. When a new model ships,
     * this constant changes and no agent knows. The indirection costs one enum
     * and buys the ability to re-tier the whole pipeline from one file.
     */
    private static final String FAST_MODEL = "claude-haiku-4-5-20251001";
    private static final String DEEP_MODEL = "claude-sonnet-4-5-20250929";

    /** USD per million tokens. Approximate — used for the budget cap, not for billing. */
    private static final double FAST_INPUT_PER_MTOK = 1.00;
    private static final double FAST_OUTPUT_PER_MTOK = 5.00;
    private static final double DEEP_INPUT_PER_MTOK = 3.00;
    private static final double DEEP_OUTPUT_PER_MTOK = 15.00;

    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private final AtomicLong costMicros = new AtomicLong();

    public AnthropicLlmClient() {
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Export it, or run with the deterministic client.");
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Response complete(Request request) throws LlmException {
        String model = request.tier() == Tier.FAST ? FAST_MODEL : DEEP_MODEL;
        String body = buildBody(request, model);
        long started = System.currentTimeMillis();

        HttpResponse<String> response = send(body);
        long latency = System.currentTimeMillis() - started;

        return parse(response, request.tier(), latency);
    }

    // ---- request ----

    private String buildBody(Request request, String model) throws LlmException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", request.maxTokens());

        if (request.system() != null && !request.system().isBlank()) {
            root.put("system", request.system());
        }

        ArrayNode messages = root.putArray("messages");
        for (Message m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role() == Message.Role.USER ? "user" : "assistant");
            node.put("content", m.content());
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new LlmException("could not serialise request", e);
        }
    }

    /**
     * Sends with bounded retries on transient failures only.
     *
     * <p>The distinction matters and is easy to get wrong. A 429 or a 5xx is the
     * server saying "try later" — retrying is correct. A 400 is the server saying
     * "this request is malformed" — retrying sends the identical malformed request
     * again, wastes the round trip, and delays the error the caller needs to see.
     * A 401 is worse: retrying a bad key three times is how accounts get locked.
     */
    private HttpResponse<String> send(String body) throws LlmException {
        LlmException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT))
                        .timeout(Duration.ofSeconds(120))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", API_VERSION)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = res.statusCode();

                if (status == 200) return res;

                if (status == 429 || status >= 500) {
                    last = new LlmException("transient failure: HTTP " + status);
                    backoff(attempt);
                    continue;
                }

                // Permanent. Fail now rather than three times.
                throw new LlmException("HTTP " + status + ": " + summarise(res.body()));

            } catch (IOException e) {
                last = new LlmException("network failure: " + e.getMessage(), e);
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted while calling the model", e);
            }
        }
        throw last != null ? last : new LlmException("exhausted " + MAX_ATTEMPTS + " attempts");
    }

    /** Exponential backoff. Crude but correct: 1s, 2s, 4s. */
    private void backoff(int attempt) throws LlmException {
        try {
            Thread.sleep(1000L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted during backoff", e);
        }
    }

    // ---- response ----

    private Response parse(HttpResponse<String> res, Tier tier, long latency) throws LlmException {
        try {
            JsonNode root = mapper.readTree(res.body());

            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }

            JsonNode usage = root.path("usage");
            int in = usage.path("input_tokens").asInt();
            int out = usage.path("output_tokens").asInt();
            String id = root.path("id").asText("unknown");

            record(tier, in, out);

            return new Response(text.toString(), id, tier, in, out, latency, false);

        } catch (IOException e) {
            throw new LlmException("could not parse model response", e);
        }
    }

    private void record(Tier tier, int in, int out) {
        calls.incrementAndGet();
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);

        double inRate = tier == Tier.FAST ? FAST_INPUT_PER_MTOK : DEEP_INPUT_PER_MTOK;
        double outRate = tier == Tier.FAST ? FAST_OUTPUT_PER_MTOK : DEEP_OUTPUT_PER_MTOK;
        double cost = (in / 1_000_000.0 * inRate) + (out / 1_000_000.0 * outRate);
        costMicros.addAndGet(Math.round(cost * 1_000_000));
    }

    @Override
    public Usage usage() {
        return new Usage(calls.get(), 0, inputTokens.get(), outputTokens.get(),
                costMicros.get() / 1_000_000.0);
    }

    /** Error bodies can contain the request echoed back. Truncate rather than log it all. */
    private static String summarise(String body) {
        if (body == null) return "(no body)";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}