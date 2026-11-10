"""
Unit tests for process_intelligence.py's pure logic -- no running ReqFlow
instance required (fetch_requisitions, the only networked function, isn't
exercised here).

This file exists because of a real bug: an earlier version of
classify_flag_reason() probed for the bare word "ceiling" before "risk
score", and the vendor-risk flag message ("...risk score 81 is at or above
the ceiling of 70") also contains the word "ceiling" -- so every
vendor-risk-flagged requisition was silently miscategorized as an
amount-ceiling flag. It shipped into a checked-in sample report before a
round of manual review caught it. A single test on this function would
have caught it immediately; that's what most of this file is.

Run with: python3 -m unittest test_process_intelligence.py -v
(or just `python3 test_process_intelligence.py`)
"""

import unittest
from datetime import datetime, timezone

from process_intelligence import (
    check_conformance,
    classify_flag_reason,
    _sorted_events,
    _parse_instant,
    profile_case,
)

# The exact message templates RequisitionService.java generates --
# duplicated here deliberately (not imported, there's nothing to import
# across languages) so a probe that happens to match today's wording but
# would break on a realistic rewording gets caught by eyeballing this list,
# not just by today's exact strings.
VENDOR_RISK_MESSAGE = "Vendor 'Horizon Consulting Group' risk score 81 is at or above the ceiling of 70"
INACTIVE_VENDOR_RISK_MESSAGE = VENDOR_RISK_MESSAGE + " (and vendor is inactive)"
AMOUNT_CEILING_MESSAGE = "Amount 62000.00 exceeds auto-approval ceiling of 50000.00"
NO_BUDGET_LINE_MESSAGE = "No budget line tracked for Sales/office_supplies in FY2026 - can't confirm there's room"
OVER_BUDGET_MESSAGE = "Amount 15000.00 exceeds remaining budget of 8000.00 for Facilities/facilities FY2026"


class ClassifyFlagReasonTests(unittest.TestCase):
    """The regression test for the bug described in the module docstring:
    a vendor-risk message also contains the word "ceiling", so the ceiling
    probe must not shadow the risk-score probe."""

    def test_vendor_risk_message_is_not_misclassified_as_amount_ceiling(self):
        self.assertEqual(classify_flag_reason(VENDOR_RISK_MESSAGE), "Vendor risk too high / inactive")

    def test_inactive_vendor_risk_message_also_classifies_correctly(self):
        self.assertEqual(classify_flag_reason(INACTIVE_VENDOR_RISK_MESSAGE), "Vendor risk too high / inactive")

    def test_amount_ceiling_message_classifies_correctly(self):
        self.assertEqual(classify_flag_reason(AMOUNT_CEILING_MESSAGE), "Amount over auto-approval ceiling")

    def test_missing_budget_line_message_classifies_as_budget(self):
        self.assertEqual(classify_flag_reason(NO_BUDGET_LINE_MESSAGE), "Budget headroom insufficient or untracked")

    def test_over_budget_message_classifies_as_budget(self):
        self.assertEqual(classify_flag_reason(OVER_BUDGET_MESSAGE), "Budget headroom insufficient or untracked")

    def test_unrecognized_text_falls_back_to_other(self):
        self.assertEqual(classify_flag_reason("some future reason nobody wrote a probe for"),
                          "Other / unclassified")

    def test_none_detail_falls_back_to_other_without_crashing(self):
        self.assertEqual(classify_flag_reason(None), "Other / unclassified")


def _event(activity: str, occurred_at: str, detail: str | None = None) -> dict:
    return {"activity": activity, "occurredAt": occurred_at, "detail": detail}


class SortedEventsTests(unittest.TestCase):
    """Regression test for the sort-order bug: sibling events written in
    the same service call can share an identical timestamp, and the sort
    must not use activity name as a tiebreaker (that would put
    AUTO_APPROVED before SUBMITTED alphabetically)."""

    def test_same_timestamp_siblings_keep_api_order_not_alphabetical_order(self):
        same_instant = "2026-01-01T00:00:00.000000Z"
        req = {"events": [
            _event("SUBMITTED", same_instant),
            _event("AUTO_APPROVED", same_instant),
        ]}
        ordered = _sorted_events(req)
        self.assertEqual([e["activity"] for e in ordered], ["SUBMITTED", "AUTO_APPROVED"])

    def test_distinct_timestamps_sort_chronologically_regardless_of_input_order(self):
        req = {"events": [
            _event("APPROVED", "2026-01-01T00:05:00Z"),
            _event("SUBMITTED", "2026-01-01T00:00:00Z"),
            _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:01:00Z"),
        ]}
        ordered = _sorted_events(req)
        self.assertEqual([e["activity"] for e in ordered],
                          ["SUBMITTED", "FLAGGED_FOR_REVIEW", "APPROVED"])


class ConformanceTests(unittest.TestCase):

    def test_straight_through_auto_approval_conforms(self):
        events = [_event("SUBMITTED", "2026-01-01T00:00:00Z"),
                  _event("AUTO_APPROVED", "2026-01-01T00:00:01Z")]
        terminal = events[1]
        self.assertIsNone(check_conformance(events, None, None, terminal))

    def test_flagged_opened_then_decided_conforms(self):
        events = [_event("SUBMITTED", "2026-01-01T00:00:00Z"),
                  _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:01Z"),
                  _event("OPENED_FOR_REVIEW", "2026-01-01T00:05:00Z"),
                  _event("APPROVED", "2026-01-01T00:06:00Z")]
        self.assertIsNone(check_conformance(events, events[1], events[2], events[3]))

    def test_still_pending_review_is_not_flagged_as_a_deviation(self):
        # Flagged but not yet decided -- nothing to check conformance
        # against yet, so this must not be reported as a bypass.
        events = [_event("SUBMITTED", "2026-01-01T00:00:00Z"),
                  _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:01Z")]
        self.assertIsNone(check_conformance(events, events[1], None, None))

    def test_decided_without_ever_being_opened_is_a_deviation(self):
        events = [_event("SUBMITTED", "2026-01-01T00:00:00Z"),
                  _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:01Z"),
                  _event("APPROVED", "2026-01-01T00:00:02Z")]
        issue = check_conformance(events, events[1], None, events[2])
        self.assertIsNotNone(issue)
        self.assertIn("bypassed", issue)

    def test_missing_submitted_as_first_activity_is_a_deviation(self):
        events = [_event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:00Z")]
        issue = check_conformance(events, events[0], None, None)
        self.assertEqual(issue, "Does not start with SUBMITTED")


class ProfileCaseIntegrationTests(unittest.TestCase):
    """A couple of end-to-end sanity checks through profile_case() itself,
    not just the helper functions it calls."""

    def test_vendor_risk_flag_category_survives_end_to_end_through_profile_case(self):
        req = {
            "decidedBy": None,
            "events": [
                _event("SUBMITTED", "2026-01-01T00:00:00Z"),
                _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:01Z", detail=VENDOR_RISK_MESSAGE),
            ],
        }
        profile = profile_case(req, datetime.now(timezone.utc))
        self.assertEqual(profile.flag_category, "Vendor risk too high / inactive")
        self.assertIsNone(profile.conformance_issue)  # still pending, not a deviation yet

    def test_bypassed_open_step_is_surfaced_as_a_conformance_issue(self):
        req = {
            "decidedBy": "someone",
            "events": [
                _event("SUBMITTED", "2026-01-01T00:00:00Z"),
                _event("FLAGGED_FOR_REVIEW", "2026-01-01T00:00:01Z", detail=VENDOR_RISK_MESSAGE),
                _event("APPROVED", "2026-01-01T00:00:02Z", detail="approved directly"),
            ],
        }
        profile = profile_case(req, datetime.now(timezone.utc))
        self.assertIsNotNone(profile.conformance_issue)
        self.assertIn("bypassed", profile.conformance_issue)


if __name__ == "__main__":
    unittest.main()
