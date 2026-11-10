"""
Process intelligence over ReqFlow's own event log.

Every requisition now carries a real activity log (RequisitionEvent, one
row per state transition: SUBMITTED, AUTO_APPROVED, FLAGGED_FOR_REVIEW,
OPENED_FOR_REVIEW, APPROVED, REJECTED), not just the two summary
timestamps (createdAt/decidedAt) the first version of this script was
limited to. This script reads that log the way a process-mining tool
would:

- **True variant discovery** -- requisitions are grouped by their actual
  activity sequence (e.g. "SUBMITTED -> AUTO_APPROVED" vs. "SUBMITTED ->
  FLAGGED_FOR_REVIEW -> OPENED_FOR_REVIEW -> APPROVED"), not just their
  terminal status.
- **Decomposed cycle time** -- when a case was explicitly opened for
  review (POST /api/requisitions/{id}/open, which both a human UI and the
  triage agent call before deciding), total cycle time splits into
  queue-time (flagged -> picked up) and decision-time (picked up ->
  decided). Without an open event, only the lump total is available --
  disclosed, not hidden.
- **A durable flag-reason breakdown** -- because each FLAGGED_FOR_REVIEW
  event keeps its own detail text, the original flag reason survives even
  after a later decision overwrites the requisition's own decisionReason
  field. The breakdown below covers every flagged case, not just the
  still-open ones the two-timestamp version was limited to.
- **A quantified, disclosed-assumption business-value estimate** --
  reviewer-hours and cost avoided by auto-approval, plus an at-scale
  projection so a small demo run's absolute numbers aren't mistaken for
  the finding itself.
- **A conformance check** against the one control-flow rule this process
  actually commits to (every case starts with SUBMITTED; a flagged case
  that reaches a decision should have been opened for review first), and
  a decisions-by-reviewer cut over the decidedBy field every requisition
  already carries.
- **A process map** -- a directly-follows graph (nodes = activities,
  edges = observed transitions, weighted by frequency) -- the standard
  first visual in process mining, built straight from the same event log.

It is a client of the REST API, nothing more -- the same relationship the
AI triage agent (agent/triage_agent.py) has to ReqFlow. No new endpoint
was added for this script specifically; it just reads the event log the
API already returns on every requisition.

Usage:
    python process_intelligence.py [--base-url http://localhost:8080]
    python process_intelligence.py --out report.md    # also write a report
    python process_intelligence.py --out report.md --chart chart.png --process-map map.png
"""

import argparse
import json
import re
import statistics
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

REQUEST_TIMEOUT_SECONDS = 10

# Jackson serializes a Java Instant with up to 9 fractional-second digits
# (nanoseconds), e.g. "...123456789Z". datetime.fromisoformat only accepts
# arbitrary fractional-digit counts from Python 3.11 onward -- on an older
# interpreter it raises ValueError on a perfectly ordinary timestamp from
# this API. Truncating to microseconds (6 digits, what datetime actually
# stores) sidesteps that version dependence entirely.
_FRACTIONAL_SECONDS = re.compile(r"(\.\d{6})\d*(?=(Z|[+-]\d{2}:?\d{2})?$)")

DEFAULT_BASE_URL = "http://localhost:8080"

TERMINAL_ACTIVITIES = {"AUTO_APPROVED", "APPROVED", "REJECTED"}
SEQUENCE_SEPARATOR = " → "  # "->"
NO_EVENT_LOG_LABEL = "Unknown sequence (no event log)"

# Disclosed assumptions behind the ROI estimate below -- a real engagement
# would pull these from the customer's own numbers; here they stand in for
# "a plausible mid-market procurement review."
ASSUMED_MANUAL_REVIEW_MINUTES = 8.0
ASSUMED_REVIEWER_HOURLY_COST_USD = 25.0

# A demo run only has as many requisitions as someone bothered to create by
# hand, which makes the absolute ROI figure look trivial even when the rate
# behind it is real. This projects that same rate onto a disclosed, plainly
# hypothetical production volume, so the number isn't mistaken for a small
# finding rather than a small sample.
ASSUMED_MONTHLY_VOLUME_AT_SCALE = 2000

# Keyword probes used to bucket a FLAGGED_FOR_REVIEW event's own detail text
# into a coarse category. Unlike the previous version of this script, this
# now applies to every flagged case (decided or not): the category comes
# from the event's own permanent detail text, not from the requisition's
# current decisionReason, which a later decision overwrites.
#
# Order matters and probe text must be specific. The vendor-risk message
# ("...risk score %d is at or above the CEILING of %d") also contains the
# word "ceiling" -- an earlier version of this list probed for the bare
# word "ceiling" first and so silently miscategorized every vendor-risk
# flag as an amount-ceiling flag. "risk score" is checked first, and the
# amount probe is the specific phrase "auto-approval ceiling" (the exact
# wording RequisitionService uses for that case and only that case), so
# neither probe can shadow the other regardless of order.
FLAG_CATEGORY_PROBES = [
    ("risk score", "Vendor risk too high / inactive"),
    ("auto-approval ceiling", "Amount over auto-approval ceiling"),
    ("budget", "Budget headroom insufficient or untracked"),
]


def fetch_requisitions(base_url: str) -> list[dict]:
    with urllib.request.urlopen(
        f"{base_url}/api/requisitions", timeout=REQUEST_TIMEOUT_SECONDS
    ) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _parse_instant(value: str | None) -> datetime | None:
    if value is None:
        return None
    value = value.replace("Z", "+00:00")
    value = _FRACTIONAL_SECONDS.sub(r"\1", value)  # ns -> us, for Python < 3.11
    return datetime.fromisoformat(value)


def _sorted_events(req: dict) -> list[dict]:
    # Stable sort on occurredAt ALONE. Sibling events written in the same
    # service call (e.g. SUBMITTED then AUTO_APPROVED) can land on the exact
    # same millisecond, and the API already returns them in true causal
    # order (ORDER BY occurred_at, id). A tuple key that adds "activity" as
    # a tiebreaker would re-sort those ties alphabetically -- AUTO_APPROVED
    # before SUBMITTED -- which silently scrambles the sequence. Python's
    # sort is stable, so sorting on occurredAt alone preserves the API's
    # already-correct order on every tie.
    events = req.get("events") or []
    return sorted(events, key=lambda e: _parse_instant(e["occurredAt"]) or datetime.min)


def _minutes_between(a: datetime | None, b: datetime | None) -> float | None:
    """Minutes from a to b, or None if either is missing or the result
    would be negative (bad data/clock skew) -- a negative duration
    averaged in silently would be a textbook "quietly wrong number" in a
    report meant to be taken at face value."""
    if a is None or b is None:
        return None
    minutes = (b - a).total_seconds() / 60.0
    return minutes if minutes >= 0 else None


@dataclass
class CaseProfile:
    """What this script actually needs out of one requisition's event log,
    computed once and reused by every section of the report."""
    sequence: str
    is_decided: bool
    flag_category: str | None       # None if never flagged (straight-through)
    total_minutes: float | None     # SUBMITTED -> terminal event
    queue_minutes: float | None     # SUBMITTED -> OPENED_FOR_REVIEW (None if never opened)
    decision_minutes: float | None  # OPENED_FOR_REVIEW -> terminal (None if never opened)
    backlog_age_minutes: float | None  # now - last event, only set when not yet decided
    bad_timestamps: bool            # decided, but a duration came out negative
    decided_by: str | None          # who/what made the terminal call, for an organizational cut
    conformance_issue: str | None   # None if the case matches the expected control flow


def check_conformance(events: list[dict], flagged_event: dict | None,
                       opened_event: dict | None, terminal_event: dict | None) -> str | None:
    """A minimal, hand-rolled conformance check against the one control-flow
    rule this process actually commits to: SUBMITTED is always first, and a
    flagged case that reaches a decision is expected to have been picked up
    (OPENED_FOR_REVIEW) first -- that's the whole reason the endpoint exists.
    The API doesn't *enforce* that ordering (approve/reject only require
    PENDING_REVIEW status, not a prior open event), so a case can legally
    skip straight from FLAGGED_FOR_REVIEW to a decision -- e.g. a reviewer
    calling the API directly instead of through the expected UI/agent path.
    That's a real deviation worth surfacing, not a crash: the case is still
    valid data, it just didn't follow the expected path, which is exactly
    what conformance checking means in process mining (checking an event
    log against an expected model, not just describing what happened)."""
    if not events or events[0]["activity"] != "SUBMITTED":
        return "Does not start with SUBMITTED"
    if flagged_event is not None and terminal_event is not None and opened_event is None:
        return "Decided without ever being opened for review (bypassed the expected flag -> open -> decide path)"
    return None


def profile_case(req: dict, now: datetime) -> CaseProfile:
    events = _sorted_events(req)
    if not events:
        # Legacy/fallback path: no event log at all. Shouldn't happen for
        # anything created through RequisitionService, but a direct SQL
        # insert (or an older API version) could still produce this.
        return CaseProfile(NO_EVENT_LOG_LABEL, req.get("decidedAt") is not None,
                            None, None, None, None, None, False,
                            req.get("decidedBy"), "No event log at all")

    sequence = SEQUENCE_SEPARATOR.join(e["activity"] for e in events)
    submitted_at = _parse_instant(events[0]["occurredAt"])
    opened_event = next((e for e in events if e["activity"] == "OPENED_FOR_REVIEW"), None)
    opened_at = _parse_instant(opened_event["occurredAt"]) if opened_event else None
    terminal_event = events[-1] if events[-1]["activity"] in TERMINAL_ACTIVITIES else None
    terminal_at = _parse_instant(terminal_event["occurredAt"]) if terminal_event else None

    flagged_event = next((e for e in events if e["activity"] == "FLAGGED_FOR_REVIEW"), None)
    flag_category = classify_flag_reason(flagged_event["detail"]) if flagged_event else None

    total_minutes = _minutes_between(submitted_at, terminal_at) if terminal_event else None
    if opened_at is not None and terminal_at is not None:
        queue_minutes = _minutes_between(submitted_at, opened_at)
        decision_minutes = _minutes_between(opened_at, terminal_at)
    else:
        queue_minutes = None
        decision_minutes = total_minutes  # no open event: can't split, only the lump sum

    bad_timestamps = terminal_event is not None and total_minutes is None
    backlog_age_minutes = None
    if terminal_event is None:
        last_at = _parse_instant(events[-1]["occurredAt"])
        if last_at is not None:
            backlog_age_minutes = max((now - last_at).total_seconds() / 60.0, 0.0)

    conformance_issue = check_conformance(events, flagged_event, opened_event, terminal_event)

    return CaseProfile(sequence, terminal_event is not None, flag_category,
                        total_minutes, queue_minutes, decision_minutes,
                        backlog_age_minutes, bad_timestamps,
                        req.get("decidedBy"), conformance_issue)


def classify_flag_reason(detail: str | None) -> str:
    text = (detail or "").lower()
    for probe, label in FLAG_CATEGORY_PROBES:
        if probe in text:
            return label
    return "Other / unclassified"


@dataclass
class VariantStats:
    label: str
    count: int = 0
    total_minutes: list[float] = field(default_factory=list)
    queue_minutes: list[float] = field(default_factory=list)
    decision_minutes: list[float] = field(default_factory=list)

    @property
    def avg_total_minutes(self) -> float | None:
        return statistics.mean(self.total_minutes) if self.total_minutes else None

    @property
    def avg_queue_minutes(self) -> float | None:
        return statistics.mean(self.queue_minutes) if self.queue_minutes else None

    @property
    def avg_decision_minutes(self) -> float | None:
        return statistics.mean(self.decision_minutes) if self.decision_minutes else None


def build_report(requisitions: list[dict]) -> dict:
    now = datetime.now(timezone.utc)
    variants: dict[str, VariantStats] = {}
    flag_reason_counts: dict[str, int] = {}
    backlog_ages_minutes: list[float] = []
    excluded_bad_timestamps = 0
    auto_approved = 0
    conformance_issues: list[tuple[int | None, str]] = []
    decisions_by_reviewer: dict[str, int] = {}
    cycle_time_by_department: dict[str, list[float]] = {}

    for req in requisitions:
        profile = profile_case(req, now)
        stats = variants.setdefault(profile.sequence, VariantStats(profile.sequence))
        stats.count += 1

        if profile.total_minutes is not None:
            stats.total_minutes.append(profile.total_minutes)
        if profile.queue_minutes is not None:
            stats.queue_minutes.append(profile.queue_minutes)
        if profile.decision_minutes is not None:
            stats.decision_minutes.append(profile.decision_minutes)
        if profile.bad_timestamps:
            excluded_bad_timestamps += 1
        if profile.backlog_age_minutes is not None:
            backlog_ages_minutes.append(profile.backlog_age_minutes)
        if profile.flag_category is not None:
            flag_reason_counts[profile.flag_category] = flag_reason_counts.get(profile.flag_category, 0) + 1
        if req.get("status") == "AUTO_APPROVED":
            auto_approved += 1
        if profile.conformance_issue is not None:
            conformance_issues.append((req.get("id"), profile.conformance_issue))
        if profile.is_decided and profile.decided_by:
            decisions_by_reviewer[profile.decided_by] = decisions_by_reviewer.get(profile.decided_by, 0) + 1
        department = req.get("department")
        if department and profile.total_minutes is not None:
            cycle_time_by_department.setdefault(department, []).append(profile.total_minutes)

    # (count, avg minutes) per department -- illustrative only at demo
    # scale; a real engagement would need a far larger n before reading
    # anything causal into a department difference, which the report text
    # says explicitly rather than implying a finding from n=1 or n=2.
    department_cycle_time = {
        dept: (len(values), statistics.mean(values))
        for dept, values in cycle_time_by_department.items()
    }

    total = len(requisitions)
    hours_saved = auto_approved * ASSUMED_MANUAL_REVIEW_MINUTES / 60.0
    cost_avoided = hours_saved * ASSUMED_REVIEWER_HOURLY_COST_USD

    auto_approval_rate = auto_approved / total if total else 0.0
    scaled_auto_approved = round(auto_approval_rate * ASSUMED_MONTHLY_VOLUME_AT_SCALE)
    scaled_hours_saved = scaled_auto_approved * ASSUMED_MANUAL_REVIEW_MINUTES / 60.0
    scaled_cost_avoided = scaled_hours_saved * ASSUMED_REVIEWER_HOURLY_COST_USD

    return {
        "total": total,
        "variants": variants,
        "auto_approved": auto_approved,
        "flag_reason_counts": flag_reason_counts,
        "backlog_ages_minutes": backlog_ages_minutes,
        "hours_saved": hours_saved,
        "cost_avoided": cost_avoided,
        "auto_approval_rate": auto_approval_rate,
        "scaled_auto_approved": scaled_auto_approved,
        "scaled_hours_saved": scaled_hours_saved,
        "scaled_cost_avoided": scaled_cost_avoided,
        "excluded_bad_timestamps": excluded_bad_timestamps,
        "conformance_issues": conformance_issues,
        "decisions_by_reviewer": decisions_by_reviewer,
        "department_cycle_time": department_cycle_time,
    }


def _fmt_minutes(value: float | None) -> str:
    return f"{value:,.1f} min" if value is not None else "--"


def print_report(report: dict) -> None:
    total = report["total"]
    print(f"\nReqFlow process intelligence -- {total} requisitions in scope\n")
    print(f"{'Variant (activity sequence)':46} {'Count':>6} {'Share':>7} "
          f"{'Total':>10} {'Queue':>10} {'Decision':>10}")
    for label, stats in sorted(report["variants"].items(), key=lambda kv: -kv[1].count):
        share = f"{100 * stats.count / total:.0f}%" if total else "--"
        print(f"{label:46} {stats.count:>6} {share:>7} "
              f"{_fmt_minutes(stats.avg_total_minutes):>10} "
              f"{_fmt_minutes(stats.avg_queue_minutes):>10} "
              f"{_fmt_minutes(stats.avg_decision_minutes):>10}")
    print(
        "\n  Queue/Decision only populate for cases with an explicit "
        "OPENED_FOR_REVIEW event (both the triage agent and a real reviewer "
        "UI would call POST .../open before deciding); without it, Total is "
        "all that can be reported -- disclosed, not interpolated."
    )

    if report["flag_reason_counts"]:
        print("\nAll flagged cases, by original flag reason (decided or still open):")
        for label, count in sorted(report["flag_reason_counts"].items(), key=lambda kv: -kv[1]):
            print(f"  {label:42} {count:>4}")
        ages = report["backlog_ages_minutes"]
        if ages:
            print(f"  (average age of the {len(ages)} case(s) still awaiting a decision: "
                  f"{statistics.mean(ages):,.1f} min)")

    print("\nEstimated business value of auto-approval, this run:")
    print(f"  {report['auto_approved']} requisitions never reached a human reviewer")
    print(
        f"  ~{report['hours_saved']:.2f} reviewer-hours avoided "
        f"(at an assumed {ASSUMED_MANUAL_REVIEW_MINUTES:.0f} min/case)"
    )
    print(
        f"  ~${report['cost_avoided']:.2f} avoided at an assumed "
        f"${ASSUMED_REVIEWER_HOURLY_COST_USD:.0f}/hr reviewer cost"
    )
    print(
        "\n  (Assumptions are deliberately disclosed and swappable -- a real "
        "engagement would use the customer's own review-time and labor-cost figures.)"
    )
    print(
        f"\n  At the same {report['auto_approval_rate']:.0%} auto-approval rate but a "
        f"production volume of {ASSUMED_MONTHLY_VOLUME_AT_SCALE:,}/month (disclosed, "
        f"hypothetical): ~{report['scaled_auto_approved']:,} auto-approved, "
        f"~{report['scaled_hours_saved']:.1f} reviewer-hours and "
        f"~${report['scaled_cost_avoided']:,.0f}/month avoided."
    )

    if report["excluded_bad_timestamps"]:
        print(
            f"\n  Note: {report['excluded_bad_timestamps']} decided requisition(s) had "
            "an out-of-order event timestamp and were excluded from cycle-time stats as bad data."
        )

    if report["decisions_by_reviewer"]:
        print("\nDecisions by reviewer (human name or agent:claude-triage-v1):")
        for reviewer, count in sorted(report["decisions_by_reviewer"].items(), key=lambda kv: -kv[1]):
            print(f"  {reviewer:42} {count:>4}")

    if report["department_cycle_time"]:
        print("\nAvg total cycle time by department:")
        for dept, (count, avg) in sorted(report["department_cycle_time"].items(), key=lambda kv: -kv[1][1]):
            print(f"  {dept:30} {count:>3} case(s)   {_fmt_minutes(avg)}")
        print(
            "  (Illustrative only at this sample size -- a real engagement would "
            "need far more cases per department before reading a difference as a "
            "finding rather than noise.)"
        )

    print("\nConformance check (against the expected SUBMITTED -> ... -> decided flow):")
    issues = report["conformance_issues"]
    if issues:
        print(f"  {len(issues)} case(s) deviated from the expected control flow:")
        for req_id, issue in issues:
            print(f"    requisition {req_id}: {issue}")
    else:
        print(f"  All {report['total']} case(s) conform -- no deviations found.")


# A fixed, hand-placed layout for the six known activities. A real
# process-discovery algorithm would infer node positions from the graph
# structure itself; at exactly six known activities, a hand layout tuned
# for this specific process reads more clearly than a generic
# force-directed layout would, and needs no extra dependency (no
# networkx/graphviz) beyond matplotlib, which this project already uses.
_DFG_NODE_POSITIONS = {
    "SUBMITTED": (0.0, 0.5),
    "FLAGGED_FOR_REVIEW": (1.3, 0.1),
    "AUTO_APPROVED": (1.3, 0.9),
    "OPENED_FOR_REVIEW": (2.6, 0.1),
    "APPROVED": (3.9, 0.3),
    "REJECTED": (3.9, -0.1),
}


def build_dfg(requisitions: list[dict]) -> dict[tuple[str, str], int]:
    """A directly-follows graph: for every pair of activities that occur
    back-to-back anywhere in the event log, how many times that exact
    transition happened. This is the standard first visual in process
    mining -- nodes are activities, edges are observed transitions, edge
    weight is frequency -- and it's what Celonis's own Process Explorer is
    built on. Built straight from the raw event log (the same
    _sorted_events used everywhere else), not from the already-aggregated
    variant table, so a transition that appears inside several different
    variants (e.g. SUBMITTED -> FLAGGED_FOR_REVIEW happens in three of the
    five variants in the sample data) is correctly counted once per
    occurrence rather than once per variant."""
    edges: dict[tuple[str, str], int] = {}
    for req in requisitions:
        activities = [e["activity"] for e in _sorted_events(req)]
        for a, b in zip(activities, activities[1:]):
            edges[(a, b)] = edges.get((a, b), 0) + 1
    return edges


def render_process_map(dfg: dict[tuple[str, str], int], out_path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if not dfg:
        return

    nodes = sorted({a for a, _ in dfg} | {b for _, b in dfg})
    positions = {}
    unknown_count = 0
    for node in nodes:
        if node in _DFG_NODE_POSITIONS:
            positions[node] = _DFG_NODE_POSITIONS[node]
        else:
            # Defensive fallback for any activity name this script doesn't
            # know about yet (the Java side is the only writer of activity
            # names today, so this shouldn't trigger) -- still renders
            # something legible rather than a KeyError.
            positions[node] = (5.2, 0.5 - 0.3 * unknown_count)
            unknown_count += 1

    fig, ax = plt.subplots(figsize=(11, 5))
    max_count = max(dfg.values())
    # A one-hop edge in this layout spans one column (~1.3 units on x).
    # An edge spanning two or more columns -- a case that skipped an
    # intermediate activity entirely, like the FLAGGED_FOR_REVIEW ->
    # APPROVED bypass when a case is decided without ever being opened --
    # needs a sharply different curvature from the normal one-hop edges,
    # or its arc lands almost exactly on top of the two-hop path through
    # the skipped node and reads as the same line. A real process-mining
    # tool's layout engine solves this properly; here, a bigger curvature
    # in the opposite direction is enough to make a skipped-activity case
    # visually impossible to miss rather than quietly invisible.
    ONE_HOP_SPAN = 1.6

    for (a, b), count in sorted(dfg.items()):
        x1, y1 = positions[a]
        x2, y2 = positions[b]
        width = 1.2 + 3.0 * (count / max_count)
        is_multi_hop = abs(x2 - x1) > ONE_HOP_SPAN
        rad = -0.45 if is_multi_hop else 0.12
        ax.annotate(
            "", xy=(x2, y2), xytext=(x1, y1),
            arrowprops=dict(arrowstyle="-|>", color="#4C72B0" if not is_multi_hop else "#C44E52",
                             lw=width, connectionstyle=f"arc3,rad={rad}",
                             shrinkA=30, shrinkB=30, alpha=0.75),
        )
        # The label needs to sit on the arc itself, not on the straight
        # chord between the two nodes -- for a curved (rad != 0) edge
        # those are visibly different points, and guessing the curve's
        # bow direction and magnitude by hand (an earlier version of this
        # code did exactly that, with a sign that happened to roughly
        # work for the small one-hop curvature but put the label well off
        # the arc for the sharper, oppositely-signed multi-hop curvature)
        # is exactly the kind of thing worth getting from the source
        # instead of re-deriving. matplotlib's own arc3 connection style
        # places its single Bezier control point at the documented
        # formula below (control = chord midpoint + rad * perpendicular
        # chord vector); evaluating the resulting quadratic Bezier at its
        # true parametric midpoint (t=0.5: 0.25*P0 + 0.5*P1 + 0.25*P2)
        # gives the exact point on the rendered arc, in data coordinates,
        # with no dependence on a draw() call or a pixel/data transform.
        dx, dy = x2 - x1, y2 - y1
        cx, cy = (x1 + x2) / 2 + rad * dy, (y1 + y2) / 2 - rad * dx
        mx = 0.25 * x1 + 0.5 * cx + 0.25 * x2
        my = 0.25 * y1 + 0.5 * cy + 0.25 * y2
        ax.text(mx, my, str(count), fontsize=9, ha="center",
                color="#C44E52" if is_multi_hop else "#333333",
                bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none", alpha=0.9))

    for node, (x, y) in positions.items():
        ax.scatter([x], [y], s=2800, color="#EDEDED", edgecolors="#555555", zorder=3)
        ax.text(x, y, node.replace("_", "\n"), fontsize=8, ha="center", va="center", zorder=4)

    has_multi_hop = any(abs(positions[b][0] - positions[a][0]) > ONE_HOP_SPAN for a, b in dfg)
    xs = [x for x, _ in positions.values()]
    ys = [y for _, y in positions.values()]
    ax.set_title("Process map (directly-follows graph) -- edges labeled by transition frequency")
    if has_multi_hop:
        ax.text(0.5, -0.02, "red = skipped an expected intermediate activity",
                transform=ax.transAxes, fontsize=8, ha="center", color="#C44E52")
    ax.set_xlim(min(xs) - 0.8, max(xs) + 0.8)
    ax.set_ylim(min(ys) - 0.55, max(ys) + 0.4)
    ax.axis("off")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def render_chart(report: dict, out_path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    labels = list(report["variants"].keys())
    counts = [report["variants"][l].count for l in labels]
    queue = [report["variants"][l].avg_queue_minutes or 0 for l in labels]
    decision = [report["variants"][l].avg_decision_minutes or 0 for l in labels]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13, 4.5), sharey=True)
    ax1.barh(labels, counts, color="#4C72B0")
    ax1.set_title("Requisitions per variant")
    ax1.set_xlabel("Count")

    # sharey=True puts the same variant labels on ax1 already; repeating
    # them on ax2 only ate horizontal room and pushed its own title off the
    # edge of the figure, so they're hidden here rather than duplicated.
    ax2.barh(labels, queue, color="#DD8452", label="Queue time")
    ax2.barh(labels, decision, left=queue, color="#55A868", label="Decision time")
    ax2.set_title("Avg cycle time: queue vs. decision")
    ax2.set_xlabel("Minutes")
    ax2.tick_params(axis="y", labelleft=False)
    ax2.legend(loc="upper right", fontsize=8, framealpha=0.95)

    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def render_markdown(report: dict, chart_path: str | None, process_map_path: str | None = None) -> str:
    lines = [
        "# ReqFlow Process Intelligence -- Proof-of-Value Summary",
        "",
        f"Scope: {report['total']} requisitions currently in the system.",
    ]
    if process_map_path:
        lines += ["", "## Process map", "",
                  f"![Process map (directly-follows graph)]({process_map_path})"]
    lines += [
        "",
        "## Variants (actual activity sequences)",
        "",
        "| Variant | Count | Share | Avg total | Avg queue | Avg decision |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    total = report["total"]
    for label, stats in sorted(report["variants"].items(), key=lambda kv: -kv[1].count):
        share = f"{100 * stats.count / total:.0f}%" if total else "--"
        lines.append(
            f"| {label} | {stats.count} | {share} | {_fmt_minutes(stats.avg_total_minutes)} | "
            f"{_fmt_minutes(stats.avg_queue_minutes)} | {_fmt_minutes(stats.avg_decision_minutes)} |"
        )
    lines += [
        "",
        "*Queue/decision only populate for cases with an explicit "
        "`OPENED_FOR_REVIEW` event -- without one, only the total is "
        "reportable, disclosed rather than interpolated.*",
    ]

    if report["flag_reason_counts"]:
        lines += ["", "## All flagged cases, by original flag reason (decided or still open)", "",
                  "| Reason | Count |", "|---|---:|"]
        for label, count in sorted(report["flag_reason_counts"].items(), key=lambda kv: -kv[1]):
            lines.append(f"| {label} | {count} |")
        ages = report["backlog_ages_minutes"]
        if ages:
            lines += ["", f"*{len(ages)} case(s) still awaiting a decision; average age "
                          f"{statistics.mean(ages):,.1f} min.*"]

    if report["decisions_by_reviewer"]:
        lines += ["", "## Decisions by reviewer", "",
                  "| Reviewer | Decisions |", "|---|---:|"]
        for reviewer, count in sorted(report["decisions_by_reviewer"].items(), key=lambda kv: -kv[1]):
            lines.append(f"| {reviewer} | {count} |")
        lines += ["", "*Human name or `agent:claude-triage-v1` -- a cheap organizational-mining "
                      "cut (who's deciding what) over a field (`decidedBy`) the API already carries.*"]

    if report["department_cycle_time"]:
        lines += ["", "## Avg total cycle time by department", "",
                  "| Department | Cases | Avg total |", "|---|---:|---:|"]
        for dept, (count, avg) in sorted(report["department_cycle_time"].items(), key=lambda kv: -kv[1][1]):
            lines.append(f"| {dept} | {count} | {_fmt_minutes(avg)} |")
        lines += ["", "*Illustrative only at this sample size -- a real engagement would need "
                      "far more cases per department before reading a difference as a finding "
                      "rather than noise.*"]

    lines += ["", "## Conformance check",
              "",
              "Checked against the expected control flow: every case starts with "
              "`SUBMITTED`, and a flagged case that reaches a decision is expected to "
              "have been opened for review first. The API doesn't enforce that "
              "ordering -- `approve`/`reject` only require `PENDING_REVIEW` status, "
              "not a prior `OPENED_FOR_REVIEW` event -- so a deviation here is real "
              "data, not a bug."]
    issues = report["conformance_issues"]
    if issues:
        lines += ["", f"**{len(issues)}** case(s) deviated from the expected flow:", ""]
        for req_id, issue in issues:
            lines.append(f"- requisition {req_id}: {issue}")
    else:
        lines += ["", f"All **{report['total']}** case(s) conform -- no deviations found."]

    lines += [
        "",
        "## Estimated business value",
        "",
        f"- **{report['auto_approved']}** requisitions were auto-approved and never reached a human reviewer.",
        f"- At an assumed **{ASSUMED_MANUAL_REVIEW_MINUTES:.0f} min/case** manual review time, that's "
        f"**~{report['hours_saved']:.2f} reviewer-hours** avoided.",
        f"- At an assumed **${ASSUMED_REVIEWER_HOURLY_COST_USD:.0f}/hr** reviewer cost, that's "
        f"**~${report['cost_avoided']:.2f}** avoided.",
        "",
        "*Assumptions are disclosed and swappable -- a real engagement would substitute "
        "the customer's own review-time and labor-cost figures.*",
        "",
        f"At the same **{report['auto_approval_rate']:.0%}** auto-approval rate but a "
        f"production volume of **{ASSUMED_MONTHLY_VOLUME_AT_SCALE:,}/month** (disclosed, "
        f"hypothetical): **~{report['scaled_auto_approved']:,}** auto-approved, "
        f"**~{report['scaled_hours_saved']:.1f} reviewer-hours** and "
        f"**~${report['scaled_cost_avoided']:,.0f}/month** avoided.",
    ]

    if chart_path:
        lines += ["", f"![Variant and cycle-time chart]({chart_path})"]

    if report["excluded_bad_timestamps"]:
        lines += [
            "",
            f"*{report['excluded_bad_timestamps']} decided requisition(s) had an "
            "out-of-order event timestamp and were excluded from cycle-time stats as bad data.*",
        ]

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--out", help="Write a Markdown report to this path")
    parser.add_argument("--chart", help="Write a PNG chart to this path (requires --out)")
    parser.add_argument("--process-map", help="Write a directly-follows-graph PNG to this path (requires --out)")
    args = parser.parse_args()
    if args.chart and not args.out:
        parser.error("--chart requires --out (the chart is only referenced from the report)")
    if args.process_map and not args.out:
        parser.error("--process-map requires --out (the image is only referenced from the report)")

    requisitions = fetch_requisitions(args.base_url)
    report = build_report(requisitions)
    print_report(report)

    if args.out:
        chart_rel = None
        if args.chart:
            render_chart(report, Path(args.chart))
            chart_rel = Path(args.chart).name
        process_map_rel = None
        if args.process_map:
            render_process_map(build_dfg(requisitions), Path(args.process_map))
            process_map_rel = Path(args.process_map).name
        Path(args.out).write_text(render_markdown(report, chart_rel, process_map_rel), encoding="utf-8")
        print(f"\nWrote report to {args.out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
