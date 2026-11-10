# ReqFlow

A small Spring Boot REST API for the requisition-approval step of a
Source-to-Pay procurement workflow, with an AI tool-use agent on top that
triages the requisitions a policy engine can't clear on its own.

## Why this exists

This project targets a specific gap: a Source-to-Pay procurement software
company's "AI-Augmented Engineering" role requires Java/Spring Boot backend
work and rewards familiarity with agent-based/LLM-assisted systems. Rather
than retrofit an unrelated project, ReqFlow is a small, honestly-scoped
project built to show both directly:

- **The backend**: a real REST API with real business rules (auto-approval
  thresholds, budget tracking, vendor risk), not a CRUD skeleton.
- **The AI layer**: a genuine tool-use agent that calls the same REST
  endpoints a human reviewer would, to decide the cases the policy engine
  flags rather than resolves -- which is what "agentic AI across
  Source-to-Pay" means in practice, scaled down to one workflow step.

It's deliberately not a flagship-scale project. The scope matches what an
entry-level JD actually asks for.

## Domain

- **Vendor** -- name, category, a 0-100 risk score, active flag.
- **BudgetLine** -- a department/category/fiscal-year allocation with
  `remaining()` tracking what's left after committed spend.
- **PurchaseRequisition** -- a request to spend, with a full audit trail:
  who decided it (human name, or `agent:claude-triage-v1`), when, and why.
- **RequisitionEvent** -- an append-only activity log, one row per lifecycle
  transition (`SUBMITTED`, `AUTO_APPROVED`, `FLAGGED_FOR_REVIEW`,
  `OPENED_FOR_REVIEW`, `APPROVED`, `REJECTED`), each with its own permanent
  `detail` text. This is what turns the audit trail into a real process
  event log -- see "Process intelligence" below.

## The approval policy

A requisition **auto-approves** the moment it's created only if all three
hold:

1. `amount` is at or under `reqflow.policy.auto-approval-ceiling`
2. a budget line exists for its department/category/current fiscal year,
   with enough `remaining()` to cover it (and that budget is then
   committed)
3. the vendor is active and its `riskScore` is under
   `reqflow.policy.vendor-risk-ceiling`

Any failure routes it to `PENDING_REVIEW` with the specific reason recorded
directly on the requisition -- no silent flip, no re-deriving why later.
Both thresholds live in `application.yml`, not in code, so the policy can
be retuned without a redeploy.

A `PENDING_REVIEW` requisition is then decided exactly one of two ways:
a human calls `POST /api/requisitions/{id}/approve` or `/reject`, or the
triage agent (below) calls the identical endpoint. The audit trail doesn't
distinguish how the call got made, by design -- `decidedBy` is just
whoever made it. Manual `approve()` also commits budget if a line is
tracked, so a human/agent override is recorded as real spend the same way
an auto-approval is.

Before deciding, a reviewer (human or agent) can call
`POST /api/requisitions/{id}/open` -- a non-deciding transition that
records `OPENED_FOR_REVIEW` without changing the requisition's status. It
exists purely to give the event log (and therefore the analytics below) a
real split between *queue time* (flagged -> picked up) and *decision time*
(picked up -> decided), instead of one undifferentiated lump. The triage
agent calls it automatically before every decision.

## Auth

Every write (`POST`) requires HTTP Basic auth; every `GET` is open, so the
API stays easy to browse and the triage agent can always list pending
requisitions. Dev credentials (`reviewer` / `reqflow-dev`) live in
`application.yml` -- override with `REQFLOW_SECURITY_USERNAME` /
`REQFLOW_SECURITY_PASSWORD` for anything beyond local use.

## API responses

Controllers return response DTOs (`VendorResponse`, `BudgetLineResponse`,
`RequisitionResponse`), not the JPA entities directly -- so a persistence
concern like `BudgetLine`'s `@Version` optimistic-lock column never leaks
into the API, and `BudgetLineResponse` exposes `remaining` as a
first-class field instead of making callers recompute
`allocatedAmount - spentAmount` themselves. `RequisitionResponse` also
carries the requisition's full `events` list (`RequisitionEventResponse`:
activity, detail, occurredAt) -- the event log isn't a side table nobody
reads, it's on every response the same surface the agent and the analytics
script both already call.

## The AI triage agent

`agent/triage_agent.py` polls `GET /api/requisitions?status=PENDING_REVIEW`
and, for each one, calls `POST .../open` (recording `OPENED_FOR_REVIEW`)
before deciding to approve or reject by calling the same `approve`/`reject`
endpoints a human reviewer uses. There is no separate "agent API" -- the
agent is just another caller of the REST surface, wired up as Anthropic
tool-use functions (`get_vendor`, `approve_requisition`,
`reject_requisition`).

Two modes, same code path:

- **Live** (`ANTHROPIC_API_KEY` set): Claude actually reasons about each
  flagged requisition and decides.
- **Mock** (`--mock`, or no key present): a small deterministic policy
  stands in for the model, so the full pipeline runs and is demoable with
  no secret required.

```bash
cd agent
pip install -r requirements.txt   # only needed for live mode
python triage_agent.py --mock     # or: python triage_agent.py (live, if ANTHROPIC_API_KEY is set)
```

## Process intelligence

`analytics/process_intelligence.py` reads the `RequisitionEvent` activity
log through the REST API the way a process-mining tool reads an event log,
and turns it into a proof-of-value-style summary:

- **True variant discovery** -- requisitions are grouped by their actual
  activity *sequence* (e.g. `SUBMITTED -> AUTO_APPROVED` vs. `SUBMITTED ->
  FLAGGED_FOR_REVIEW -> OPENED_FOR_REVIEW -> APPROVED`), not just their
  terminal status. An earlier version of this script only had two
  timestamps to work with and grouped by status, which a round of
  structured review correctly flagged as not a real variant analysis; the
  event log is the fix, not a renaming of the same limitation.
- **Decomposed cycle time** -- when a case was explicitly opened for review
  (`POST .../open`, which both the triage agent and a real reviewer UI
  would call before deciding), total cycle time splits into *queue time*
  (flagged -> picked up) and *decision time* (picked up -> decided).
  Without an open event, only the lump total is available -- disclosed,
  not interpolated.
- **A durable flag-reason breakdown covering every flagged case** --
  because each `FLAGGED_FOR_REVIEW` event keeps its own detail text
  permanently, the original flag reason survives even after a later
  decision overwrites the requisition's own `decisionReason` field, so the
  breakdown isn't limited to the still-open backlog the way it would be
  reading `decisionReason` alone.
- **A quantified, disclosed-assumption business-value estimate** --
  reviewer-hours and cost avoided by auto-approval, plus an at-scale
  projection so a small demo run's absolute numbers aren't mistaken for the
  finding itself.

It's a REST client, nothing more -- same relationship to the API that the
triage agent has. No new endpoint was added for this script specifically;
it just reads the event log the API already returns on every requisition.

```bash
cd analytics
pip install -r requirements.txt   # only needed for --chart
python process_intelligence.py                                    # console report
python process_intelligence.py --out report.md --chart chart.png  # + files
```

`sample_report.md` / `sample_chart.png` are checked-in output from a small
local demo run, so the report's shape is visible without running anything.

## Running it

Requires Java 21 and Maven.

```bash
mvn spring-boot:run
```

Starts on `http://localhost:8080` with an in-memory H2 database seeded
(`src/main/resources/data.sql`) with sample vendors, budget lines, and a
mix of requisitions -- one clean auto-approval and three flagged for
different reasons -- so the API has something to look at immediately.
H2's console is at `/h2-console` (JDBC URL `jdbc:h2:mem:reqflow`).

```bash
# list everything pending review
curl http://localhost:8080/api/requisitions?status=PENDING_REVIEW

# create one (auto-approves or flags depending on the policy) -- POSTs need auth
curl -X POST http://localhost:8080/api/requisitions \
  -u reviewer:reqflow-dev \
  -H 'Content-Type: application/json' \
  -d '{"requesterName":"Jane Doe","department":"Engineering","vendorId":2,
       "category":"it_equipment","amount":1200.00,"justification":"replacement monitors"}'

# approve a flagged one
curl -X POST http://localhost:8080/api/requisitions/2/approve \
  -u reviewer:reqflow-dev \
  -H 'Content-Type: application/json' \
  -d '{"decidedBy":"jane.doe","reason":"reviewed, acceptable risk for this vendor relationship"}'
```

## Tests

```bash
mvn test
```

32 tests: 9 service-layer unit tests covering every branch of the
auto-approval policy (ceiling, vendor risk, budget headroom, re-decision
guard, unknown-vendor fast-fail), 4 persistence tests that deliberately
skip the test transaction wrapper to force a fresh read (these are what
caught a real bug -- see below, and include a dedicated test for the
`@Version` optimistic-lock conflict), 6 service-layer tests covering the
`RequisitionEvent` log and `openForReview` (including one that specifically
proves the original flag reason survives on the event log after a later
decision overwrites `decisionReason` -- the whole reason the event log
exists), and 13 MockMvc integration tests against the real H2-backed,
Spring-Security-protected REST surface, including the `/open` endpoint and
the event log showing up in `RequisitionResponse`.

## Architecture

```
Client / Human Reviewer          AI Triage Agent (agent/triage_agent.py)
        |                                    |
        |            REST (JSON)             |
        v                                    v
            Spring Boot REST API (controller/)
                        |
            RequisitionService (service/)
             - auto-approval policy
             - approve / reject / openForReview
                        |              \
         JPA repositories (repository/)  -> RequisitionEventService
                        |                       (append-only activity log)
                        v                       |
                       H2  <---------------------
                                                 |
                              analytics/process_intelligence.py
                              (reads the event log via the same REST API)
```

## Build process

Built with Claude Code, iterating: domain model and repositories first,
then the policy-bearing service layer, then controllers and a global
exception handler, verified by running the app and curling every branch of
the policy by hand, then unit and integration tests, then the agent --
tested live against the running API in mock mode before being documented
here. That iterative, AI-assisted build process is itself the kind of
"AI-aware development" workflow the target role is asking for.

A follow-up review pass (three parallel reviewers reading the actual code,
not a rubber stamp) caught a real bug: the auto-approval policy mutated a
`BudgetLine`'s spent amount without the save ever reaching the database --
masked by a test that wrapped everything in one transaction. Fixed with
`@Transactional` + an explicit save, a `@Version` column so two
concurrent requisitions against the same budget line can't both slip
past the headroom check, a consistency fix so manual approval commits
budget the same way auto-approval does, and regression tests structured
specifically to not hide the bug the way the original one did. The same
pass flagged (and this iteration fixed) two scope gaps: response DTOs
instead of returning JPA entities, and HTTP Basic auth on every write.
