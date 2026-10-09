package com.expense_management_service.service;

import com.expense_management_service.dto.response.DashboardResponse;

/**
 * Role-based dashboards. Each method answers "what's happening in my part of the workflow" for one
 * role; the controller restricts each to that role. Read-only.
 */
public interface DashboardService {

    /** The caller's own reports: where they are, what needs fixing, spend and reimbursement. */
    DashboardResponse employee();

    /** Approvals assigned to the caller: queue, waiting time, SLA, and their recent decisions. */
    DashboardResponse manager();

    /** Finance Verification across the organization: queue, queries, throughput, exceptions. */
    DashboardResponse finance();

    /** Employee reimbursement: what's waiting to be paid, how fast payments go out. */
    DashboardResponse ap();

    /** Organization-wide spend, workflow status, budgets and policy compliance. */
    DashboardResponse admin();
}
