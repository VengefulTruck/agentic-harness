package com.jay.agentic.policy;

import com.jay.agentic.state.StageId;
import com.jay.agentic.state.StageStatus;

import java.util.List;
import java.util.Set;

/**
 * The rules this harness operates under.
 *
 * <p>Written as a small set of independent, named rules rather than a single
 * "isAllowed" method with branches. Each is separately testable, separately
 * explicable, and separately removable — and a reviewer can read this file and
 * know the whole authority model without reading anything else.
 */
public final class Policies {

    private Policies() {
    }

    /** The default set for a run against a real repository. */
    public static List<Policy> standard() {
        return List.of(
                noMutationBeforeApproval(),
                noSecretsInGeneratedContent(),
                writesConfinedToProposals(),
                noDependencyChanges()
        );
    }

    /**
     * Nothing that changes anything may run until a human has approved the plan.
     *
     * <p>Expressed against the {@code isMutating} flag rather than against a list
     * of tool names, so a tool added next month is governed the day it is written
     * without anyone remembering to update this rule. Enumerating tools here would
     * mean the policy silently stops covering the system as the system grows.
     */
    public static Policy noMutationBeforeApproval() {
        return new Policy() {
            @Override
            public String name() {
                return "no-mutation-before-approval";
            }

            @Override
            public String rationale() {
                return "A human must see the plan and its reasoning before the harness "
                        + "produces anything that will be acted on.";
            }

            @Override
            public Verdict check(Request r) {
                if (!r.tool().isMutating()) return Verdict.allow();

                var approval = r.state().stage(StageId.APPROVAL_GATE);
                if (approval != null && approval.status() == StageStatus.PASSED) {
                    return Verdict.allow();
                }
                return Verdict.deny(r.tool().name() + " is a mutating tool and the plan "
                        + "has not been approved (APPROVAL_GATE is "
                        + (approval == null ? "not in this run" : approval.status()) + ")");
            }
        };
    }

    /**
     * No generated content containing a credential may be recorded.
     *
     * <p>Checked before the write, not after. A scanner that runs on the finished
     * file has found the secret after it exists on disk — which is detection, and
     * the thing being defended against is a secret existing on disk at all.
     */
    public static Policy noSecretsInGeneratedContent() {
        return new Policy() {
            @Override
            public String name() {
                return "no-secrets-in-generated-content";
            }

            @Override
            public String rationale() {
                return "Generated code routinely inlines credentials. A credential that "
                        + "reaches a file reaches version control shortly afterwards.";
            }

            @Override
            public Verdict check(Request r) {
                String content = r.args().get("content");
                if (content == null) return Verdict.allow();

                List<SecretScanner.Finding> findings = SecretScanner.scan(content);
                if (findings.isEmpty()) return Verdict.allow();

                return Verdict.deny("generated content contains " + findings.size()
                        + " credential(s): " + findings
                        + ". Use an environment variable and reference it by name.");
            }
        };
    }

    /**
     * The only mutating tool permitted is the one that writes proposals.
     *
     * <p>Belt and braces: no tool exists in this codebase that writes to the target
     * repository, so this rule currently guards against nothing. It is here for the
     * version of this harness that someone extends in six months — the rule outlives
     * the current tool set, and the failure it prevents is a well-meaning addition
     * rather than an attack.
     */
    public static Policy writesConfinedToProposals() {
        return new Policy() {
            private final Set<String> permittedMutators = Set.of("propose_patch");

            @Override
            public String name() {
                return "writes-confined-to-proposals";
            }

            @Override
            public String rationale() {
                return "The harness proposes changes for human review. It does not apply them.";
            }

            @Override
            public Verdict check(Request r) {
                if (!r.tool().isMutating()) return Verdict.allow();
                if (permittedMutators.contains(r.tool().name())) return Verdict.allow();
                return Verdict.deny(r.tool().name() + " is a mutating tool that is not on the "
                        + "permitted list " + permittedMutators + ". The harness may only "
                        + "propose changes, never apply them.");
            }
        };
    }

    /**
     * Proposals may not alter build files.
     *
     * <p>A change to {@code pom.xml} is a change to what code runs on every
     * machine that builds the project, including CI. It is categorically different
     * from a change to a class — the blast radius is the supply chain, and the
     * review it needs is not the review a source diff gets.
     */
    public static Policy noDependencyChanges() {
        return new Policy() {
            private final Set<String> buildFiles = Set.of(
                    "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
                    "requirements.txt", "Dockerfile", "settings.gradle");

            @Override
            public String name() {
                return "no-dependency-changes";
            }

            @Override
            public String rationale() {
                return "Adding a dependency changes what executes on every build machine. "
                        + "That decision belongs to a human, not to a code-generation step.";
            }

            @Override
            public Verdict check(Request r) {
                String path = r.args().get("path");
                if (path == null) return Verdict.allow();

                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (buildFiles.contains(fileName)) {
                    return Verdict.deny("proposals may not modify " + fileName
                            + " — dependency and build changes require a human author. "
                            + "Raise it as an open question instead.");
                }
                return Verdict.allow();
            }
        };
    }
}