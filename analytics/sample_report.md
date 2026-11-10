# ReqFlow Process Intelligence -- Proof-of-Value Summary

> Sample output from a small local demo run (a handful of seeded and
> manually-decided requisitions created seconds apart), checked in to show
> the report's shape. Run against real usage, cycle times would reflect
> real review latency -- minutes to days, not fractions of a minute -- and
> the business-value estimate would scale with real volume.

Scope: 5 requisitions currently in the system.

## Process variants

| Variant | Count | Share | Avg cycle time |
|---|---:|---:|---:|
| Straight-through (auto-approved) | 2 | 40% | 0.0 min |
| Awaiting review (open) | 1 | 20% | -- |
| Manually approved after review | 1 | 20% | 0.3 min |
| Manually rejected after review | 1 | 20% | 0.3 min |

## Open backlog, by original flag reason

| Reason | Count |
|---|---:|
| Amount over auto-approval ceiling | 1 |

## Estimated business value

- **2** requisitions were auto-approved and never reached a human reviewer.
- At an assumed **8 min/case** manual review time, that's **~0.27 reviewer-hours** avoided.
- At an assumed **$25/hr** reviewer cost, that's **~$6.67** avoided.

*Assumptions are disclosed and swappable -- a real engagement would substitute the customer's own review-time and labor-cost figures.*

![Process variant and cycle-time chart](sample_chart.png)
