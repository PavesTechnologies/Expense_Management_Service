-- Replaces EP06's cost-center + amount-range approval matrix with a new priority-ordered
-- Approval Flow engine (per-employee approver sources instead of a cost-center/amount matrix).
--
-- Consistent with this project's established convention (see V5's note): Flyway here handles only
-- drops and data changes that `hibernate.ddl-auto=update` cannot safely perform. The new schema
-- (approval_flow, approval_flow_criterion, approval_level, approval_level_approver,
-- department_approver, approval_level_instance, approval_assignment, approval_line_item_review) is
-- NOT created here — it is left to ddl-auto=update to generate from the new JPA entities, exactly
-- like every other new table in this project.
--
-- Three things this migration DOES need to do by hand:
--
-- 1. Drop the old EP06 matrix/task tables being replaced outright. approval_delegation,
--    approval_matrix's sibling SLA/escalation config (SystemConfiguration keys) are untouched -
--    delegation is carried over unchanged, and the SLA business-days key is reused as-is for the
--    new reminder-only escalation behavior.
--
-- 2. Neutralise expense_report.report_status values that no longer exist as Java enum constants
--    (SUBMITTED, MANAGER_APPROVED, FINANCE_APPROVED - removed because nothing in the old engine
--    ever actually set the latter two, per ReportStatus's own javadoc, and SUBMITTED was never used
--    either - submission goes straight to PENDING_APPROVAL). report_status has no DB-level CHECK
--    constraint, only the Java @Enumerated(EnumType.STRING) mapping, so an existing row holding one
--    of these values would fail to deserialize once the constant is removed - this is a defensive
--    safety net, not an expected real transformation.
--
-- 3. Neutralise cost_center.owner_employee_id: it has been storing a UMS `user_id` (validated via
--    UmsClient.getAllUsers() in CostCenterServiceImpl.assertOwnerExists), not an EOS `employeeId`
--    like every other approver reference in this system (EmployeeCache.managerEmployeeId,
--    ApprovalDelegation.delegatorId/delegateId, etc). CostCenterServiceImpl is being fixed
--    alongside this migration to validate/store an EOS employeeId going forward (join against
--    EmployeeCacheRepository instead of UMS). There is no reliable local join from a UMS user_id to
--    an EOS employeeId, so existing values cannot be automatically converted - any row whose
--    owner_employee_id does not already happen to match a known EmployeeCache.employee_id is
--    nulled out here, requiring Admin to re-set the correct owner via the fixed Cost Center screen.

UPDATE expense_report
SET report_status = 'PENDING_APPROVAL'
WHERE report_status = 'SUBMITTED';

UPDATE expense_report
SET report_status = 'APPROVED'
WHERE report_status IN ('MANAGER_APPROVED', 'FINANCE_APPROVED');

UPDATE cost_center cc
SET owner_employee_id = NULL
WHERE owner_employee_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM employee_cache ec WHERE ec.employee_id = cc.owner_employee_id
  );

DROP TABLE IF EXISTS approval_task;
DROP TABLE IF EXISTS approval_matrix;
