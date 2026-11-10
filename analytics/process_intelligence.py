"""
Process intelligence over ReqFlow's own audit trail.

ReqFlow already records, for every requisition, when it was created, when
(and whether) it was decided, and why -- a timestamped event log by any
other name. This script reads that log the way a process-mining tool
would: it groups requisitions into the decision paths they took through
the approval process, measures how long each path actually takes, and
turns the straight-through / manual-review split into a quantified
business-value number -- the kind of proof-of-value deliverable a
procurement process-intelligence engagement is built to produce.

Honest about its own limits: with only two recorded timestamps per case
(created, decided), a "decision path" here is really a terminal-status
grouping, not full process-mining variant discovery over a multi-activity
event sequence -- and cycle time is total elapsed time, not decomposed
into queue-time vs. decision-time. A richer log (e.g. an "opened for
review" event) would support both; see the note each report prints.

It is a client of the REST API, nothing more -- the same relationship the
AI triage agent (agent/triage_agent.py) has to ReqFlow. No new endpoint,
no new table: the data was always there, this just reads it differently.

Usage:
    python process_intelligence.py [--base-url http://localhost:8080]
    python process_intelligence.py --out report.md    # also write a report
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

LIMITATION_NOTE = (
    "These are decision-path groupings by terminal status, not full "
    "process-mining variant discovery over a multi-activity event "
    "sequence -- ReqFlow's audit trail records two timestamps per case "
    "(created, decided), not intermediate events like \"opened for "
    "review\". A richer log capturing that would let cycle time "
    "decompose into queue-time vs. decision-time and support true "
    "variant discovery."
)

# Keyword probes used only to bucket an *open* (still-pending) requisition's
# flag reason into a coarse category for the backlog breakdown below. This
# only works for PENDING_REVIEW cases: once a human or the agent decides a
# requisition, decide() overwrites decisionReason with their own text, so
# the original flag category isn't recoverable after the fact -- a real
# limitation of this data model, noted in the report rather than papered
# over.
BACKLOG_CATEGORY_PROBES = [
    ("ceiling", "Amount over auto-approval ceiling"),
    ("risk score", "Vendor risk too high / inactive"),
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


@dataclass
class VariantStats:
    label: str
    count: int = 0
    cycle_minutes: list[float] = field(default_factory=list)

    @property
    def avg_cycle_minutes(self) -> float | None:
        return statistics.mean(self.cycle_minutes) if self.cycle_minutes else None


def classify_variant(req: dict) -> str:
    status = req["status"]
    if status == "AUTO_APPROVED":
        return "Straight-through (auto-approved)"
    if status == "APPROVED":
        return "Manually approved after review"
    if status == "REJECTED":
        return "Manually rejected after review"
    return "Awaiting review (open)"


def classify_backlog_reason(req: dict) -> str:
    reason = (req.get("decisionReason") or "").lower()
    for probe, label in BACKLOG_CATEGORY_PROBES:
        if probe in reason:
            return label
    return "Other / unclassified"


def cycle_minutes(req: dict) -> float | None:
    """None if undecided, or if the timestamps are unusable (clock skew or
    bad data producing decidedAt before createdAt) -- a negative duration
    averaged in silently would be a textbook "quietly wrong number" in a
    report meant to be taken at face value."""
    created = _parse_instant(req.get("createdAt"))
    decided = _parse_instant(req.get("decidedAt"))
    if created is None or decided is None:
        return None
    minutes = (decided - created).total_seconds() / 60.0
    return minutes if minutes >= 0 else None


def build_report(requisitions: list[dict]) -> dict:
    variants: dict[str, VariantStats] = {}
    backlog_by_reason: dict[str, int] = {}
    now = datetime.now(timezone.utc)
    backlog_ages_minutes: list[float] = []
    excluded_bad_timestamps = 0

    for req in requisitions:
        label = classify_variant(req)
        stats = variants.setdefault(label, VariantStats(label))
        stats.count += 1

        if label == "Awaiting review (open)":
            reason = classify_backlog_reason(req)
            backlog_by_reason[reason] = backlog_by_reason.get(reason, 0) + 1
            created = _parse_instant(req.get("createdAt"))
            if created is not None:
                backlog_ages_minutes.append((now - created).total_seconds() / 60.0)
        else:
            minutes = cycle_minutes(req)
            if minutes is not None:
                stats.cycle_minutes.append(minutes)
            elif req.get("decidedAt") is not None:
                # Decided, but the timestamps didn't produce a usable
                # (non-negative) duration -- bad data, not "undecided".
                excluded_bad_timestamps += 1

    total = len(requisitions)
    auto_approved = variants.get("Straight-through (auto-approved)", VariantStats("")).count
    manually_decided = sum(
        v.count for k, v in variants.items() if k.startswith("Manually")
    )
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
        "manually_decided": manually_decided,
        "backlog_by_reason": backlog_by_reason,
        "backlog_ages_minutes": backlog_ages_minutes,
        "hours_saved": hours_saved,
        "cost_avoided": cost_avoided,
        "auto_approval_rate": auto_approval_rate,
        "scaled_auto_approved": scaled_auto_approved,
        "scaled_hours_saved": scaled_hours_saved,
        "scaled_cost_avoided": scaled_cost_avoided,
        "excluded_bad_timestamps": excluded_bad_timestamps,
    }


def print_report(report: dict) -> None:
    total = report["total"]
    print(f"\nReqFlow process intelligence -- {total} requisitions in scope\n")
    print(f"{'Decision path':38} {'Count':>6} {'Share':>7} {'Avg cycle':>12}")
    for label, stats in sorted(report["variants"].items(), key=lambda kv: -kv[1].count):
        share = f"{100 * stats.count / total:.0f}%" if total else "--"
        avg = stats.avg_cycle_minutes
        avg_str = f"{avg:,.1f} min" if avg is not None else "--"
        print(f"{label:38} {stats.count:>6} {share:>7} {avg_str:>12}")
    print(f"\n  {LIMITATION_NOTE}")

    if report["backlog_by_reason"]:
        print("\nOpen backlog, by original flag reason:")
        for label, count in sorted(report["backlog_by_reason"].items(), key=lambda kv: -kv[1]):
            print(f"  {label:42} {count:>4}")
        ages = report["backlog_ages_minutes"]
        if ages:
            print(f"  (average time in backlog so far: {statistics.mean(ages):,.1f} min)")

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
            "decidedAt before createdAt and were excluded from cycle-time stats as bad data."
        )


def render_chart(report: dict, out_path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    labels = list(report["variants"].keys())
    counts = [report["variants"][l].count for l in labels]
    avg_cycles = [report["variants"][l].avg_cycle_minutes or 0 for l in labels]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11, 4))
    ax1.barh(labels, counts, color="#4C72B0")
    ax1.set_title("Requisitions per decision path")
    ax1.set_xlabel("Count")

    ax2.barh(labels, avg_cycles, color="#DD8452")
    ax2.set_title("Avg cycle time by path")
    ax2.set_xlabel("Minutes")

    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def render_markdown(report: dict, chart_path: str | None) -> str:
    lines = [
        "# ReqFlow Process Intelligence -- Proof-of-Value Summary",
        "",
        f"Scope: {report['total']} requisitions currently in the system.",
        "",
        "## Decision paths",
        "",
        "| Decision path | Count | Share | Avg cycle time |",
        "|---|---:|---:|---:|",
    ]
    for label, stats in sorted(report["variants"].items(), key=lambda kv: -kv[1].count):
        share = f"{100 * stats.count / report['total']:.0f}%" if report["total"] else "--"
        avg = stats.avg_cycle_minutes
        avg_str = f"{avg:,.1f} min" if avg is not None else "--"
        lines.append(f"| {label} | {stats.count} | {share} | {avg_str} |")
    lines += ["", f"*{LIMITATION_NOTE}*"]

    if report["backlog_by_reason"]:
        lines += ["", "## Open backlog, by original flag reason", "",
                  "| Reason | Count |", "|---|---:|"]
        for label, count in sorted(report["backlog_by_reason"].items(), key=lambda kv: -kv[1]):
            lines.append(f"| {label} | {count} |")

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
        lines += ["", f"![Decision-path and cycle-time chart]({chart_path})"]

    if report["excluded_bad_timestamps"]:
        lines += [
            "",
            f"*{report['excluded_bad_timestamps']} decided requisition(s) had `decidedAt` "
            "before `createdAt` and were excluded from cycle-time stats as bad data.*",
        ]

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--out", help="Write a Markdown report to this path")
    parser.add_argument("--chart", help="Write a PNG chart to this path (requires --out)")
    args = parser.parse_args()
    if args.chart and not args.out:
        parser.error("--chart requires --out (the chart is only referenced from the report)")

    requisitions = fetch_requisitions(args.base_url)
    report = build_report(requisitions)
    print_report(report)

    if args.out:
        chart_rel = None
        if args.chart:
            render_chart(report, Path(args.chart))
            chart_rel = Path(args.chart).name
        Path(args.out).write_text(render_markdown(report, chart_rel), encoding="utf-8")
        print(f"\nWrote report to {args.out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
