package com.jay.agentic.cli;

import com.jay.agentic.orchestration.ApprovalPort;
import com.jay.agentic.state.Assumption;
import com.jay.agentic.state.TaskState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the human at the terminal.
 *
 * <p>Everything the decision rests on is printed before the prompt — the plan,
 * the patch, the assumptions, the reasoning, the consequence of proceeding. A
 * checkpoint that asks "approve? [y/n]" without showing the work is not
 * oversight, it is a keystroke, and a keystroke is what an automated system
 * gets when it makes approving easier than reading.
 *
 * <p>High-risk assumptions are printed last and marked, immediately above the
 * prompt, because that is the position the eye actually reaches. Burying them
 * in the middle of a plan is technically disclosure and practically concealment.
 *
 * <p>There is no default. An empty line re-prompts rather than being read as
 * consent. A default of "yes" is how approval gates become formalities; a
 * default of "no" would be safer but trains people to hammer the key. Requiring
 * a real answer is the only version that keeps the human in the loop.
 */
public final class ConsoleApprovalPort implements ApprovalPort {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    @Override
    public Response requestApproval(Request request, TaskState state) {
        print(request, state);

        Map<String, Resolution> resolutions = resolveAssumptions(state);

        while (true) {
            System.out.print("\n  [a]pprove  [r]eject  [d]efer  > ");
            System.out.flush();

            String answer;
            try {
                answer = in.readLine();
            } catch (IOException e) {
                // Cannot read the terminal — fail closed. An unanswerable question is
                // not an answered one.
                return Response.reject("could not read from the terminal: " + e.getMessage());
            }

            if (answer == null) {
                // EOF: piped input, or Ctrl-D. Not consent.
                return Response.reject("input stream closed without a decision");
            }

            switch (answer.strip().toLowerCase()) {
                case "a", "approve" -> {
                    return Response.approve(readReason("Reason for approving"), resolutions);
                }
                case "r", "reject" -> {
                    return Response.reject(readReason("Reason for rejecting"));
                }
                case "d", "defer" -> {
                    System.out.println("\n  Deferred. The run state is saved; resume with:");
                    System.out.println("    mvn -q exec:java -Dexec.args=\"--resume "
                            + state.runId() + "\"\n");
                    return Response.defer();
                }
                default -> System.out.println("  Please answer a, r or d.");
            }
        }
    }

    /**
     * Asks the human to settle each high-risk guess before the approve prompt.
     *
     * <p>Only HIGH assumptions are put to the human, and that restraint is the point.
     * Asking about all of them would mean asking about a dozen, most of them trivial,
     * and a prompt that asks a dozen questions gets a dozen reflexive yeses. The risk
     * grade exists to decide what is worth interrupting someone for.
     *
     * <p>Skipping is allowed and is not a failure: an unanswered HIGH assumption stays
     * OPEN and the release gate refuses later. The human is not forced to invent an
     * answer at the point of being asked — they are prevented from shipping without
     * one.
     */
    private Map<String, Resolution> resolveAssumptions(TaskState state) {
        List<Assumption> open = state.assumptions().stream()
                .filter(a -> a.status() == Assumption.Status.OPEN
                        && a.risk() == Assumption.Risk.HIGH)
                .toList();

        if (open.isEmpty()) return Map.of();

        System.out.println("\n" + "=".repeat(78));
        System.out.println("  The harness had to guess at " + open.size()
                + " thing(s) that matter. Settle them before deciding.");
        System.out.println("=".repeat(78));

        Map<String, Resolution> answers = new LinkedHashMap<>();

        for (Assumption a : open) {
            System.out.println("\n  " + a.id() + ": " + a.statement());
            System.out.println("  Assumed because: " + a.reason());
            System.out.println("  Made by: " + a.madeBy());

            while (true) {
                System.out.print("\n  [y]es that is right  [n]o it is wrong  [s]kip  > ");
                System.out.flush();

                String answer;
                try {
                    answer = in.readLine();
                } catch (IOException e) {
                    return answers;
                }
                if (answer == null) return answers;

                switch (answer.strip().toLowerCase()) {
                    case "y", "yes" -> {
                        answers.put(a.id(), Resolution.confirm(
                                readReason("How do you know")));
                    }
                    case "n", "no" -> {
                        answers.put(a.id(), Resolution.reject(
                                readReason("What is actually true")));
                    }
                    case "s", "skip" -> {
                        System.out.println("  Left open. The release gate will refuse "
                                + "while it stands.");
                    }
                    default -> {
                        System.out.println("  Please answer y, n or s.");
                        continue;
                    }
                }
                break;
            }
        }
        return answers;
    }

    /**
     * The reason is not optional.
     *
     * <p>It goes into the decision ledger and it is the only record of why a human
     * chose what they chose. "Looks fine" is a poor reason and still better than
     * an empty field, which is indistinguishable from nobody having thought.
     */
    private String readReason(String prompt) {
        System.out.print("  " + prompt + ": ");
        System.out.flush();
        try {
            String reason = in.readLine();
            return (reason == null || reason.isBlank()) ? "(no reason given)" : reason.strip();
        } catch (IOException e) {
            return "(reason could not be read)";
        }
    }

    private void print(Request request, TaskState state) {
        System.out.println("\n" + "=".repeat(78));
        System.out.println("  HUMAN CHECKPOINT — " + request.stage());
        System.out.println("=".repeat(78));
        System.out.println("\n  " + request.question());

        System.out.println("\n  Requirement: " + state.requirement());

        for (String block : request.context()) {
            System.out.println("\n" + "-".repeat(78));
            System.out.println(indent(block));
        }

        List<Assumption> blockers = state.assumptions().stream()
                .filter(a -> a.status() == Assumption.Status.OPEN
                        && a.risk() == Assumption.Risk.HIGH)
                .toList();

        if (!blockers.isEmpty()) {
            System.out.println("\n" + "!".repeat(78));
            System.out.println("  " + blockers.size() + " HIGH-RISK ASSUMPTION(S) — read before deciding");
            System.out.println("!".repeat(78));
            for (Assumption a : blockers) {
                System.out.println("\n  * " + a.statement());
                System.out.println("    because: " + a.reason());
            }
        }

        System.out.println("\n" + "-".repeat(78));
        System.out.println("  If you approve: " + request.consequence());
        System.out.println("-".repeat(78));
    }

    private static String indent(String block) {
        return block.lines().map(l -> "  " + l).reduce("", (a, b) -> a + b + "\n");
    }
}