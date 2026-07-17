package com.jay.agentic.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jay.agentic.state.StageId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Append-only execution trace, written as JSONL.
 *
 * <p>JSONL rather than a single JSON document because the trace must survive a
 * crash. A partially written array is unparseable; a partially written JSONL file
 * loses its last line and nothing else. The run's state file can be rewritten
 * atomically because it is small — a trace cannot, so it is designed to be
 * append-only and tolerant of truncation instead.
 *
 * <p>Spans are also held in memory so the metrics summary does not have to read
 * back what it just wrote.
 */
public final class Tracer {

    /** One recorded event. Flat by design — a nested span tree is harder to grep. */
    public record Span(
            String spanId,
            StageId stage,
            Kind kind,
            String name,
            /** OK, FAILED, REFUSED. */
            String outcome,
            /** Why, when the outcome is not OK. */
            String detail,
            long durationMillis,
            /** Tokens for LLM spans; zero otherwise. */
            int inputTokens,
            int outputTokens,
            boolean cached,
            Instant at
    ) {
        public enum Kind { STAGE, LLM_CALL, TOOL_CALL, GATE, POLICY, APPROVAL }
    }

    private final Path traceFile;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentLinkedQueue<Span> spans = new ConcurrentLinkedQueue<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public Tracer(Path runDir) {
        this.traceFile = runDir.resolve("trace.jsonl");
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create run directory", e);
        }
    }

    public void record(StageId stage, Span.Kind kind, String name, String outcome,
                       String detail, long durationMillis) {
        record(new Span("sp-" + sequence.incrementAndGet(), stage, kind, name,
                outcome, detail, durationMillis, 0, 0, false, Instant.now()));
    }

    public void recordLlm(StageId stage, String callId, int in, int out,
                          long durationMillis, boolean cached) {
        record(new Span("sp-" + sequence.incrementAndGet(), stage, Span.Kind.LLM_CALL,
                callId, "OK", null, durationMillis, in, out, cached, Instant.now()));
    }

    /** Appends to disk immediately. A trace written at the end is a trace lost on a crash. */
    public void record(Span span) {
        spans.add(span);
        try {
            Files.writeString(traceFile, mapper.writeValueAsString(span) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A failed trace write must not fail the run. Observability is not the work.
            System.err.println("trace write failed: " + e.getMessage());
        }
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    /**
     * The reliability metrics the assignment asks for, computed from the trace.
     *
     * <p>Only stages that were <em>attempted</em> count towards the success rate.
     * BLOCKED and SKIPPED are excluded, and the distinction is not pedantry: a run
     * where IMPLEMENT failed and blocked six downstream stages would otherwise report
     * seven failures and a 15% success rate, when what happened was one failure. A
     * metric that multiplies a single fault by its blast radius tells you about the
     * shape of the graph rather than about the reliability of the system.
     *
     * <p>Retries are counted as attempts beyond the first per stage, so a stage that
     * passed on its third try contributes two.
     */
    public Metrics metrics() {
        List<Span> all = spans();
        List<Span> stages = all.stream().filter(s -> s.kind() == Span.Kind.STAGE).toList();

        long ok = stages.stream().filter(s -> "OK".equals(s.outcome())).count();
        long failed = stages.stream().filter(s -> "FAILED".equals(s.outcome())).count();
        long blocked = stages.stream().filter(s -> "BLOCKED".equals(s.outcome())).count();
        long skipped = stages.stream().filter(s -> "SKIPPED".equals(s.outcome())).count();
        long refusals = all.stream().filter(s -> "REFUSED".equals(s.outcome())).count();

        long attempted = ok + failed;
        long distinctAttempted = stages.stream()
                .filter(s -> "OK".equals(s.outcome()) || "FAILED".equals(s.outcome()))
                .map(Span::stage).distinct().count();
        long retries = Math.max(0, attempted - distinctAttempted);

        // Both come from stage spans only. Summing every span would double count —
        // a stage span already contains the LLM spans that happened inside it — and
        // taking the maximum across every span picks up the APPROVAL span, which is
        // how long a human took to read. That produced a "slowest stage" longer than
        // the total, which is the sort of number that makes a reader stop trusting
        // the rest of them. Human thinking time is real and worth recording; it is
        // not a stage.
        long totalMs = stages.stream().mapToLong(Span::durationMillis).sum();
        long slowest = stages.stream().mapToLong(Span::durationMillis).max().orElse(0);
        long humanWaitMs = all.stream()
                .filter(sp -> sp.kind() == Span.Kind.APPROVAL)
                .mapToLong(Span::durationMillis).sum();

        double successRate = attempted == 0 ? 0 : (double) ok / attempted;

        return new Metrics(attempted, ok, failed, blocked, skipped, retries, refusals,
                successRate, totalMs, slowest, humanWaitMs);
    }

    public record Metrics(
            long attempts, long passed, long failed, long blocked, long skipped,
            long retries, long policyRefusals,
            double successRate, long totalMillis, long slowestStageMillis, long humanWaitMillis
    ) {
        public String report() {
            return String.format(
                    "%d attempt(s): %d passed, %d failed, %d retried; "
                            + "%d blocked, %d skipped, %d policy refusal(s). "
                            + "Success rate %.0f%% of attempted. "
                            + "MTTR n/a (no automatic recovery). "
                            + "%.1fs machine time, slowest stage %.1fs, %.1fs waiting on a human.",
                    attempts, passed, failed, retries, blocked, skipped, policyRefusals,
                    successRate * 100, totalMillis / 1000.0,
                    slowestStageMillis / 1000.0, humanWaitMillis / 1000.0);
        }
    }

    public Path traceFile() {
        return traceFile;
    }
}