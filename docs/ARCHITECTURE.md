# Architecture

## What this is

A harness that takes an engineering requirement and walks it through a full SDLC
pipeline — requirements, clarification, codebase reasoning, planning, implementation,
testing, documentation, security review, validation, release readiness — under
governance, and produces a reviewable change proposal.

**The URL shortener is not the deliverable.** It is the demo domain: the brownfield
codebase the harness reasons about. The harness lives in `agentic-harness/`; the
shortener lives in `urlShortener/` and is never modified by a run.

## The two-repository boundary

```
projects/
├── urlShortener/       the target. Untouched on main. The material.
└── agentic-harness/    the system. Takes a target repo path as configuration.
```

The harness reaches the target only through a configured path and two read-only
tools. There is no code path from this codebase to the target's working tree — see
"Write authority" below. Co-locating the two would make "clear boundaries between
agent logic and application logic" a claim rather than a fact.

## Component map

```
com.jay.agentic
├── orchestration/   the DAG, gates, scheduling, human checkpoints
├── state/           what is known, decided, assumed, produced — and persistence
├── context/         (folded into agents/PromptBuilder)
├── agents/          the ten SDLC stages
├── tools/           the narrow waist: every reach outside the harness
├── policy/          authority rules, evaluated before an action
├── llm/             the model boundary, tiering, caching, budget
├── observability/   traces, audit log, reliability metrics
├── hooks/           extension points
└── cli/             composition root and console front-end
```

Each package maps to one graded concern. Nothing in `agents/` knows how it was
scheduled; nothing in `orchestration/` knows what a stage does.

## The pipeline

```
   INTAKE
     |
     +----------------+
     |                |
   CLARIFY         EXPLORE          scenario-dependent; SKIPPED when not applicable
     |                |
     +--------+-------+
              |
            PLAN                    joins: a plan needs the codebase picture
              |
        APPROVAL_GATE               ── HUMAN. Nothing is generated before this.
              |
          IMPLEMENT
              |
     +--------------+------------------+
     |              |                  |
   TEST           DOCS        SECURITY_REVIEW      parallel, no mutual dependency
     |              |                  |
     +--------------+------------------+
                    |
                VALIDATE                           join barrier: waits for all three
                    |
              RELEASE_GATE          ── HUMAN. Refuses while HIGH assumptions are open.
                    |
                 SUMMARY
```

### Parallelism is emergent, not configured

Nothing in the orchestrator says "run TEST and DOCS together". `WorkflowGraph.readyStages`
returns a **set** of stages whose dependencies are satisfied; `Orchestrator.executePass`
submits all of them. TEST, DOCS and SECURITY_REVIEW each depend on IMPLEMENT and on
nothing else, so they are simply ready at the same time.

The join is the same property in reverse: VALIDATE lists three dependencies, so it
cannot start until the slowest finishes. Neither the fan-out nor the barrier is
implemented anywhere — both are consequences of the dependency set. A `next` pointer
would have made the graph a linked list and required explicit machinery for both.

### Re-planning is detection, not notification

Every `Artifact` carries a SHA-256 of its content. Every `StageResult` records the
id and hash of each artifact it read (`inputFingerprint`). After each pass,
`Orchestrator.propagateStaleness` re-asks whether those hashes still match reality.
A mismatch marks the stage `STALE`; `readyStages` treats `STALE` as re-runnable
alongside `PENDING`, so it is rescheduled with no special code path.

Concretely: a patch is rejected, IMPLEMENT re-runs, the patch hash changes, and the
tests and docs that describe the old code are invalidated and re-run.

There is no observer, no event bus, and no dirty flag an agent could forget to set.
State that cannot lie beats a protocol that must be remembered.

## State model

| Type | Role |
|---|---|
| `TaskState` | The run. The only mutable class; everything it holds is immutable. |
| `StageResult` | One stage's status, attempts, input fingerprint, outputs. |
| `Artifact` | A produced output + content hash. The hash drives staleness. |
| `Assumption` | A recorded guess, graded LOW/MEDIUM/HIGH by consequence. |
| `Decision` | A choice **with the alternatives it beat** and who made it. |
| `EvidenceRef` | A pointer to what justified a claim. |
| `RunSnapshot` | An immutable, serialisable freeze. What goes to disk. |
| `StateStore` | Atomic JSON persistence. Temp-file-then-rename. |

**`TaskState` is mutable and everything else is not.** Parallel stages all append to
it; copy-on-write would require a compare-and-swap retry loop on every append or
writes would be silently lost. A lock over an append-mostly aggregate is simpler to
reason about. Mutability stops at exactly one class.

**`RunSnapshot` is separate from `TaskState` deliberately.** Serialising the live
aggregate would mean Jackson walking its internals while other threads append — a
data race hidden inside a library call. It also separates the persistence format
from the in-memory model: the run file is an audit artifact that outlives the code.

**Ten stage statuses, not a boolean.** `AWAITING_APPROVAL` and `STALE` are the
interesting ones — a `done` flag cannot express "waiting on a human" or "was correct,
now invalidated", and those two are the whole assignment.

## Governance

### Gates vs. policies — the distinction is load-bearing

| | Gate | Policy |
|---|---|---|
| Asks | "Is this work good enough?" | "Is this action permitted?" |
| About | Quality | Authority |
| Runs | After the work | **Before** the action |

Merging them would mean the only way to stop a forbidden action is to notice it
afterwards — which, for anything with side effects, is too late. Secret scanning is
a policy, not a gate, for exactly this reason: a scanner on a finished file has found
the secret after it exists on disk.

### Entry and exit gates

- **Entry**: is it sensible to start? Can skip or refuse.
- **Exit**: is the output fit to hand downstream? Can fail a stage that ran fine
  and produced rubbish.

Without exit gates, an agent that returns an apology instead of a patch counts as a
success. `hasArtifact(...)` is the minimum bar for every generative stage.

### Two human checkpoints

`APPROVAL_GATE` sits **before** any code is generated. Rejecting a plan costs one
stage; rejecting a finished branch costs the run.

`RELEASE_GATE` has an **entry gate** that refuses to open while an unresolved
HIGH-risk assumption exists. This is the sharpest control in the system: it blocks
*before a human is asked*. An approver under time pressure clicks approve; a gate
that won't open removes the opportunity. Verified by
`HarnessScenarioTest.highSeverityFindingBlocksReleaseBeforeAsking`, which uses an
approver that always says yes and asserts it was never asked.

### Two kinds of unresolved question, deliberately treated differently

A HIGH-risk assumption raised by `CLARIFY` can be settled by a human at
`APPROVAL_GATE`: the console asks about each one before the approve prompt, and the
answer is recorded as a human decision in its own right.

A HIGH-risk assumption raised by `SECURITY_REVIEW` or `VALIDATE` **cannot be settled
that way**, because `RELEASE_GATE`'s entry gate blocks before `requestApproval` is
ever called. That asymmetry is deliberate, and it is the sharpest judgement in the
design:

> **CLARIFY's guesses are questions. SECURITY_REVIEW's findings are defects.**

A question gets answered. A defect gets fixed and the run repeated. Allowing someone
to click past a HIGH security finding is exactly the rubber-stamp the gate exists to
prevent — and an approver under time pressure will click.

The cost is real and worth naming: a legitimate *"yes, I accept that limitation for
now"* also requires a re-run with a sharper requirement, because there is no way to
say it at the gate. That is the asymmetry chosen on purpose — it is cheaper to re-run
than to un-ship.

The evidence that this is not simply always-on: the greenfield QR run **completed**,
because its findings were genuinely MEDIUM and LOW. The model grades honestly when the
prompt tells it that grading everything HIGH is as useless as grading nothing HIGH.

### Write authority — capability absent, not guarded

The harness **cannot** modify the target repository. Not "is prevented from" —
*cannot*. `PatchProposalTool` writes proposals into the run directory; no tool exists
that writes to the target working tree. An attacker with full control of the model
could not use this harness to change the shortener, because there is no code path
that does so.

Most implementations would have a `WriteFileTool` with an `if (approved)` in front
of it. That safety lives in a conditional a refactor can remove. This one is an
absence.

Asserted by `HarnessScenarioTest.targetRepositoryIsNeverTouched`: the repo is
byte-identical before and after a fully approved run.

### The policy engine is the only path to a tool

Agents call `policy.invoke(tool, args, stage, state)`. There is no
`tools.find(...).invoke(...)` anywhere in `agents/`. That indirection *is* the
control — an agent with a direct tool reference would make every policy advisory.

Two rules govern how it decides:

- **Deny wins.** Every policy is evaluated; any refusal ends it. No precedence
  order, because precedence orders are where security bugs hide.
- **Fail closed.** A policy that throws is a *denial*, not an abstention. "We could
  not determine whether this was allowed, so we did it" is not defensible.

### The four policies

| Policy | Prevents |
|---|---|
| `no-mutation-before-approval` | Generating anything before a human sees the plan. Keys off the `isMutating` flag, not a tool-name list, so a tool added next month is governed on day one. |
| `no-secrets-in-generated-content` | A credential reaching a file. Checked before the write. Findings are **redacted** in the audit log — a scanner that logs what it caught has written the secret to a second file. |
| `writes-confined-to-proposals` | Guards nothing today, deliberately: it exists for the maintainer who adds a write tool in six months. |
| `no-dependency-changes` | A change to `pom.xml` is a change to what executes on every build machine. Different blast radius, different rules. |

A denial becomes an **open question on the state**, so the human learns the agent
tried. A guardrail that fires silently has taught nobody anything.

## Cost and model tiering

```
LlmClient (interface)
├── AnthropicLlmClient       real calls; hand-rolled over java.net.http
├── DeterministicLlmClient   canned; what makes the harness testable
└── BudgetedLlmClient        decorator: cache + budget + attribution
```

**Two tiers, named by role.** `Tier.FAST` (Haiku) for classification — is this
ambiguous, which regex to search for, prose that restates a decision already made.
`Tier.DEEP` (Sonnet) for reasoning — architecture, code generation, review. Agents
ask for a capability, never a model string; the model names live in one file.

`ExploreAgent` uses **both within one stage**: FAST to pick search terms, DEEP to
synthesise the impact analysis. Different jobs, 3× price difference.

**The budget is checked before the call**, not after. A limit enforced afterwards is
a report. Exceeding it raises `BudgetExceededException`, the stage fails, the
downstream cone blocks — safe-stop with a real trigger.

**The cache is exact-match on a hash of the full request.** A near-match cache
returns the answer to a question you didn't ask, and the failure mode is a plausible
wrong answer. It also pairs with re-planning: when IMPLEMENT re-runs and the fan-out
goes stale, unchanged sub-questions cost nothing. **Re-planning and caching are the
same feature seen from two sides.**

**Mock-first is not a shortcut.** It is why category 8 is scoreable at all: a suite
that hit a live API on every run would be non-deterministic, slow, and expensive.
The real client is the default; the deterministic one is what makes the system
testable. Both are load-bearing.

## Observability

`Tracer` writes **JSONL** — one self-contained record per line, appended immediately.
Not a single JSON document, because a trace must survive a crash: a partially written
array is unparseable, a truncated JSONL file loses its last line and nothing else.
The state file *can* be rewritten atomically because it is small; a trace cannot, so
it is designed to tolerate truncation instead.

Span kinds: `STAGE`, `LLM_CALL`, `TOOL_CALL`, `GATE`, `POLICY`, `APPROVAL`.

Recorded per **attempt**, not per stage — a stage that passed on its third try is a
different fact from one that passed first time.

Reliability metrics computed from the trace: stage executions, passed, failed,
retries, policy refusals, success rate, total latency, slowest span. The approval
**wait** is recorded too: a gate that parks a run for an hour is doing its job; a
gate nobody answers is a bottleneck, and the trace is the only place that difference
is data rather than opinion.

## Extensibility

`HookRegistry` fires at eight points: `RUN_START`, `BEFORE_STAGE`, `AFTER_STAGE`,
`ON_FAILURE`, `ON_APPROVAL`, `ON_REPLAN`, `ON_POLICY_DENIAL`, `RUN_END`. These cover
requirement analysis, code search, planning, code and test generation, security
checks, validation, human approval, failure handling, and summary generation — a
`BEFORE_STAGE` hook receives the `StageId`, so per-stage extension is a filter, not
a new point.

Two rules make it safe:

- **A hook cannot fail the run.** Exceptions are caught and reported. A Slack outage
  must not fail a code review. Gates and policies are where something is *allowed*
  to stop a run, and they are deliberately **not** extensible this way — a governance
  layer any plugin can weaken is not one.
- **A hook cannot mutate the run.** It reads; its return value is ignored. If hooks
  could write, registration order would silently become part of the semantics.

Extension also happens by **registration** rather than modification: a new `Tool`
declaring `isMutating()` is governed by `no-mutation-before-approval` the day it is
written. A new `Agent` is a constructor argument in `Main`. A new `Policy` is one
entry in `Policies.standard()`.

## Known limitations

Stated here rather than discovered by a reviewer.

1. **Generated tests are never executed.** TEST authors tests; running them needs a
   tool that invokes Maven against the target — a mutating tool touching the target
   tree, which the write-authority design forbids. The right answer is a sandboxed
   runner (container, no network, read-only mount). Out of scope here.
2. **`GrepTool` is vulnerable to ReDoS.** The pattern comes from a model; `(a+)+b`
   backtracks exponentially. Mitigated by a length cap and a match ceiling, not
   fixed. The real fix is a matcher timeout or RE2.
3. **`FileReadTool` has a TOCTOU gap.** Path is canonicalised, then read; a symlink
   swapped in between defeats it. Irrelevant for a local single-user harness, real
   in a shared one.
4. **Markdown parsing is lenient.** A malformed `ASSUMPTION |` line is skipped, so a
   mangled HIGH assumption could be lost and the release gate would then pass
   something it should block. Mitigated — the raw model output is preserved verbatim
   in the artifact a human reads — not eliminated. Structured tool-use output would
   remove the gap entirely.
5. **The cache is per-process.** A resumed run starts cold. Persisting it keyed by
   hash would survive restarts.
6. **The budget overshoots by up to one call.** The check runs against spend-so-far,
   and a call's cost is unknowable before making it.
7. **`checkpoint()` rewrites the full state after every pass** — O(n) writes of a
   growing file. Fine at this size; at scale you want an append-only event log with
   periodic snapshots, which is what real workflow engines do.
8. **No jitter on HTTP backoff.** Three concurrent stages hitting a 429 retry in
   lockstep. Harmless at a fan-out of three, wrong at any real scale.
9. **`RunSnapshot.of()` takes each field under its own lock**, not one lock across
   all of them. At stage boundaries — when it is actually called — the window does
   not exist. If it mattered, the fix is a `snapshot()` method inside one
   synchronized block.
10. **`SUMMARY` reports its own status as `RUNNING`** in the stage table, because the
    summary is generated during the stage. Honest rather than wrong: claiming it had
    passed while it was still executing would be a lie.
11. **`planTasksCovered` only inspects `.java` files.** A plan task naming `pom.xml`
    or `application.properties` is invisible to it. Combined with
    `no-dependency-changes`, a change can be proposed that imports a library the build
    does not have — as happened on the greenfield QR run, where the code imported
    `com.google.zxing` and the pom change was correctly refused. The policy is right
    to refuse; the gap is that nothing surfaces the resulting incoherence as a
    blocker. The fix is to extend the gate to non-Java plan tasks and raise an
    explicit open question when a refused dependency is load-bearing.
12. **Only HIGH-risk assumptions are put to a human.** MEDIUM ones are disclosed at
    the checkpoint and never asked about, so a MEDIUM guess ships unless the approver
    reads carefully. That is deliberate — asking about a dozen guesses gets a dozen
    reflexive yeses — but the threshold is a judgement, not a law, and it depends on
    the model grading honestly.
13. **`VALIDATE` checks the change against the requirement, not against the confirmed
    assumptions.** On the ambiguous run, a human confirmed that the safety concern was
    malicious destinations, and the plan addressed SSRF instead — a defensible choice
    (the concrete documented gap over the vague threat) that nonetheless does not
    follow from the confirmed assumption. Nothing downstream flags that mismatch; only
    the human noticed. Feeding resolved assumptions into VALIDATE's comparison would
    close it.
14. **The `DECISION |` parser does not strip markdown emphasis.** A plan that writes
    `**DECISION** |` falls through to the fallback ledger entry — which fires
    correctly, recording that the reasoning did not parse rather than dropping it
    silently, but the reasoning then lives only in the artifact. Observed on the
    ambiguous run. The fix is one `replaceAll`; it is listed rather than done because
    the failure is loud and the mitigation already works.

## Bugs found by running it

Both were found by live runs, not by design review. Kept here because how a system
fails is more informative than how it was intended to work.

**1. PLAN ran in parallel with EXPLORE.** Both originally depended only on INTAKE, so
both became ready in the same pass — and PLAN planned against an impact analysis that
did not exist yet. The emergent parallelism that makes the fan-out free made this
mistake free too. Notably, PLAN did **not** hallucinate paths to fill the gap; it
wrote `[UNKNOWN: dependency file]` and flagged "cannot proceed without impact
analysis" as a risk. Fixed by adding `CLARIFY, EXPLORE` to PLAN's dependencies, which
works across all scenarios because `SKIPPED` counts as successful.

**2. A file cap silently changed what the change was.** `ImplementAgent` capped output
at 3 files; the plan had 7 tasks. The harness produced a rate limiter that was never
wired to the endpoint it was meant to protect — every stage passed, the code
compiled, and the change did nothing. VALIDATE caught it as a contradiction four
stages later. Fixed twice over: the cap was raised, and IMPLEMENT gained a
`planTasksCovered()` exit gate so an omitted task is a *retryable stage failure* at
the stage that caused it rather than a contradiction discovered downstream.

**3. EXPLORE was skipped on greenfield runs, so PLAN guessed the stack.** The
reasoning seemed sound — a new capability has no existing code to reason about — and
it conflated "a new system, from nothing" with "a new feature, in something". Almost
every real greenfield task is the second. With no codebase picture, a live run
produced a plan naming `requirements.txt` and `app/routes/` against a Java Spring
repository. IMPLEMENT then refused to write it: *"I need to analyze the codebase
structure to understand the framework and conventions before implementing the plan."*
The guardrail held; the graph was wrong. EXPLORE now applies to every scenario.

The deterministic client could not have caught any of the three — canned responses
exercise the plumbing, not the reasoning. **Knowing what a test double cannot prove is
worth more than the test double.**

Two smaller ones, same source:

- **A stateful hook registered twice is two hooks.** `slowStageWarning` was
  instantiated separately at `BEFORE_STAGE` and `AFTER_STAGE`, so the second instance
  never received the event that set its start time and reported the elapsed time
  since the epoch: `INTAKE took 1784291745.5s`.
- **The success rate multiplied one failure by its blast radius.** BLOCKED stages
  were traced with outcome `FAILED`, so a single IMPLEMENT failure that blocked six
  downstream stages reported seven failures and a 15% success rate. BLOCKED and
  SKIPPED are now excluded from the denominator: the rate is over stages actually
  *attempted*.
