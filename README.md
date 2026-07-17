# agentic-harness

An agentic software engineering system. Give it a requirement; it takes that
requirement through a governed SDLC pipeline — requirements, clarification, codebase
reasoning, planning, implementation, testing, documentation, security review,
validation, release readiness — and produces a change **proposal** a human reviews.

It never modifies the target repository. It cannot: no such tool exists.

> **The URL shortener is not the deliverable.** It is the brownfield codebase this
> harness reasons about. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Setup

### Prerequisites

- **Java 17** (LTS — matches the target repository)
- **Maven 3.9+**
- An **Anthropic API key** with credit, for live runs. Not needed for mock runs.

```bash
java -version      # expect 17.x
mvn -version
```

### Layout

The harness expects the target repository beside it:

```
projects/
├── agentic-harness/    <- you are here
└── urlShortener/       <- the target. Untouched.
```

### API key

```bash
export ANTHROPIC_API_KEY="sk-ant-..."     # add to ~/.zshrc to persist
echo $ANTHROPIC_API_KEY                   # verify
```

The key is read from the environment and held nowhere else — not a config field,
not a constructor argument, never logged, never written to a run file.

Verify it works and has credit:

```bash
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d '{"model":"claude-haiku-4-5-20251001","max_tokens":16,"messages":[{"role":"user","content":"ping"}]}'
```

A JSON response with `"content"` means you're set. `credit balance is too low` means
the key is valid but the account is empty — top up at console.anthropic.com.

---

## Build and test

```bash
mvn clean test
```

Expect **BUILD SUCCESS** and **57 tests**. This runs entirely offline — no API key
needed, no network, no cost. That is deliberate; see "Why mock-first" below.

Coverage report (optional):

```bash
mvn clean test jacoco:report
open target/site/jacoco/index.html
```

---

## Run it

### Offline, no key, no cost — start here

```bash
mvn -q exec:java -Dexec.args='--mock --scenario brownfield --repo ../urlShortener --requirement "Add per-IP rate limiting to POST /api/v1/links"'
```

This walks the entire graph against canned model responses. **The tool calls are
real** — it searches and reads your actual shortener — only the model's reasoning is
substituted. It will stop twice and ask you to approve.

### Live

```bash
mvn -q exec:java -Dexec.args='--scenario brownfield --repo ../urlShortener --requirement "Add per-IP rate limiting to POST /api/v1/links"'
```

~10 model calls, 30–90 seconds, roughly **$0.20–0.30** against a $2.00 default cap.

### All options

| Flag | Meaning |
|---|---|
| `--requirement "<text>"` | What to build. **Required.** |
| `--repo <path>` | Target repository. **Required.** |
| `--scenario <type>` | `greenfield` \| `brownfield` \| `ambiguous`. **Required.** |
| `--mock` | Offline, deterministic, free. |
| `--unattended` | Refuse all approvals (fail closed). |
| `--budget <usd>` | Spend cap per run. Default `2.00`. |
| `--run-id <id>` | Name the run. Default is a timestamp. |

Exit codes: `0` completed · `1` failed · `2` bad arguments · `3` awaiting a human ·
`4` stopped by a guardrail.

### The three scenarios

```bash
# BROWNFIELD — reasons about the existing codebase
mvn -q exec:java -Dexec.args='--scenario brownfield --repo ../urlShortener --requirement "Add per-IP rate limiting to POST /api/v1/links"'

# GREENFIELD — new capability; EXPLORE is skipped, not failed
mvn -q exec:java -Dexec.args='--scenario greenfield --repo ../urlShortener --requirement "Add a QR code endpoint that returns a PNG for a short link"'

# AMBIGUOUS — under-specified; forces assumptions and questions
mvn -q exec:java -Dexec.args='--scenario ambiguous --repo ../urlShortener --requirement "Make the links safer"'
```

---

## Verify it

### 1. The tests

```bash
mvn clean test
```

57 tests. The interesting ones are in `HarnessScenarioTest` — they run the **real**
graph, orchestrator, policy engine and tools end to end, substituting only the model
and the human:

| Test | Proves |
|---|---|
| `brownfieldRunCompletes` | Full pipeline, both checkpoints, all artifacts |
| `greenfieldSkipsExplore` | A skipped stage satisfies its dependents |
| `ambiguousRunRecordsAssumptions` | Guesses are graded objects, not prose |
| `highSeverityFindingBlocksReleaseBeforeAsking` | The gate refuses **before a human is asked** |
| `secretInGeneratedCodeIsRefused` | Refused before the write; secret not logged |
| `humanRejectionStopsTheRun` | Rejection stops it with nothing generated |
| `unattendedRunFailsClosed` | Nobody watching ⇒ nothing approved |
| `flakyStageRecoversWithinItsRetryBudget` | Bounded retries recover |
| `exhaustedRetriesBlockTheDownstreamCone` | One failure blocks the cone in one pass |
| `planCoverageGateCatchesAnOmittedTask` | An incomplete change fails where it was caused |
| `targetRepositoryIsNeverTouched` | Repo byte-identical after an approved run |
| `deferredRunIsResumable` | A parked run outlives the process |
| `everyRunIsTraceable` | Append-only JSONL trace with span kinds |
| `readToolRefusesTraversal` | Path escape refused |
| `ambiguityBlocksReleaseNotPlanning` | A HIGH guess stops shipping, not planning |
| `resolvedAssumptionUnblocksRelease` | A human answering unblocks the run |
| `rejectedAssumptionIsRetained` | An overturned guess is recorded, not erased |

### 2. What a run leaves behind

```
runs/<run-id>/
├── state.json      the complete run: stages, decisions, assumptions, artifacts
├── trace.jsonl     append-only execution trace, one span per line
└── proposals/
    ├── <flattened-path>.java             the proposed file
    └── <flattened-path>.java.rationale.txt   why
```

```bash
cat runs/<run-id>/state.json | python3 -m json.tool | less
cat runs/<run-id>/trace.jsonl | python3 -c "import sys,json; [print(json.loads(l)['stage'], json.loads(l)['kind'], json.loads(l)['outcome']) for l in sys.stdin]"
ls runs/<run-id>/proposals/
```

### 3. Prove it never touched the target

```bash
cd ../urlShortener && git status
```

Clean. Always. After any run, approved or not.

---

## What the output means

```
  Stages:
    INTAKE           PASSED
    CLARIFY          SKIPPED           <- not applicable to this scenario
    EXPLORE          PASSED
    PLAN             PASSED
    APPROVAL_GATE    PASSED            <- you approved
    IMPLEMENT        PASSED
    TEST             PASSED            ┐
    DOCS             PASSED            ├─ ran in parallel
    SECURITY_REVIEW  PASSED            ┘
    VALIDATE         PASSED            <- the join
    RELEASE_GATE     FAILED   entry gate: 2 unresolved high-risk assumption(s) [...]
    SUMMARY          BLOCKED
```

**`RELEASE_GATE FAILED` is the system working.** It means the pipeline produced
something with a serious unresolved problem and refused to let a human approve it.
The gate blocks *before* asking. Three of the live runs in `runs/` end this way, on
progressively deeper findings — a race condition, an IP-spoofing hole, a component
never wired to its endpoint.

Statuses: `PENDING` `READY` `RUNNING` `PASSED` `FAILED` `BLOCKED` `AWAITING_APPROVAL`
`SKIPPED` `STALE` `ROLLED_BACK`.

---

## Approving well

The checkpoint prints the plan, the impact analysis, the reasoning, the proposed
patch, and any high-risk assumptions — the last of these immediately above the
prompt, because that is where the eye actually lands.

There is **no default**. Enter does not mean yes. That is on purpose: a `[Y/n]`
prompt turns approval into a reflex, and a reflex is not oversight.

The reason you type goes into the decision ledger and into the run summary. It is
the only record of *why* a human said yes. Write something a reviewer could check —
"plan is grounded in the real codebase, wires the limiter into ShortLinkController,
429 via GlobalExceptionHandler; the 60/min threshold is still unverified" — not
"looks fine".

**What to actually check at `APPROVAL_GATE`:** does the plan name real files from
*this* codebase, and does it do what was asked? You are not reviewing the code —
that is TEST and SECURITY_REVIEW's job. You are checking the plan is about your repo
and solves the stated problem.

---

## Why mock-first

`--mock` is not a lesser demo. It is why the harness is testable at all.

A pipeline that reaches for a live model at every decision point cannot be unit
tested, cannot run in CI, and cannot be demonstrated on a plane. One interface, two
implementations: `AnthropicLlmClient` is the production path, `DeterministicLlmClient`
is the test path, and **both are load-bearing**.

It also has a limit worth knowing: canned responses exercise the plumbing, not the
reasoning. Two real bugs in this harness were found only by live runs and could not
have been caught by the mock. Both are written up at the end of
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Design choices you might question

| Choice | Why |
|---|---|
| No Spring | The harness is a CLI process, not a server. The shortener uses Spring because it serves HTTP. Different tool, different job. |
| No Anthropic SDK | The API is one POST. ~80 lines of `java.net.http` that are fully owned beats a dependency whose retry and timeout semantics would have to be learned before they could be defended. |
| Java 17, not 21 | Matches the target's LTS. The only cost was virtual threads; a fixed pool sized to the fan-out is fine for three IO-bound stages. |
| Two repositories | "Clear boundaries between agent logic and application logic" is a fact, not a claim. |
| Markdown, not JSON, from agents | The primary reader of a plan is a human at a checkpoint. Escaped JSON does not get reviewed, it gets skimmed. The cost is lenient parsing — stated as a limitation. |
| Records everywhere except `TaskState` | Parallel stages append to it; copy-on-write would need CAS retries or lose writes. Mutability stops at one class. |

---

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components, orchestration model,
  control flow, governance, key decisions, **known limitations**, and the two bugs
  found by running it
- [`docs/SCENARIOS.md`](docs/SCENARIOS.md) — the three required scenarios plus the
  failure paths, with evidence from real runs

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| `release version 21 not supported` | Maven is on JDK 17. Correct — `pom.xml` targets 17. |
| `ANTHROPIC_API_KEY is not set` | Export it, or use `--mock`. |
| `credit balance is too low` | Valid key, empty account. |
| `no such file` from EXPLORE | Wrong `--repo` path. |
| VS Code shows errors, `mvn` is clean | Stale language server. `Cmd+Shift+P → Java: Clean Java Language Server Workspace`. **`mvn` is the arbiter.** |
| Run seems to hang | Live model calls take 30–90s. `--mock` is instant. |
