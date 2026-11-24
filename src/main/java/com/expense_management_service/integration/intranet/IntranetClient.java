package com.expense_management_service.integration.intranet;

import com.expense_management_service.config.IntranetServicesProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.function.Function;

/**
 * Read-only access to the other intranet modules for the Paves Assistant. Every call is a GET to an endpoint the
 * intranet frontend already calls for the signed-in user, with the caller's own token (forwarded by each RestClient),
 * so a user can only read what they could already see in that module; the downstream service applies its own role
 * checks. Responses are returned as raw JSON; the assistant's tools trim them before they reach the model.
 * <p>
 * Nothing here writes: there are no POST/PUT/PATCH/DELETE calls.
 */
@Slf4j
@Component
public class IntranetClient {

    /** Thrown when a module's base URL is not configured, so the assistant can say it is not connected. */
    public static class ModuleNotConnectedException extends RuntimeException {
        public ModuleNotConnectedException(String module) {
            super(module + " is not connected to the assistant yet");
        }
    }

    private final RestClient lms;
    private final RestClient tms;
    private final RestClient pms;
    private final RestClient ems;
    private final RestClient rms;
    private final RestClient ums;
    private final RestClient airs;
    private final RestClient ap;
    private final RestClient ar;
    private final IntranetServicesProperties properties;

    public IntranetClient(@Qualifier("lmsRestClient") RestClient lms,
                          @Qualifier("tmsRestClient") RestClient tms,
                          @Qualifier("pmsRestClient") RestClient pms,
                          @Qualifier("employeeOnboardingRestClient") RestClient ems,
                          @Qualifier("rmsRestClient") RestClient rms,
                          @Qualifier("umsRestClient") RestClient ums,
                          @Qualifier("airsRestClient") RestClient airs,
                          @Qualifier("apRestClient") RestClient ap,
                          @Qualifier("arRestClient") RestClient ar,
                          IntranetServicesProperties properties) {
        this.lms = lms;
        this.tms = tms;
        this.pms = pms;
        this.ems = ems;
        this.rms = rms;
        this.ums = ums;
        this.airs = airs;
        this.ap = ap;
        this.ar = ar;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- Leave (LMS), Timesheets (TMS), Projects (PMS)

    /** LMS: the user's leave balance per leave type for a year (the dashboard's Leave Balance card). */
    public JsonNode leaveBalance(Long umsUserId, int year) {
        return get(lms, "/api/leave-balance/employee-dashboard/leave-balance/" + umsUserId + "/" + year);
    }

    /** LMS: colleagues on leave today (the dashboard's Today on Leave card). */
    public JsonNode todayOnLeave() {
        return get(lms, "/api/leave-requests/dashboard/today-on-leave");
    }

    /** LMS: the company holiday calendar for a year. */
    public JsonNode holidays(int year) {
        return get(lms, "/api/holidays/year/" + year);
    }

    /** TMS: hours the user has logged this month. */
    public JsonNode timesheetHoursThisMonth() {
        return get(tms, "/api/dashboard/total_hours");
    }

    /** PMS: the user's work summary (active projects, tasks). */
    public JsonNode workSummary(Long umsUserId) {
        return get(pms, b -> b.path("/api/my-work/dashboard-summary").queryParam("userId", umsUserId).build());
    }

    // ---------------------------------------------------------------- Employee Onboarding (EMS)

    /** EMS: birthdays, work anniversaries and new joinees. */
    public JsonNode celebrations() {
        return get(ems, "/dashboard/celebrations");
    }

    /** EMS: employee records behind the Employee Directory page (the tool keeps only directory fields). */
    public JsonNode coreEmployeeDetails() {
        return get(ems, "/permanent-employee/core-employee-details/");
    }

    /** EMS: department names, to label directory entries. */
    public JsonNode departments() {
        return get(ems, "/masters/departments/");
    }

    /** EMS: designation names, to label directory entries. */
    public JsonNode designations() {
        return get(ems, "/masters/designations/");
    }

    // ---------------------------------------------------------------- Resource Management (RMS)

    /** RMS: bench KPIs (the Bench Management dashboard). */
    public JsonNode benchKpis() {
        return get(rms, "/api/bench/kpi");
    }

    /** RMS: open/approved demands visible to the caller as a resource manager. */
    public JsonNode openDemands() {
        return get(rms, "/api/demand/rm/demands");
    }

    // ---------------------------------------------------------------- User Management (UMS, admin endpoints)

    /** UMS: users with their roles, first page, optionally filtered by a search term (User Manage page). */
    public JsonNode usersWithRoles(String search, int limit) {
        return get(ums, b -> b.path("/admin/users/roles")
                .queryParam("page", 1).queryParam("limit", limit).queryParam("search", search == null ? "" : search)
                .build());
    }

    /** UMS: all roles (Role Manage page). */
    public JsonNode roles() {
        return get(ums, "/admin/roles");
    }

    // ---------------------------------------------------------------- AI Screening (AIRS)

    /** AIRS: recruitment dashboard stats. */
    public JsonNode recruitmentStats() {
        return get(requireConnected(airs, properties.airsBaseUrl(), "AI Screening"), "/dashboard/stats");
    }

    /** AIRS: candidates per pipeline stage over the last {@code days} days. */
    public JsonNode hiringFunnel(int days) {
        return get(requireConnected(airs, properties.airsBaseUrl(), "AI Screening"),
                b -> b.path("/dashboard/hiring-funnel").queryParam("days", days).build());
    }

    // ---------------------------------------------------------------- Accounts Payable (AP)

    /** AP: dashboard summary (KPIs, action required, status and financial summary), scoped by the AP backend. */
    public JsonNode payablesSummary() {
        return get(requireConnected(ap, properties.apBaseUrl(), "Accounts Payable"), "/dashboard/summary");
    }

    /** AP: the caller's prioritised to-do list (Action Center). */
    public JsonNode payablesActions() {
        return get(requireConnected(ap, properties.apBaseUrl(), "Accounts Payable"), "/dashboard/actions");
    }

    // ---------------------------------------------------------------- Account Receivable (AR)

    /** AR: dashboard summary for the period (KPIs, pipeline, worklist, aging). */
    public JsonNode receivablesSummary(String period) {
        return get(requireConnected(ar, properties.arBaseUrl(), "Account Receivable"),
                b -> b.path("/api/v1/dashboard/summary").queryParam("period", period).build());
    }

    /** AR: invoices on the AR dashboard, optionally filtered by a search term (client, invoice number). */
    public JsonNode receivablesInvoices(String search, int limit) {
        return get(requireConnected(ar, properties.arBaseUrl(), "Account Receivable"), b -> {
            UriBuilder ub = b.path("/api/v1/dashboard/invoices").queryParam("view", "ALL").queryParam("limit", limit);
            if (search != null && !search.isBlank()) ub.queryParam("q", search.trim());
            return ub.build();
        });
    }

    // ---------------------------------------------------------------- helpers

    private static RestClient requireConnected(RestClient client, String baseUrl, String module) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ModuleNotConnectedException(module);
        }
        return client;
    }

    private JsonNode get(RestClient client, String path) {
        log.debug("[INTRANET-OUT] GET {}", path);
        return client.get().uri(path).retrieve().body(JsonNode.class);
    }

    private JsonNode get(RestClient client, Function<UriBuilder, URI> uri) {
        return client.get().uri(uri).retrieve().body(JsonNode.class);
    }
}
