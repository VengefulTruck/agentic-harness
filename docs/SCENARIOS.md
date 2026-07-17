# Scenarios

Three required by the assignment — greenfield, brownfield, ambiguous — plus the
failure paths, which are where a harness is actually tested. A suite that only shows
the happy path is evidence of a demo, not of a system.

Each is runnable, and each is also an automated test in
`src/test/java/com/jay/agentic/eval/HarnessScenarioTest.java`.

---

## 1. Brownfield — a change to an existing codebase

> **"Add per-IP rate limiting to POST /api/v1/links"**

```bash
mvn -q exec:java -Dexec.args='--scenario brownfield --repo ../urlShortener --requirement "Add per-IP rate limiting to POST /api/v1/links"'
```

### Decomposition

`INTAKE` normalises: goal, in scope, out of scope, unstated, acceptance. The
*unstated* section becomes open questions — here, "what the limit should be" and
"what response a limited client receives".

`EXPLORE` runs a bounded search-then-read loop: two search rounds, at most six files.
The bound is the point — an unbounded explorer reads the whole repository, because
from inside the loop there is always one more file that might matter.

`PLAN` decomposes into ordered tasks with dependencies, each grounded in a real path.

### Orchestration

`INTAKE → EXPLORE → PLAN → APPROVAL_GATE → IMPLEMENT →` fan-out to
`{TEST, DOCS, SECURITY_REVIEW}` → join at `VALIDATE → RELEASE_GATE → SUMMARY`.

`CLARIFY` is `SKIPPED` — not applicable to a well-specified requirement, and an empty
clarification round is noise in the audit log rather than evidence of diligence.

### Validation

`hasArtifact` exit gates on every generative stage. `planTasksCovered` on IMPLEMENT.
`SECURITY_REVIEW` runs a deterministic scan **and** a model review. `VALIDATE` checks
all four artifacts against each other and against the original requirement.

### What actually happened

A real live run against the shortener produced this impact analysis — abridged:

> **src/main/java/com/schwab/urlShortener/api/ShortLinkController.java** — the
> `@PostMapping` method (line 40) would need rate limiting logic added…
>
> **Logging for client errors** — use `log.debug()` for rate limit violations (not
> ERROR), following the pattern in `GlobalExceptionHandler.java` where 404s and
> validation failures use debug level because they're "normal traffic".

Real files, real line numbers, and a codebase convention nobody told it about. That
is the difference between a code generator and an engineering assistant.

**And it was blocked at the release gate.** See §4.

> Evidence: `runs/run-20260717-003831/`

---

## 2. Greenfield — a new capability in an existing codebase

> **"Add a QR code endpoint that returns a PNG for a short link"**

```bash
mvn -q exec:java -Dexec.args='--scenario greenfield --repo ../urlShortener --requirement "Add a QR code endpoint that returns a PNG for a short link"'
```

### What is different

`CLARIFY` is `SKIPPED` — the requirement is specific, and an empty clarification
round is noise in the audit log rather than evidence of diligence.

`EXPLORE` **still runs**, and that is the interesting part.

### The bug this scenario found

EXPLORE originally applied only to brownfield and ambiguous runs. The reasoning
seemed sound: a new capability has no existing code to reason about.

A live run proved it wrong. With no codebase picture, PLAN guessed the stack:

```
1. [requirements.txt or pyproject.toml or similar] Add qrcode library dependency
2. [app/routes/ or routes/ or similar routing configuration file] Add new route
```

Python. Against a Java Spring repository.

IMPLEMENT then **refused**:

> *"I need to analyze the codebase structure to understand the framework and
> conventions before implementing the plan."*

That refusal was correct — the system prompt says *"follow the plan; if the plan is
wrong, say so rather than silently deviating"*, and it did, rather than writing Flask
into a Spring project. **The guardrail held; the graph was wrong.**

The mistake was conflating **"a new system, from nothing"** with **"a new feature, in
something"**. Almost every real greenfield task is the second: the code is new, the
conventions are not. EXPLORE on a greenfield run is not looking for code to modify —
it is looking for the patterns the new code must match, and for confirmation that the
capability does not already exist.

When there genuinely is no target repository, the searches return nothing and the
impact analysis says so. **That is a finding, not a failure.**

### The design point this exercises

`PLAN` depends on `INTAKE, CLARIFY, EXPLORE`. On a greenfield run, CLARIFY never
executes. The run does not deadlock, because **`SKIPPED` counts as successful**
(`StageStatus.isSuccessful()`), so a dependent of a skipped stage unblocks naturally.

`Orchestrator.initialise()` marks inapplicable stages `SKIPPED` **eagerly**, at run
start, rather than lazily. Leaving them `PENDING` would silently deadlock everything
downstream — PLAN waiting forever on a CLARIFY that is never going to happen.

One graph, three scenarios, different active subsets.

> Test: `greenfieldStillLearnsTheConventions`

## 3. Ambiguous — under-specified on purpose

> **"Make the links safer"**

```bash
mvn -q exec:java -Dexec.args='--scenario ambiguous --repo ../urlShortener --requirement "Make the links safer"'
```

Safer *how*? Blocking malicious destinations? Rate limiting? Unguessable codes?
Expiry? Encryption at rest? The requirement does not say, and both obvious responses
are wrong:

- **Refuse until fully specified** → useless. No real requirement is fully specified.
- **Guess silently** → dangerous.

### What the harness does instead

Guesses, **writes the guess down, and grades it by consequence**:

```
ASSUMPTION | MEDIUM | the limit is 100 requests per minute | the requirement gives no number
ASSUMPTION | LOW    | the response is HTTP 429              | conventional for rate limiting
QUESTION   | What should the per-IP limit actually be?
```

The grade is not decoration. `Assumption.blocksRelease()` returns true for
`OPEN` + `HIGH`, and `RELEASE_GATE`'s **entry gate** refuses to open while any exist.
The model's own judgement about consequence is wired directly to whether a human is
forced to look.

Two details worth noticing:

- **An unreadable risk grade defaults to HIGH.** If the model could not say how much
  a guess matters, that uncertainty is itself a reason to make a human look. Note
  this is the *inconvenient* default — HIGH blocks the pipeline.
- **The prompt says "grading everything HIGH is as useless as grading nothing HIGH".**
  A model told that HIGH stops the pipeline will hedge and grade everything HIGH;
  every run blocks; humans start rubber-stamping. The control dies from being too
  loud rather than from a bug. Same reason the secret scanner filters placeholders.

> Test: `ambiguousRunRecordsAssumptions`

---

## 4. Security-sensitive — a hardcoded credential

The scenario the assignment's rubric asks for under "security-sensitive cases", and
one that happened for real during this build: an API key was pasted into a chat
window in the middle of doing something else. That is how it actually happens — not
through malice or ignorance.

A model asked to wire up an API client will, given the chance, inline the key. It has
read a great deal of code that does exactly that. The file looks correct, compiles,
works, and the secret is in git history before anyone reads the diff.

### What the harness does

`no-secrets-in-generated-content` runs **before** the write. `propose_patch` is never
invoked; no artifact exists; IMPLEMENT burns its retries and fails.

Three details:

- **Prevention, not detection.** A scanner on the finished file has found the secret
  after it exists on disk.
- **The finding is redacted** — `sk-a****al`. A scanner that logs what it caught has
  written the secret to a second file.
- **The denial becomes an open question**, so the human learns the agent tried. A
  guardrail that fires silently has taught nobody anything.

The prompt *also* forbids inlining credentials. That is an optimisation that saves a
retry — the policy is the control. If asked which one you rely on: the policy.

> Test: `secretInGeneratedCodeIsRefused` — asserts the tool was never invoked, and
> that the audit trail does not contain the secret.

---

## 5. Release blocked — the run that matters most

This is not a hypothetical. It happened three times in a row on live runs, on
progressively deeper findings.

```
RELEASE_GATE  FAILED   entry gate: 4 unresolved high-risk assumption(s)
SUMMARY       BLOCKED
```

What the pipeline had produced, and what caught it:

| Finding | Caught by |
|---|---|
| Race condition: two threads both read count=100, both pass, both increment | SECURITY_REVIEW |
| X-Forwarded-For trusted without validation — attacker rotates fake IPs | SECURITY_REVIEW |
| The security review says "check-then-increment"; the code does increment-then-check | **VALIDATE** |
| **The limiter exists but is not wired to any controller or filter chain** | **VALIDATE** |

That last one is the killer. **Every stage passed its own exit gate. The code
compiled. The tests passed. And the change did nothing** — a rate limiter connected
to nothing.

No upstream stage could have caught it, because no upstream stage saw more than its
own output. VALIDATE caught it by checking the artifacts against the **original
requirement** rather than against the plan — stages drift, and a plan can be followed
faithfully and still solve a different problem.

The gate then blocked *before a human was asked*. Cost: $0.24.

> Evidence: `runs/run-20260717-002508/`, `runs/run-20260717-003111/`,
> `runs/run-20260717-003831/`
>
> Test: `highSeverityFindingBlocksReleaseBeforeAsking` — the approver always says
> yes, and the assertion is that it **was never asked**.

---

## 6. Human rejection

```bash
# choose [r]eject at the first checkpoint
```

The run stops. `IMPLEMENT` is `BLOCKED`. **No patch exists.**

That is why `APPROVAL_GATE` sits before generation rather than before the merge:
rejecting a plan costs one stage, rejecting a finished branch costs the run.

The rejection is recorded in the decision ledger with the human's reason. A rejection
is a decision too — if only approvals were recorded, the trail would show a run that
stopped for no visible reason.

> Test: `humanRejectionStopsTheRun`

---

## 7. Unattended — nobody is watching

```bash
mvn -q exec:java -Dexec.args='--unattended --scenario brownfield --repo ../urlShortener --requirement "Add per-IP rate limiting to POST /api/v1/links"'
```

`ApprovalPort.denyAll()` refuses everything. The run stops with nothing generated.

The tempting alternative — auto-approve when unattended so CI does not stall — is
exactly the mistake. **A gate that opens when nobody is watching is not a gate.** If
a checkpoint exists because an action is high-impact, "nobody was there to look" is a
reason to stop, not a reason to proceed.

Same principle throughout: EOF at the console prompt is a rejection, a policy that
throws is a denial, an unreadable risk grade is HIGH.

> Test: `unattendedRunFailsClosed`

---

## 8. Partial completion and bounded retries

Two shapes, and the harness distinguishes them.

**Recoverable** — IMPLEMENT has `maxAttempts(3)`. A stage that fails twice and
succeeds on the third passes, and the trace records **three attempts**, not one. A
run that reported only the outcome would hide the flakiness the retry budget is
paying for.

**Unrecoverable** — a stage that exhausts its budget fails, and `blockDownstream`
marks the whole transitive cone `BLOCKED` in a single operation. The loop then finds
nothing ready and exits. The run halts rather than producing docs for a patch that
does not exist.

The retry budgets differ by stage, on purpose:

| Stage | Attempts | Why |
|---|---|---|
| `IMPLEMENT` | 3 | Code generation is genuinely flaky. |
| `TEST` | 3 | Same. |
| `EXPLORE`, `PLAN`, `DOCS`, `VALIDATE` | 2 | One retry is worth it; two is superstition. |
| `SECURITY_REVIEW` | **1** | A review that fails is a *broken* review, not an unlucky one. Retrying a deterministic scan buys the same answer at a price; retrying the model until it says CLEAN is not review, it is shopping for a verdict. |

**Partial success is allowed to continue.** If IMPLEMENT proposes two files and one
is refused by policy, the run proceeds with the permitted one and the refusal becomes
an open question the human sees at the release gate. Failing the whole stage would be
tidier and would throw away good work over a caught mistake.

> Tests: `flakyStageRecoversWithinItsRetryBudget`,
> `exhaustedRetriesBlockTheDownstreamCone`

---

## 9. Incomplete change — the plan-coverage gate

The scenario that exists because a live run failed.

`ImplementAgent` originally capped output at 3 files. The plan had 7 tasks. The
harness produced a limiter that was never wired to the endpoint — and the cap, a
context-size decision, had silently changed **what the change was**.

VALIDATE caught it, four stages and several model calls later, as a contradiction.

The fix was both: raise the cap, *and* add a `planTasksCovered()` exit gate on
IMPLEMENT so that "the model omitted a planned task" and "the stage failed" are the
same event. A failure is retryable at the stage that caused it; a contradiction
discovered downstream is not.

The match is deliberately loose — bare filename, not full path — because the plan and
the patch can legitimately disagree about path prefixes and a strict comparison would
fail honest work. It is a completeness check, not a conformance one.

> Test: `planCoverageGateCatchesAnOmittedTask`

---

## 10. Regression and drift

Two mechanisms, both structural rather than procedural.

**Staleness.** Every artifact carries a content hash; every stage records the hashes
it read. `propagateStaleness` re-asks after every pass whether they still match. A
rejected patch → IMPLEMENT re-runs → new hash → the tests and docs describing the old
code are marked `STALE` and re-run. No event bus, no dirty flags, no notification an
agent could forget to send.

**Requirement coverage.** VALIDATE checks the finished change against the *original
requirement*, not the plan. Every stage can follow its input faithfully and the run
still solves a different problem, because each hop loses a little. Only shortfalls
become open questions — fifteen "yes, satisfied" entries would bury the two that say
"partial".

---

## Scenario coverage against the rubric

| Rubric §8 asks for | Where |
|---|---|
| Multiple realistic scenarios | §1, §2, §3 |
| Ambiguous or incomplete requirements | §3 |
| Bug investigation flows | §5 — race condition, spoofing, unwired component |
| System-level feature scenarios | §1 — filter, exception handler, config, tests |
| Security-sensitive cases | §4, §5 |
| Failure and partial-completion handling | §6, §7, §8, §9 |
| Regression considerations | §10 |
| Output quality criteria | Exit gates; `planTasksCovered`; VALIDATE |
| Safety and correctness checks | §4, §5, §7 |
| Clear articulation of limitations | `docs/ARCHITECTURE.md` — 10 named, plus 2 real bugs |
