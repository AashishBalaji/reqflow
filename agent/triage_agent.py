#!/usr/bin/env python3
"""
ReqFlow AI triage agent.

A tool-use agent that polls ReqFlow's REST API for requisitions a human
reviewer would otherwise have to open one by one, and decides each one by
calling the same approve/reject endpoints a human uses -- there is no
separate "agent API". The tools below are just thin wrappers over
GET/POST calls against the Spring Boot service; the agent's only job is
to reason about each requisition and call the right tool with a stated
reason, which lands in the audit trail (decidedBy/decisionReason) exactly
like a human decision would.

This mirrors the "agentic AI engine powering autonomous workflows across
Source-to-Pay" framing Zycus uses for its own Merlin AI product, scaled
down to one well-scoped workflow step: requisition triage.

Two modes:
  * LIVE mode, when ANTHROPIC_API_KEY is set: uses the Anthropic Messages
    API with tool use, letting the model decide for itself, requisition
    by requisition, which tool to call and what reasoning to record.
  * MOCK mode, when no key is present: runs the exact same tool-calling
    loop, but a small deterministic policy stands in for the model. This
    keeps the whole pipeline runnable and demoable (`python triage_agent.py
    --mock`, or just run it with no key set) without requiring a secret,
    while still exercising the real REST contract end to end.

Usage:
    python triage_agent.py                  # live if ANTHROPIC_API_KEY set, else mock
    python triage_agent.py --mock           # force mock mode
    python triage_agent.py --base-url http://localhost:8080
"""

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any


DEFAULT_BASE_URL = "http://localhost:8080"
AGENT_IDENTITY = "agent:claude-triage-v1"

# ReqFlow requires HTTP Basic auth on every POST (SecurityConfig); GET
# stays open. Override with REQFLOW_API_USERNAME/REQFLOW_API_PASSWORD --
# these default to the same dev credentials application.yml ships with.
DEFAULT_API_USERNAME = os.environ.get("REQFLOW_API_USERNAME", "reviewer")
DEFAULT_API_PASSWORD = os.environ.get("REQFLOW_API_PASSWORD", "reqflow-dev")


# ---------------------------------------------------------------------------
# Tools: each one is a direct, auditable call to a ReqFlow REST endpoint.
# These are exactly the functions handed to the model as tool definitions
# in live mode, and exactly the functions the mock policy calls in mock mode
# -- one code path, two different "brains" deciding when to call them.
# ---------------------------------------------------------------------------

class ReqFlowClient:
    def __init__(self, base_url: str, username: str = DEFAULT_API_USERNAME,
                 password: str = DEFAULT_API_PASSWORD):
        self.base_url = base_url.rstrip("/")
        token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        self._auth_header = f"Basic {token}"

    def _request(self, method: str, path: str, body: dict | None = None) -> Any:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"}
        if method != "GET":  # GET is open; only mutating calls need auth
            headers["Authorization"] = self._auth_header
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {detail}") from e

    def list_pending_requisitions(self) -> list[dict]:
        """Tool: list every requisition currently awaiting a decision."""
        return self._request("GET", "/api/requisitions?status=PENDING_REVIEW")

    def get_vendor(self, vendor_id: int) -> dict:
        """Tool: fetch a vendor's details (name, category, risk score, active)."""
        return self._request("GET", f"/api/vendors/{vendor_id}")

    def approve_requisition(self, requisition_id: int, reason: str) -> dict:
        """Tool: approve a pending requisition, recording why."""
        return self._request("POST", f"/api/requisitions/{requisition_id}/approve",
                              {"decidedBy": AGENT_IDENTITY, "reason": reason})

    def reject_requisition(self, requisition_id: int, reason: str) -> dict:
        """Tool: reject a pending requisition, recording why."""
        return self._request("POST", f"/api/requisitions/{requisition_id}/reject",
                              {"decidedBy": AGENT_IDENTITY, "reason": reason})


TOOL_DEFINITIONS = [
    {
        "name": "get_vendor",
        "description": "Fetch a vendor's details, including its risk score (0-100) and whether it is active.",
        "input_schema": {
            "type": "object",
            "properties": {"vendor_id": {"type": "integer"}},
            "required": ["vendor_id"],
        },
    },
    {
        "name": "approve_requisition",
        "description": "Approve a pending requisition. Use when the vendor and amount look acceptable "
                        "on human/agent review, even though the automatic policy flagged it.",
        "input_schema": {
            "type": "object",
            "properties": {
                "requisition_id": {"type": "integer"},
                "reason": {"type": "string", "description": "Why this is being approved."},
            },
            "required": ["requisition_id", "reason"],
        },
    },
    {
        "name": "reject_requisition",
        "description": "Reject a pending requisition. Use when the vendor risk, amount, or justification "
                        "doesn't hold up on review.",
        "input_schema": {
            "type": "object",
            "properties": {
                "requisition_id": {"type": "integer"},
                "reason": {"type": "string", "description": "Why this is being rejected."},
            },
            "required": ["requisition_id", "reason"],
        },
    },
]

SYSTEM_PROMPT = """You are a procurement triage assistant for ReqFlow. You will be shown a \
purchase requisition that an automatic policy could not clear on its own (it already failed a \
ceiling, budget, or vendor-risk check -- that reason is given to you). Decide whether a human \
would reasonably approve or reject it, using the get_vendor tool if you want more detail on the \
vendor first. Always call either approve_requisition or reject_requisition exactly once, with a \
specific, concrete reason grounded in the data you were given -- not a generic justification. \
Be conservative: when a vendor is inactive, has a very high risk score (80+), or the requisition's \
own justification is vague or implausible for its amount, reject it."""


@dataclass
class Decision:
    requisition_id: int
    action: str  # "approve" | "reject"
    reason: str


# ---------------------------------------------------------------------------
# Mock mode: deterministic stand-in policy, no API key required.
# ---------------------------------------------------------------------------

def mock_decide(requisition: dict, client: ReqFlowClient) -> Decision:
    vendor = client.get_vendor(requisition["vendorId"])
    rid = requisition["id"]

    if not vendor["active"]:
        return Decision(rid, "reject", f"Vendor '{vendor['name']}' is inactive; cannot proceed.")
    if vendor["riskScore"] >= 80:
        return Decision(rid, "reject",
                         f"Vendor '{vendor['name']}' risk score {vendor['riskScore']} is too high "
                         f"to approve even on manual review.")
    if len(requisition.get("justification", "").strip()) < 8:
        return Decision(rid, "reject", "Justification is too vague to approve on review.")

    return Decision(rid, "approve",
                     f"Vendor '{vendor['name']}' risk score {vendor['riskScore']} is acceptable on "
                     f"manual review; justification is specific and amount is reasonable for the request.")


# ---------------------------------------------------------------------------
# Live mode: real Anthropic tool-use loop.
# ---------------------------------------------------------------------------

def live_decide(requisition: dict, client: ReqFlowClient) -> Decision:
    import anthropic  # imported lazily so mock mode never needs the package installed

    api_client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from the environment
    tool_impls = {
        "get_vendor": lambda i: client.get_vendor(i["vendor_id"]),
        "approve_requisition": lambda i: client.approve_requisition(i["requisition_id"], i["reason"]),
        "reject_requisition": lambda i: client.reject_requisition(i["requisition_id"], i["reason"]),
    }

    messages = [{
        "role": "user",
        "content": f"Here is the pending requisition:\n{json.dumps(requisition, indent=2)}",
    }]
    decision: Decision | None = None

    for _ in range(5):  # a small bound so a confused model can't loop forever
        response = api_client.messages.create(
            model="claude-sonnet-4-5",
            max_tokens=1024,
            system=SYSTEM_PROMPT,
            tools=TOOL_DEFINITIONS,
            messages=messages,
        )
        messages.append({"role": "assistant", "content": response.content})

        tool_uses = [b for b in response.content if b.type == "tool_use"]
        if not tool_uses:
            break

        tool_results = []
        for call in tool_uses:
            result = tool_impls[call.name](call.input)
            tool_results.append({"type": "tool_result", "tool_use_id": call.id,
                                  "content": json.dumps(result)})
            if call.name in ("approve_requisition", "reject_requisition"):
                decision = Decision(call.input["requisition_id"],
                                     "approve" if call.name == "approve_requisition" else "reject",
                                     call.input["reason"])
        messages.append({"role": "user", "content": tool_results})

        if response.stop_reason != "tool_use":
            break

    if decision is None:
        raise RuntimeError(f"Model never called a decision tool for requisition {requisition['id']}")
    return decision


# ---------------------------------------------------------------------------

def run(base_url: str, force_mock: bool, username: str, password: str) -> int:
    client = ReqFlowClient(base_url, username, password)
    use_mock = force_mock or not os.environ.get("ANTHROPIC_API_KEY")
    mode = "MOCK (deterministic, no API key)" if use_mock else "LIVE (Anthropic tool use)"
    print(f"ReqFlow triage agent starting in {mode} mode against {base_url}")

    try:
        pending = client.list_pending_requisitions()
    except Exception as e:
        print(f"Could not reach ReqFlow at {base_url}: {e}", file=sys.stderr)
        print("Is the app running? Try: mvn spring-boot:run", file=sys.stderr)
        return 1

    if not pending:
        print("No pending requisitions. Nothing to triage.")
        return 0

    print(f"Found {len(pending)} pending requisition(s).\n")
    decide = mock_decide if use_mock else live_decide

    for requisition in pending:
        rid = requisition["id"]
        print(f"--- Requisition #{rid}: {requisition['requesterName']} / "
              f"{requisition['department']} / {requisition['amount']} ---")
        print(f"  Flagged because: {requisition.get('decisionReason')}")

        decision = decide(requisition, client)
        if decision.action == "approve":
            client.approve_requisition(decision.requisition_id, decision.reason)
        else:
            client.reject_requisition(decision.requisition_id, decision.reason)

        print(f"  Decision: {decision.action.upper()} - {decision.reason}\n")

    return 0


def main():
    parser = argparse.ArgumentParser(description="ReqFlow AI triage agent")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                         help=f"ReqFlow API base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--mock", action="store_true",
                         help="Force mock mode even if ANTHROPIC_API_KEY is set")
    parser.add_argument("--username", default=DEFAULT_API_USERNAME,
                         help="HTTP Basic auth username for POST calls (default: reviewer, or $REQFLOW_API_USERNAME)")
    parser.add_argument("--password", default=DEFAULT_API_PASSWORD,
                         help="HTTP Basic auth password for POST calls (default: reqflow-dev, or $REQFLOW_API_PASSWORD)")
    args = parser.parse_args()
    sys.exit(run(args.base_url, args.mock, args.username, args.password))


if __name__ == "__main__":
    main()
