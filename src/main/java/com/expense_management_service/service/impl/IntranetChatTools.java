package com.expense_management_service.service.impl;

import com.expense_management_service.integration.intranet.IntranetClient;
import com.expense_management_service.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paves Assistant tools for the intranet modules outside Expense Management: Leave, Timesheets, Projects,
 * celebrations, the Employee Directory, Resource Management, User Management, AI Screening, Accounts Payable and
 * Account Receivable. Each reads <b>only what the caller can already see in that module</b>, through
 * {@link IntranetClient} with the caller's own token (the downstream service applies its own role checks); the user
 * id comes from the JWT, never from the model. Results are trimmed here so requests stay small.
 * <p>
 * All tools are read-only. A module that is down, refuses the caller or is not connected returns a plain error
 * object for that tool only, so the other tools keep working.
 */
@Component
@RequiredArgsConstructor
public class IntranetChatTools {

    static final String LEAVE_BALANCE = "get_my_leave_balance";
    static final String ON_LEAVE_TODAY = "get_colleagues_on_leave_today";
    static final String HOLIDAYS = "get_upcoming_holidays";
    static final String TIMESHEET_HOURS = "get_my_timesheet_hours";
    static final String WORK_SUMMARY = "get_my_projects_and_tasks";
    static final String CELEBRATIONS = "get_upcoming_celebrations";
    static final String FIND_COLLEAGUE = "find_colleague";
    static final String BENCH_SUMMARY = "get_bench_and_demand_summary";
    static final String USERS_AND_ROLES = "get_users_and_roles";
    static final String RECRUITMENT_SUMMARY = "get_recruitment_summary";
    static final String PAYABLES_SUMMARY = "get_payables_summary";
    static final String RECEIVABLES_SUMMARY = "get_receivables_summary";

    private static final List<String> ALL_TOOLS = List.of(LEAVE_BALANCE, ON_LEAVE_TODAY, HOLIDAYS, TIMESHEET_HOURS,
            WORK_SUMMARY, CELEBRATIONS, FIND_COLLEAGUE, BENCH_SUMMARY, USERS_AND_ROLES, RECRUITMENT_SUMMARY,
            PAYABLES_SUMMARY, RECEIVABLES_SUMMARY);
    /** Directory matches returned for one name search. */
    private static final int MAX_PEOPLE = 5;
    /** Longest text value passed to the model from another module's response. */
    private static final int MAX_TEXT = 300;

    private static final int MAX_ROWS = 10;
    private static final DateTimeFormatter DD_MM_YY = DateTimeFormatter.ofPattern("dd/MM/yy");
    /** Optional string arguments may arrive as null; Groq validates arguments against the schema. */
    private static final List<String> OPTIONAL_STRING = List.of("string", "null");

    private final IntranetClient client;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${expense.business-time-zone:Asia/Kolkata}")
    private String businessTimeZone = "Asia/Kolkata";

    public List<Map<String, Object>> definitions() {
        return List.of(
                tool(LEAVE_BALANCE, "The current user's own leave balance for this year, per leave type (earned, sick, "
                        + "comp-off, ...): total, used and remaining days. Use for 'my leave balance', 'how many leaves do I have'."),
                tool(ON_LEAVE_TODAY, "Colleagues who are on leave today, with the session (full day / half day). "
                        + "Use for 'who is on leave today', 'is anyone out'."),
                tool(HOLIDAYS, "The next company holidays from today (name and date). Use for 'upcoming holidays', "
                        + "'next holiday', 'is there a holiday on ...'."),
                tool(TIMESHEET_HOURS, "Hours the current user has logged in timesheets this month. Use for 'my hours', "
                        + "'how many hours this month'."),
                tool(WORK_SUMMARY, "The current user's work summary from Projects: active project count and task counts. "
                        + "Use for 'my projects', 'my tasks', 'what am I working on'."),
                tool(CELEBRATIONS, "Upcoming birthdays, work anniversaries and new joinees across the company, soonest "
                        + "first. Use for 'birthdays', 'anniversaries', 'who joined', 'celebrations'."),
                tool(FIND_COLLEAGUE, "Look up colleagues in the Employee Directory by name: work email, employee id, "
                        + "designation, department and location (no personal details). Use for 'find X', "
                        + "'what is X's email', 'which team is X in'.",
                        Map.of("name", Map.of("type", "string",
                                "description", "Full or part of the person's name, at least 2 letters."))),
                tool(BENCH_SUMMARY, "Resource Management: bench KPIs (people on the bench) and open staffing demands. "
                        + "Only resource managers / admins can see it. Use for 'how many people are on the bench', "
                        + "'open demands'."),
                tool(USERS_AND_ROLES, "User Management (admins only): users with their roles, optionally filtered by "
                        + "name or email, plus the list of roles. Use for 'what roles does X have', 'list roles'.",
                        Map.of("search", Map.of("type", OPTIONAL_STRING,
                                "description", "Optional name or email to filter users. Leave empty (null) to list roles only."))),
                tool(RECRUITMENT_SUMMARY, "AI Screening: recruitment dashboard stats and the hiring funnel (candidates per "
                        + "pipeline stage, last 30 days). Only HR admins, recruiters and hiring managers can see it."),
                tool(PAYABLES_SUMMARY, "Accounts Payable: vendor invoice KPIs, what needs action, status and financial "
                        + "summary, and the caller's AP to-do list. Use for 'pending invoices', 'what is due to vendors'."),
                tool(RECEIVABLES_SUMMARY, "Account Receivable: billing and receivables KPIs, pipeline, worklist and aging "
                        + "for this month; with a search term also the matching client invoices. Only Finance can see it.",
                        Map.of("search", Map.of("type", OPTIONAL_STRING,
                                "description", "Optional client name or invoice number to look up invoices."))));
    }

    public boolean handles(String name) {
        return ALL_TOOLS.contains(name);
    }

    public Object execute(String name) {
        return execute(name, Map.of());
    }

    /**
     * Runs a tool. "Not connected" and "no access" come back as a plain error object for this tool only; any other
     * failure is thrown to ChatbotTools, which turns it into "could not be loaded right now".
     */
    public Object execute(String name, Map<String, Object> arguments) {
        try {
            return run(name, arguments == null ? Map.of() : arguments);
        } catch (IntranetClient.ModuleNotConnectedException ex) {
            return Map.of("error", ex.getMessage() + ". Open the module from the sidebar to see this information.");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                return Map.of("error", "The user does not have access to this information in that module.");
            }
            throw ex;
        }
    }

    private Object run(String name, Map<String, Object> arguments) {
        LocalDate today = LocalDate.now(ZoneId.of(businessTimeZone));
        return switch (name) {
            case LEAVE_BALANCE -> Map.of("year", today.getYear(),
                    "leaveTypes", limit(unwrap(client.leaveBalance(currentUserService.getUmsUserId(), today.getYear()))));
            case ON_LEAVE_TODAY -> {
                JsonNode people = unwrap(client.todayOnLeave());
                yield Map.of("date", today, "count", people.isArray() ? people.size() : 0, "people", limit(people));
            }
            case HOLIDAYS -> upcomingHolidays(unwrap(client.holidays(today.getYear())), today);
            case TIMESHEET_HOURS -> Map.of("month", today.getMonth().toString(), "hours", unwrap(client.timesheetHoursThisMonth()));
            case WORK_SUMMARY -> unwrap(client.workSummary(currentUserService.getUmsUserId()));
            case CELEBRATIONS -> celebrations(client.celebrations(), today);
            case FIND_COLLEAGUE -> findColleague(stringArg(arguments, "name"));
            case BENCH_SUMMARY -> twoParts(
                    "benchKpis", () -> compact(unwrap(client.benchKpis()), 0),
                    "openDemands", () -> countedList(unwrap(client.openDemands())));
            case USERS_AND_ROLES -> usersAndRoles(stringArg(arguments, "search"));
            case RECRUITMENT_SUMMARY -> twoParts(
                    "stats", () -> compact(unwrap(client.recruitmentStats()), 0),
                    "hiringFunnelLast30Days", () -> compact(unwrap(client.hiringFunnel(30)), 0));
            case PAYABLES_SUMMARY -> twoParts(
                    "summary", () -> pick(unwrap(client.payablesSummary()),
                            "kpis", "action_required", "status_summary", "financial_summary", "period"),
                    "myToDo", () -> compact(unwrap(client.payablesActions()), 0));
            case RECEIVABLES_SUMMARY -> receivables(stringArg(arguments, "search"));
            default -> Map.of("error", "Unknown tool: " + name);
        };
    }

    // ---------------------------------------------------------------- Employee Directory

    /** Directory matches for a name: only work-directory fields, never personal details or the whole list. */
    private Map<String, Object> findColleague(String name) {
        if (name == null || name.trim().length() < 2) {
            return Map.of("error", "Give at least 2 letters of the person's name to search the directory.");
        }
        String q = name.trim().toLowerCase();
        Map<String, String> departments = names(unwrap(client.departments()), "department_uuid", "department_name");
        Map<String, String> designations = names(unwrap(client.designations()), "designation_uuid", "designation_name");
        JsonNode people = unwrap(client.coreEmployeeDetails());

        List<Map<String, Object>> matches = new ArrayList<>();
        int total = 0;
        if (people.isArray()) {
            for (JsonNode e : people) {
                String full = (e.path("first_name").asText("") + " " + e.path("last_name").asText("")).trim();
                if (!full.toLowerCase().contains(q)) continue;
                total++;
                if (matches.size() >= MAX_PEOPLE) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", full);
                row.put("employeeId", text(e, "employee_id"));
                row.put("workEmail", text(e, "work_email") != null ? text(e, "work_email") : text(e, "email"));
                row.put("designation", designations.getOrDefault(e.path("designation_uuid").asText(""), text(e, "role")));
                row.put("department", departments.getOrDefault(e.path("department_uuid").asText(""), text(e, "department")));
                row.put("location", text(e, "location"));
                row.values().removeIf(java.util.Objects::isNull);
                matches.add(row);
            }
        }
        return Map.of("search", name.trim(), "matches", total, "people", matches,
                "note", total > matches.size() ? "Only the first " + matches.size() + " matches are listed." : "");
    }

    // ---------------------------------------------------------------- User Management

    private Map<String, Object> usersAndRoles(String search) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (search != null) {
            JsonNode res = client.usersWithRoles(search, MAX_ROWS);
            List<Map<String, Object>> users = new ArrayList<>();
            for (JsonNode u : res.path("users")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", text(u, "name"));
                row.put("email", text(u, "mail") != null ? text(u, "mail") : text(u, "email"));
                row.put("roles", u.path("roles"));
                row.values().removeIf(java.util.Objects::isNull);
                users.add(row);
            }
            out.put("search", search);
            out.put("matchingUsers", res.path("total").asInt(users.size()));
            out.put("users", users);
        }
        out.put("roles", compact(unwrap(client.roles()), 0));
        return out;
    }

    // ---------------------------------------------------------------- Account Receivable

    private Map<String, Object> receivables(String search) {
        Map<String, Object> out = new LinkedHashMap<>(twoParts(
                "summaryThisMonth", () -> pick(unwrap(client.receivablesSummary("MTD")),
                        "kpis", "pipeline", "worklist", "aging", "asOf"),
                null, null));
        if (search != null) {
            out.putAll(twoParts("invoices", () -> compact(unwrap(client.receivablesInvoices(search, MAX_ROWS)), 0),
                    null, null));
        }
        return out;
    }

    // ---------------------------------------------------------------- trimming helpers

    /**
     * Runs up to two independent reads; a part that fails is reported in place so the other part still answers.
     * Access and connection failures of the FIRST part are rethrown, so the whole tool reports them plainly.
     */
    private Map<String, Object> twoParts(String firstKey, java.util.function.Supplier<Object> first,
                                         String secondKey, java.util.function.Supplier<Object> second) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(firstKey, first.get());
        if (secondKey != null) {
            try {
                out.put(secondKey, second.get());
            } catch (RuntimeException ex) {
                out.put(secondKey, Map.of("error", "This part could not be loaded right now."));
            }
        }
        return out;
    }

    /** Count plus the first rows of a list. */
    private Map<String, Object> countedList(JsonNode list) {
        int count = list != null && list.isArray() ? list.size() : 0;
        return Map.of("count", count, "first", compact(list, 0));
    }

    /** Only the named top-level fields of an object, each compacted. */
    private Map<String, Object> pick(JsonNode node, String... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        for (String f : fields) {
            if (node.hasNonNull(f)) out.put(f, compact(node.get(f), 0));
        }
        return out;
    }

    /** Lists cut to MAX_ROWS, long text cut to MAX_TEXT, nesting cut at depth 4 - keeps any response small. */
    private JsonNode compact(JsonNode node, int depth) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        if (node.isTextual() && node.asText().length() > MAX_TEXT) {
            return objectMapper.getNodeFactory().textNode(node.asText().substring(0, MAX_TEXT) + "...");
        }
        if (depth >= 4 && node.isContainerNode()) {
            return objectMapper.getNodeFactory().textNode("(more detail on the module's page)");
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            for (int i = 0; i < Math.min(node.size(), MAX_ROWS); i++) out.add(compact(node.get(i), depth + 1));
            return out;
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode out = objectMapper.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), compact(e.getValue(), depth + 1)));
            return out;
        }
        return node;
    }

    private static Map<String, String> names(JsonNode list, String idField, String nameField) {
        Map<String, String> out = new java.util.HashMap<>();
        if (list != null && list.isArray()) {
            for (JsonNode n : list) out.put(n.path(idField).asText(""), n.path(nameField).asText(""));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) return null;
        String t = value.toString().trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }

    private Map<String, Object> upcomingHolidays(JsonNode all, LocalDate today) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (all != null && all.isArray()) {
            for (JsonNode h : all) {
                LocalDate date = parseIsoDate(h.path("holidayDate").asText(null));
                if (date != null && !date.isBefore(today)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", h.path("holidayName").asText(""));
                    row.put("date", date);
                    row.put("dayOfWeek", date.getDayOfWeek().toString());
                    rows.add(row);
                }
            }
        }
        rows.sort(Comparator.comparing(r -> (LocalDate) r.get("date")));
        return Map.of("today", today, "upcoming", rows.subList(0, Math.min(rows.size(), MAX_ROWS)),
                "note", rows.isEmpty() ? "No more holidays are listed this year." : "");
    }

    private Map<String, Object> celebrations(JsonNode res, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today);
        out.put("birthdays", soonest(res == null ? null : res.path("birthdays"), today, false));
        out.put("workAnniversaries", soonest(res == null ? null : res.path("workAnniversaries"), today, true));
        out.put("newJoinees", soonest(res == null ? null : res.path("newJoinees"), today, false));
        return out;
    }

    /** People sorted by the next occurrence of their date (dd/MM/yy), soonest first. */
    private List<Map<String, Object>> soonest(JsonNode list, LocalDate today, boolean withYears) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list == null || !list.isArray()) return rows;
        for (JsonNode p : list) {
            LocalDate next = nextOccurrence(p.path("date").asText(null), today);
            if (next == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.path("name").asText(""));
            row.put("date", next);
            row.put("inDays", java.time.temporal.ChronoUnit.DAYS.between(today, next));
            if (withYears && p.hasNonNull("anniversaryYear")) row.put("years", p.get("anniversaryYear").asInt());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> (Long) r.get("inDays")));
        return rows.subList(0, Math.min(rows.size(), MAX_ROWS));
    }

    /** The dashboard APIs wrap lists as {"data": [...]}; some return the list bare. */
    private static JsonNode unwrap(JsonNode node) {
        if (node == null) return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        if (node.isObject() && node.has("data")) return node.get("data");
        return node;
    }

    private JsonNode limit(JsonNode node) {
        if (node == null || !node.isArray() || node.size() <= MAX_ROWS) return node;
        ArrayNode trimmed = objectMapper.createArrayNode();
        for (int i = 0; i < MAX_ROWS; i++) trimmed.add(node.get(i));
        return trimmed;
    }

    private static LocalDate parseIsoDate(String text) {
        if (text == null || text.length() < 10) return null;
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (Exception ex) {
            return null;
        }
    }

    private static LocalDate nextOccurrence(String ddMMyy, LocalDate today) {
        if (ddMMyy == null) return null;
        try {
            LocalDate d = LocalDate.parse(ddMMyy, DD_MM_YY);
            LocalDate next = d.withYear(today.getYear());
            return next.isBefore(today) ? next.plusYears(1) : next;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Map<String, Object> tool(String name, String description) {
        return tool(name, description, Map.of());
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        return Map.of("type", "function", "function", Map.of(
                "name", name,
                "description", description,
                "parameters", Map.of("type", "object", "properties", properties, "required", List.of())));
    }
}
