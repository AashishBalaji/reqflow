-- Seed data so the API is demoable immediately after `mvn spring-boot:run`,
-- without needing to POST a vendor/budget-line/requisition by hand first.
-- Fiscal year 2026 to match budget lines the seeded requisitions reference.

insert into vendors (name, category, risk_score, active) values
  ('Northwind Office Supply', 'office_supplies', 12, true),
  ('Vantage IT Hardware', 'it_equipment', 35, true),
  ('Quickstone Facilities', 'facilities', 58, true),
  ('Horizon Consulting Group', 'professional_services', 81, true),
  ('Lapsed Freight Co', 'logistics', 44, false);

insert into budget_lines (department, category, fiscal_year, allocated_amount, spent_amount) values
  ('Engineering', 'it_equipment', 2026, 120000.00, 15000.00),
  ('Facilities', 'facilities', 2026, 60000.00, 52000.00),
  ('Operations', 'office_supplies', 2026, 20000.00, 2500.00);

-- A mix of outcomes an auto-approval policy would actually produce, so the
-- /api/requisitions endpoints have something to list/filter/approve right
-- away: one clean auto-approval, one blocked on vendor risk, one blocked on
-- budget headroom, and one blocked on amount alone.
insert into requisitions
  (requester_name, department, vendor_id, category, amount, justification, status, decided_by, decision_reason, created_at)
values
  ('Priya Nair', 'Engineering', 2, 'it_equipment', 4800.00, 'Replacement laptops for new hires',
   'AUTO_APPROVED', 'system:auto-approval-policy', 'Within ceiling, budget, and vendor-risk thresholds', CURRENT_TIMESTAMP),
  ('Diego Alvarez', 'Facilities', 4, 'professional_services', 9000.00, 'Office relocation consulting',
   'PENDING_REVIEW', null, 'Vendor ''Horizon Consulting Group'' risk score 81 is at or above the ceiling of 70', CURRENT_TIMESTAMP),
  ('Mei Chen', 'Facilities', 3, 'facilities', 15000.00, 'HVAC servicing contract renewal',
   'PENDING_REVIEW', null, 'Amount 15000.00 exceeds remaining budget of 8000.00 for Facilities/facilities FY2026', CURRENT_TIMESTAMP),
  ('Sam Okafor', 'Operations', 1, 'office_supplies', 62000.00, 'Bulk annual stationery order',
   'PENDING_REVIEW', null, 'Amount 62000.00 exceeds auto-approval ceiling of 50000.00', CURRENT_TIMESTAMP);
