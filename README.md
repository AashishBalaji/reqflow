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
whoever made it.

## The AI triage agent

`agent/triage_agent.py` polls `GET /api/requisitions?status=PENDING_REVIEW`
and, for each one, decides to approve or reject by calling the same
`approve`/`reject` endpoints a human reviewer uses. There is no separate
"agent API" -- the agent is just another caller of the REST surface, wired
up as Anthropic tool-use functions (`get_vendor`, `approve_requisition`,
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

# create one (auto-approves or flags depending on the policy)
curl -X POST http://localhost:8080/api/requisitions \
  -H 'Content-Type: application/json' \
  -d '{"requesterName":"Jane Doe","department":"Engineering","vendorId":2,
       "category":"it_equipment","amount":1200.00,"justification":"replacement monitors"}'

# approve a flagged one
curl -X POST http://localhost:8080/api/requisitions/2/approve \
  -H 'Content-Type: application/json' \
  -d '{"decidedBy":"jane.doe","reason":"reviewed, acceptable risk for this vendor relationship"}'
```

## Tests

```bash
mvn test
```

15 tests: 9 service-layer unit tests covering every branch of the
auto-approval policy (ceiling, vendor risk, budget headroom, re-decision
guard, unknown-vendor fast-fail) and 6 MockMvc integration tests against
the real H2-backed REST surface.

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
             - approve / reject
                        |
         JPA repositories (repository/) -> H2
```

## Build process

Built with Claude Code, iterating: domain model and repositories first,
then the policy-bearing service layer, then controllers and a global
exception handler, verified by running the app and curling every branch of
the policy by hand, then unit and integration tests, then the agent --
tested live against the running API in mock mode before being documented
here. That iterative, AI-assisted build process is itself the kind of
"AI-aware development" workflow the target role is asking for.
