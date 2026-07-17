package com.jay.agentic.orchestration;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.TaskState;

import java.util.List;
import java.util.Map;

/**
 * How the harness asks a human to decide.
 *
 * <p>An interface rather than a direct console read, for one reason that matters
 * more than testability: a blocking {@code Scanner.nextLine()} would make the
 * approval checkpoint a property of the process rather than of the run. The run
 * could not be saved, resumed, or approved by anyone who was not sitting at the
 * terminal that started it. Behind this interface, an approval can arrive from a
 * console now, or from a web request tomorrow, or from a resumed run file an hour
 * later — and the orchestrator does not change.
 *
 * <p>{@link Decision#DEFER} is what makes that concrete: the run writes its state
 * and exits rather than waiting, and the JVM is free to die.
 */
public interface ApprovalPort {

    enum Decision {
        /** Proceed with the proposed work. */
        APPROVE,
        /** Do not proceed. The run stops; nothing is applied. */
        REJECT,
        /** Do not decide now. The run parks and exits; state is preserved for resume. */
        DEFER
    }

    /** What the human is being asked, with everything needed to answer it. */
    record Request(
            StageId stage,
            String question,
            /** The reasoning, artifacts and assumptions the decision rests on. */
            List<String> context,
            /** What proceeding would cause. Stated plainly — this is the risk being accepted. */
            String consequence
    ) {
        public Request {
            context = List.copyOf(context);
        }
    }

    /**
     * The human's answer, with a reason recorded into the decision ledger.
     *
     * <p>{@code resolutions} is how a human answers the questions the harness could
     * not. Without it, a recorded assumption was permanent: {@code CLARIFY} could
     * grade a guess HIGH, and nothing in the system could ever un-grade it, so any
     * run that surfaced real ambiguity was dead on arrival at the release gate. The
     * harness had detection and no resolution — it could ask, but not listen.
     *
     * <p>Keyed by assumption id. A confirmed assumption stops blocking; a rejected
     * one is retained in the ledger as rejected, because "we guessed wrong and a
     * human said so" is the most useful line in an audit trail.
     */
    record Response(Decision decision, String reason, Map<String, Resolution> resolutions) {

        public Response {
            resolutions = Map.copyOf(resolutions);
        }

        public static Response approve(String reason) {
            return new Response(Decision.APPROVE, reason, Map.of());
        }

        public static Response approve(String reason, Map<String, Resolution> resolutions) {
            return new Response(Decision.APPROVE, reason, resolutions);
        }

        public static Response reject(String reason) {
            return new Response(Decision.REJECT, reason, Map.of());
        }

        public static Response defer() {
            return new Response(Decision.DEFER, "deferred", Map.of());
        }
    }

    /** A human's answer to one assumption. */
    record Resolution(boolean confirmed, String note) {

        public static Resolution confirm(String note) {
            return new Resolution(true, note);
        }

        public static Resolution reject(String note) {
            return new Resolution(false, note);
        }
    }

    Response requestApproval(Request request, TaskState state);

    /**
     * An approver that refuses everything.
     *
     * <p>The default for unattended runs, and deliberately not auto-approve. If a
     * checkpoint exists because an action is high-impact, then "nobody was there
     * to look" is a reason to stop, not a reason to proceed. Fail-closed.
     */
    static ApprovalPort denyAll() {
        return (request, state) ->
                Response.reject("no approver available; high-impact action refused by default");
    }
}