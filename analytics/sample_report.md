# ReqFlow Process Intelligence -- Proof-of-Value Summary

> Sample output from a small local demo run (6 hand-created requisitions, including a real event log walked through the agent's submit -> open -> decide flow), checked in to show the report's shape -- variants, the queue/decision split, and the flag-reason breakdown. Run against real usage, the same fields populate at whatever volume and cadence the process actually runs at, and the at-scale projection below shows how the same rate reads at a disclosed, hypothetical production volume.

Scope: 6 requisitions currently in the system.

## Variants (actual activity sequences)

| Variant | Count | Share | Avg total | Avg queue | Avg decision |
|---|---:|---:|---:|---:|---:|
| SUBMITTED → AUTO_APPROVED | 2 | 33% | 0.0 min | -- | 0.0 min |
| SUBMITTED → FLAGGED_FOR_REVIEW → OPENED_FOR_REVIEW → APPROVED | 2 | 33% | 0.4 min | 0.4 min | 0.0 min |
| SUBMITTED → FLAGGED_FOR_REVIEW → OPENED_FOR_REVIEW → REJECTED | 1 | 17% | 0.4 min | 0.3 min | 0.0 min |
| SUBMITTED → FLAGGED_FOR_REVIEW | 1 | 17% | -- | -- | -- |

*Queue/decision only populate for cases with an explicit `OPENED_FOR_REVIEW` event -- without one, only the total is reportable, disclosed rather than interpolated.*

## All flagged cases, by original flag reason (decided or still open)

| Reason | Count |
|---|---:|
| Amount over auto-approval ceiling | 3 |
| Budget headroom insufficient or untracked | 1 |

*1 case(s) still awaiting a decision; average age 3.0 min.*

## Estimated business value

- **2** requisitions were auto-approved and never reached a human reviewer.
- At an assumed **8 min/case** manual review time, that's **~0.27 reviewer-hours** avoided.
- At an assumed **$25/hr** reviewer cost, that's **~$6.67** avoided.

*Assumptions are disclosed and swappable -- a real engagement would substitute the customer's own review-time and labor-cost figures.*

At the same **33%** auto-approval rate but a production volume of **2,000/month** (disclosed, hypothetical): **~667** auto-approved, **~88.9 reviewer-hours** and **~$2,223/month** avoided.

![Variant and cycle-time chart](sample_chart.png)
