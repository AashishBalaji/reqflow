# ReqFlow Process Intelligence -- Proof-of-Value Summary

> Sample output from a small local demo run (a handful of seeded and
> manually-decided requisitions created seconds apart), checked in to show
> the report's shape. Run against real usage, cycle times would reflect
> real review latency -- minutes to days, not fractions of a minute -- and
> the at-scale projection below shows how the same rate reads at a
> disclosed, hypothetical production volume.

Scope: 5 requisitions currently in the system.

## Decision paths

| Decision path | Count | Share | Avg cycle time |
|---|---:|---:|---:|
| Straight-through (auto-approved) | 2 | 40% | 0.0 min |
| Awaiting review (open) | 1 | 20% | -- |
| Manually approved after review | 1 | 20% | 0.4 min |
| Manually rejected after review | 1 | 20% | 0.4 min |

*These are decision-path groupings by terminal status, not full process-mining variant discovery over a multi-activity event sequence -- ReqFlow's audit trail records two timestamps per case (created, decided), not intermediate events like "opened for review". A richer log capturing that would let cycle time decompose into queue-time vs. decision-time and support true variant discovery.*

## Open backlog, by original flag reason

| Reason | Count |
|---|---:|
| Amount over auto-approval ceiling | 1 |

## Estimated business value

- **2** requisitions were auto-approved and never reached a human reviewer.
- At an assumed **8 min/case** manual review time, that's **~0.27 reviewer-hours** avoided.
- At an assumed **$25/hr** reviewer cost, that's **~$6.67** avoided.

*Assumptions are disclosed and swappable -- a real engagement would substitute the customer's own review-time and labor-cost figures.*

At the same **40%** auto-approval rate but a production volume of **2,000/month** (disclosed, hypothetical): **~800** auto-approved, **~106.7 reviewer-hours** and **~$2,667/month** avoided.

![Decision-path and cycle-time chart](sample_chart.png)
