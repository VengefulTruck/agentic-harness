package com.jay.agentic.tools;

import com.jay.agentic.state.EvidenceRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Searches the target repository for a pattern.
 *
 * <p>This is the tool that makes brownfield reasoning possible. Without it, an
 * agent asked to change an existing codebase has two options: read every file
 * into the prompt, which is unaffordable, or guess at file names, which is worse.
 * Search is what lets the harness find the three files that matter and read only
 * those — the difference between an impact analysis and a hallucination.
 *
 * <p>The pattern also arrives from a model, so it is untrusted in a second sense:
 * a regex can be a denial-of-service. {@code (a+)+b} against a long line
 * backtracks exponentially and the stage hangs with no error to catch. The
 * defences here are a length cap on the pattern and a match ceiling; a fuller
 * answer would run the match under a timeout, which is noted as a limitation
 * rather than pretended away.
 */
public final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 100;
    private static final int MAX_PATTERN_LENGTH = 200;
    private static final int MAX_LINE_LENGTH = 500;

    /** Directories that are never worth searching and are expensive to walk. */
    private static final List<String> SKIP_DIRS = List.of(
            "/target/", "/.git/", "/node_modules/", "/build/", "/.idea/", "/out/");

    private final Path repoRoot;

    public GrepTool(Path repoRoot) {
        this.repoRoot = canonical(repoRoot);
    }

    /** Canonicalised for the same reason as {@code FileReadTool}: a symlinked root
     *  otherwise makes {@code relativize} produce paths the read tool then rejects. */
    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @Override
    public String name() {
        return "search_repo";
    }

    @Override
    public String description() {
        return "Search the target repository for a regex. Args: pattern, optional glob (e.g. *.java). "
                + "Returns matching file paths with line numbers.";
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public Result invoke(Map<String, String> args) {
        String patternArg = args.get("pattern");
        if (patternArg == null || patternArg.isBlank()) {
            return Result.failed("search_repo requires a 'pattern' argument");
        }
        if (patternArg.length() > MAX_PATTERN_LENGTH) {
            return Result.failed("pattern exceeds " + MAX_PATTERN_LENGTH + " characters");
        }

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternArg);
        } catch (PatternSyntaxException e) {
            // A malformed regex is the agent's mistake to correct, not a harness fault.
            return Result.failed("invalid regex: " + e.getDescription());
        }

        String glob = args.getOrDefault("glob", "*");
        List<String> hits = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(this::notInSkippedDir)
                    .filter(p -> matchesGlob(p, glob))
                    .forEach(p -> collectMatches(p, pattern, hits));
        } catch (IOException e) {
            return Result.failed("could not walk repository: " + e.getMessage());
        } catch (UncheckedIOException e) {
            return Result.failed("could not read a file during search: " + e.getMessage());
        }

        if (hits.isEmpty()) {
            // Not a failure. "Nothing matches" is a finding — it may mean the agent's
            // assumption about the codebase was wrong, which it needs to know.
            return Result.ok("no matches for /" + patternArg + "/ in " + glob,
                    EvidenceRef.toolCall("search_repo:" + patternArg));
        }

        boolean truncated = hits.size() >= MAX_MATCHES;
        String output = String.join("\n", hits.subList(0, Math.min(hits.size(), MAX_MATCHES)))
                + (truncated ? "\n... truncated at " + MAX_MATCHES + " matches; narrow the pattern" : "");

        return Result.ok(output, EvidenceRef.toolCall("search_repo:" + patternArg));
    }

    private void collectMatches(Path file, Pattern pattern, List<String> hits) {
        if (hits.size() >= MAX_MATCHES) return;
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && hits.size() < MAX_MATCHES; i++) {
                String line = lines.get(i);
                if (pattern.matcher(line).find()) {
                    String trimmed = line.length() > MAX_LINE_LENGTH
                            ? line.substring(0, MAX_LINE_LENGTH) + "..." : line;
                    hits.add(repoRoot.relativize(file) + ":" + (i + 1) + ": " + trimmed.strip());
                }
            }
        } catch (IOException e) {
            // Binary files and unreadable files are skipped rather than failing the search.
        }
    }

    private boolean notInSkippedDir(Path p) {
        String s = p.toString().replace('\\', '/');
        return SKIP_DIRS.stream().noneMatch(s::contains);
    }

    private boolean matchesGlob(Path p, String glob) {
        return p.getFileSystem().getPathMatcher("glob:" + glob).matches(p.getFileName());
    }
}