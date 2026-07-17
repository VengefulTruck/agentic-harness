package com.jay.agentic.tools;

import com.jay.agentic.state.EvidenceRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Records a proposed change to the target repository.
 *
 * <p>Named "propose" rather than "write" because that is precisely what it does.
 * It writes to the harness's own run directory — never to the target repo. The
 * change becomes a file on disk that a human can read, diff, and apply by hand;
 * the harness itself has no code path that touches the target working tree.
 *
 * <p>This is the recommendation-versus-execution split, made structural. The
 * alternative — a write tool guarded by an approval check — puts the safety in a
 * conditional that a refactor could remove, a policy misconfiguration could skip,
 * or a determined prompt could route around. Here there is nothing to bypass: the
 * capability to modify the target repository does not exist anywhere in this
 * codebase. An attacker who fully controlled the model could not use this tool to
 * change the shortener, because it cannot.
 *
 * <p>It is still marked {@link #isMutating()} true, and that is not a contradiction.
 * It writes to disk and it produces the artifact a human will act on, so it belongs
 * on the governed side of the line: policy still refuses to let it run before the
 * plan is approved.
 */
public final class PatchProposalTool implements Tool {

    private final Path proposalsDir;

    public PatchProposalTool(Path proposalsDir) {
        this.proposalsDir = proposalsDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "propose_patch";
    }

    @Override
    public String description() {
        return "Record a proposed file change for human review. Args: path (repo-relative target), "
                + "content (the full proposed file), rationale. Writes to the run's proposals "
                + "directory; never modifies the target repository.";
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public Result invoke(Map<String, String> args) {
        String targetPath = args.get("path");
        String content = args.get("content");
        String rationale = args.getOrDefault("rationale", "");

        if (targetPath == null || targetPath.isBlank()) {
            return Result.failed("propose_patch requires a 'path' argument");
        }
        if (content == null || content.isBlank()) {
            // An empty patch is the classic silent failure: the stage "succeeded",
            // produced nothing, and the exit gate would be the only thing to notice.
            return Result.failed("propose_patch requires non-blank 'content'");
        }

        // The proposal filename is derived from the target path, flattened. The target
        // path is never resolved against the repo — it is recorded, not followed.
        String safeName = targetPath.replaceAll("[^A-Za-z0-9._-]", "_");

        try {
            Files.createDirectories(proposalsDir);
            Path proposal = proposalsDir.resolve(safeName);
            Path note = proposalsDir.resolve(safeName + ".rationale.txt");

            Files.writeString(proposal, content);
            Files.writeString(note, "Target: " + targetPath + "\n\nRationale:\n" + rationale + "\n");

            return Result.ok("proposed change to " + targetPath
                            + " (" + content.lines().count() + " lines) — written to "
                            + proposalsDir.relativize(proposal) + " for review",
                    EvidenceRef.toolCall("propose_patch:" + targetPath));
        } catch (IOException e) {
            return Result.failed("could not record proposal for " + targetPath + ": " + e.getMessage());
        }
    }
}