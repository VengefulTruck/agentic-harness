package com.jay.agentic.tools;

import com.jay.agentic.state.EvidenceRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads a file from the target repository.
 *
 * <p>Read-only, but not therefore harmless. The path arrives from a model, which
 * means it is untrusted input in the ordinary security sense — no different from
 * a path arriving in an HTTP request. A model asked to explore a codebase can be
 * talked into asking for {@code ../../.ssh/id_rsa}, and a tool that resolves
 * whatever it is handed will oblige.
 *
 * <p>The containment is structural: resolve the path, canonicalise it, then
 * verify the result is still inside the repository root. Canonicalisation before
 * the check is what matters — {@code repo/../../etc/passwd} only reveals itself
 * as an escape once the {@code ..} segments are collapsed. Checking the raw
 * string for "{@code ..}" is the version of this that looks right and is
 * trivially defeated by symlinks.
 */
public final class FileReadTool implements Tool {

    /** Files a source-reading agent has no business opening, even inside the repo. */
    private static final String[] DENIED_NAMES = {
            ".env", ".git", "id_rsa", "id_ed25519", ".pem", ".p12", ".keystore", "credentials"
    };

    private static final long MAX_BYTES = 512_000;

    private final Path repoRoot;

    public FileReadTool(Path repoRoot) {
        this.repoRoot = canonical(repoRoot);
    }

    /**
     * Resolves symlinks in the root, not just the requested path.
     *
     * <p>Both sides of the containment check must be canonicalised the same way or
     * the comparison is meaningless. On macOS {@code /var} is a symlink to
     * {@code /private/var}, so a root of {@code /var/folders/x} and a resolved path
     * of {@code /private/var/folders/x/File.java} describe the same file and fail
     * {@code startsWith}. That is a false negative — the tool refuses a legitimate
     * read — and the mirror-image mistake, canonicalising only the root, would be a
     * false positive, which is the one that matters.
     *
     * <p>Falls back to normalisation when the root does not exist yet, which is not
     * a security regression: a non-existent root cannot contain anything to leak.
     */
    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a file from the target repository. Args: path (repo-relative).";
    }

    @Override
    public boolean isMutating() {
        return false;
    }

    @Override
    public Result invoke(Map<String, String> args) {
        String rel = args.get("path");
        if (rel == null || rel.isBlank()) {
            return Result.failed("read_file requires a 'path' argument");
        }

        Path resolved;
        try {
            resolved = repoRoot.resolve(rel).toRealPath();
        } catch (IOException e) {
            // Non-existent is a fact the agent must reason about, not a fault.
            return Result.failed("no such file: " + rel);
        }

        if (!resolved.startsWith(repoRoot)) {
            return Result.failed("path escapes the repository root: " + rel);
        }

        String lower = resolved.toString().toLowerCase();
        for (String denied : DENIED_NAMES) {
            if (lower.contains(denied)) {
                return Result.failed("access to '" + rel + "' is denied by policy");
            }
        }

        try {
            if (Files.size(resolved) > MAX_BYTES) {
                return Result.failed("file exceeds " + MAX_BYTES + " bytes: " + rel);
            }
            String content = Files.readString(resolved);
            return Result.ok(content, EvidenceRef.sourceFile(rel, null));
        } catch (IOException e) {
            return Result.failed("could not read " + rel + ": " + e.getMessage());
        }
    }
}