package com.expense_management_service.e2e;

import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyAssignment;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.enums.PolicyAssignmentType;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.integration.pms.PmsClient;
import com.expense_management_service.integration.rms.RmsClient;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.PolicyAssignmentRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.scheduler.ExchangeRateRefreshScheduler;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.textract.TextractClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Whole-application workflow tests: the real controllers, security, services, transactions and JPA, called over
 * HTTP the way the frontend calls them, against an in-memory H2 database created for the test run.
 * <p>
 * The datasource is set explicitly to H2 here, so the {@code .env} file (loaded by spring-dotenv) can never point
 * these tests at the shared dev MySQL. Every system outside XMS is replaced with a mock: UMS, PMS, RMS, the
 * departments API, S3/Textract, RabbitMQ, and the Kafka CDC consumer (not started). The JWT decoder is mocked;
 * callers are authenticated per request with {@code jwt()} carrying the claims {@code CurrentUserService} reads.
 * <p>
 * Tests are deliberately not {@code @Transactional}: each HTTP call commits like it does in production (and the
 * after-commit listeners run). The database is shared by every test in the run, so each test creates its own
 * employee, cost center and category; only the INR currency, the default policy and the catch-all approval flow
 * (manager {@link #MANAGER} then Finance verification) are shared, created once.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:xms_e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE,YEAR,MONTH,KEY,USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "spring.flyway.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid",
        "ums.base-url=http://ums.invalid",
        "ums.jwks-uri=http://ums.invalid/jwks",
        "employee-onboarding.base-url=http://eos.invalid",
        "pms.base-url=http://pms.invalid",
        "rms.base-url=http://rms.invalid",
        "aws.region=ap-south-1",
        "aws.access-key-id=test",
        "aws.secret-access-key=test",
        "aws.s3.bucket-name=xms-e2e",
        "exchange.rate.api.url=http://fx.invalid",
        "exchange.rate.api.key=test",
        "exchange.rate.base-currency=INR",
        "exchange.rate.refresh.cron=-",
        "exchange-rate.refresh.cron=-",
        "cdc.retry.cron=-",
        "escalation.sla.cron=-",
        "cash-advance.overdue.cron=-",
        "tax.category-sync.cron=-",
        "invoice-handoff.ar-delivery.retry-cron=-",
        "logging.level.org.springframework.security=WARN",
        "logging.level.org.springframework.security.oauth2.server.resource=WARN",
        "logging.level.org.springframework.web=WARN",
        "logging.level.com.expense_management_service=INFO"
})
@AutoConfigureMockMvc
abstract class E2eTestBase {

    /** A caller: the JWT claims {@code CurrentUserService} reads and the roles {@code @PreAuthorize} checks. */
    protected record Persona(String employeeId, UUID uuid, long umsUserId, List<String> roles) {
    }

    /** Approves every report (the catch-all flow's named approver) and, as manager, every cash advance. */
    protected static final Persona MANAGER = new Persona("MGR001", UUID.fromString("00000000-0000-0000-0000-00000000a001"), 9001L, List.of("MANAGER"));
    protected static final Persona FINANCE = new Persona("FIN001", UUID.fromString("00000000-0000-0000-0000-00000000a002"), 9002L,
            List.of("FINANCE_EXECUTIVE", "FINANCE"));
    protected static final Persona AP = new Persona("AP001", UUID.fromString("00000000-0000-0000-0000-00000000a003"), 9003L, List.of("AP_EXECUTIVE"));

    @Autowired protected MockMvc mockMvc;

    @Autowired protected CurrencyRepository currencyRepository;
    @Autowired protected GlAccountRepository glAccountRepository;
    @Autowired protected ExpenseCategoryRepository expenseCategoryRepository;
    @Autowired protected CostCenterRepository costCenterRepository;
    @Autowired protected CostCenterBudgetRepository costCenterBudgetRepository;
    @Autowired protected EmployeeCacheRepository employeeCacheRepository;
    @Autowired protected PolicyRepository policyRepository;
    @Autowired protected PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired protected ApprovalFlowRepository approvalFlowRepository;
    @Autowired protected CashAdvanceRepository cashAdvanceRepository;
    @Autowired protected AuditLogRepository auditLogRepository;

    @MockitoBean protected JwtDecoder jwtDecoder;
    @MockitoBean protected UmsClient umsClient;
    @MockitoBean protected PmsClient pmsClient;
    @MockitoBean protected RmsClient rmsClient;
    @MockitoBean protected DepartmentClient departmentClient;
    @MockitoBean protected S3Client s3Client;
    @MockitoBean protected S3Presigner s3Presigner;
    @MockitoBean protected TextractClient textractClient;
    @MockitoBean protected RabbitTemplate rabbitTemplate;
    @MockitoBean protected ExchangeRateRefreshScheduler exchangeRateRefreshScheduler;

    /** Active users UMS reports; report creation requires the caller to be one. */
    private final List<UmsUserResponse> umsUsers = new ArrayList<>();

    protected Currency inr;

    @BeforeEach
    void seedSharedMasterData() {
        umsUsers.clear();
        when(umsClient.getAllUsers()).thenAnswer(invocation -> List.copyOf(umsUsers));
        inr = currencyRepository.findByCurrencyCodeIgnoreCase("INR").orElseGet(() -> currencyRepository.save(
                Currency.builder().currencyCode("INR").currencyName("Indian Rupee").symbol("₹").decimalPlaces(2).status("ACTIVE").build()));
        if (policyAssignmentRepository.findFirstByAssignmentTypeAndStatus(PolicyAssignmentType.DEFAULT, "ACTIVE").isEmpty()) {
            Policy policy = policyRepository.save(Policy.builder().policyName("E2E default policy").status("ACTIVE").build());
            policyAssignmentRepository.save(PolicyAssignment.builder()
                    .assignmentType(PolicyAssignmentType.DEFAULT).policy(policy).status("ACTIVE").build());
        }
        if (approvalFlowRepository.findByIsCatchAllTrue().isEmpty()) {
            ApprovalFlow flow = ApprovalFlow.builder().name("E2E catch-all").isCatchAll(true).status("ACTIVE").build();
            flow.getLevels().add(level(flow, 1, "Manager Review", LevelType.APPROVAL, MANAGER.employeeId()));
            flow.getLevels().add(level(flow, 2, "Finance Verification", LevelType.FINANCE_VERIFICATION, FINANCE.employeeId()));
            approvalFlowRepository.save(flow);
        }
    }

    private static ApprovalLevel level(ApprovalFlow flow, int order, String name, LevelType type, String approverEmployeeId) {
        ApprovalLevel level = ApprovalLevel.builder().flow(flow).levelOrder(order).levelName(name)
                .quorum(LevelQuorum.ANY_OF).levelType(type).build();
        level.getApprovers().add(ApprovalLevelApprover.builder().level(level).entryOrder(1)
                .sourceType(ApproverSourceType.NAMED_USER).sourceReference(approverEmployeeId).build());
        return level;
    }

    // ---------------------------------------------------------------------
    // Per-test master data
    // ---------------------------------------------------------------------

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /** An active cost center in its own department; {@code budget == null} means unbudgeted spending is allowed. */
    protected CostCenter costCenter(String ownerEmployeeId, BigDecimal budget) {
        String sfx = suffix();
        CostCenter costCenter = costCenterRepository.save(CostCenter.builder()
                .costCenterCode("CC-" + sfx).costCenterName("Engineering " + sfx).departmentUuid(UUID.randomUUID())
                .ownerEmployeeId(ownerEmployeeId).status("ACTIVE").allowUnbudgeted(budget == null).build());
        if (budget != null) {
            costCenterBudgetRepository.save(CostCenterBudget.builder().costCenter(costCenter).fiscalYear(currentFiscalYear())
                    .budgetAmount(budget).availableBudget(budget)
                    .cashAdvanceAllocated(BigDecimal.ZERO).cashAdvanceFeeAllocated(BigDecimal.ZERO).build());
        }
        return costCenter;
    }

    protected ExpenseCategory category() {
        String sfx = suffix();
        GlAccount gl = glAccountRepository.save(GlAccount.builder()
                .glAccountCode("GL-" + sfx).glAccountName("Travel GL " + sfx).accountType("EXPENSE").status("ACTIVE").build());
        return expenseCategoryRepository.save(ExpenseCategory.builder()
                .categoryCode("CAT-" + sfx).categoryName("Travel " + sfx).glAccount(gl).receiptRequired(false)
                .effectiveFrom(LocalDate.now().minusYears(1)).status("ACTIVE").build());
    }

    /**
     * A new active employee in {@code costCenter}'s department, reporting to {@link #MANAGER}: known to UMS (so they
     * can create reports) and in the employee cache (so cash advances find their manager and cost center owner).
     */
    protected Persona employee(CostCenter costCenter) {
        Persona employee = new Persona("E" + suffix(), UUID.randomUUID(), 42L, List.of("GENERAL"));
        umsUsers.add(new UmsUserResponse(employee.uuid(), employee.umsUserId(), "Test", employee.employeeId(),
                employee.employeeId() + "@e2e.test", true));
        employeeCacheRepository.save(EmployeeCache.builder()
                .employeeId(employee.employeeId()).employeeUuid(employee.uuid().toString())
                .managerEmployeeId(MANAGER.employeeId()).departmentUuid(costCenter.getDepartmentUuid().toString())
                .employmentStatus("Active").build());
        return employee;
    }

    /** April-March, as the services compute it: "2026-2027" for any date from April 2026 to March 2027. */
    protected static String currentFiscalYear() {
        LocalDate today = LocalDate.now();
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return startYear + "-" + (startYear + 1);
    }

    // ---------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------

    protected static RequestPostProcessor as(Persona persona) {
        return jwt().jwt(j -> j.subject(persona.uuid().toString())
                        .claim("obs_user_uuid", persona.uuid().toString())
                        .claim("employee_id", persona.employeeId())
                        .claim("user_id", persona.umsUserId())
                        .claim("email", persona.employeeId() + "@e2e.test")
                        .claim("name", persona.employeeId())
                        .claim("roles", persona.roles())
                        .claim("permissions", List.of()))
                .authorities(persona.roles().stream()
                        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
    }

    /** Sends the request and returns the response body; fails with the body when the status isn't {@code expectedStatus}. */
    protected String call(AbstractMockHttpServletRequestBuilder<?> request, Persona persona, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request.with(as(persona))).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("%s %s -> %s", result.getRequest().getMethod(), result.getRequest().getRequestURI(), body)
                .isEqualTo(expectedStatus);
        return body;
    }

    protected String postJson(String path, Persona persona, String json, int expectedStatus) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(json), persona, expectedStatus);
    }

    protected String putJson(String path, Persona persona, String json, int expectedStatus) throws Exception {
        return call(put(path).contentType(MediaType.APPLICATION_JSON).content(json), persona, expectedStatus);
    }

    protected String getJson(String path, Persona persona) throws Exception {
        return call(get(path), persona, 200);
    }

    protected static <T> T read(String body, String path) {
        return JsonPath.read(body, path);
    }

    protected static BigDecimal amount(String body, String path) {
        Object value = JsonPath.read(body, path);
        return value == null ? null : new BigDecimal(value.toString());
    }

    // ---------------------------------------------------------------------
    // Expense report workflow steps
    // ---------------------------------------------------------------------

    /**
     * Creates a draft report for a trip that is today. A report linked to a cash advance must fall inside one month of
     * the advance request, and the advance is requested today, so the trip cannot start earlier. {@code cashAdvanceId} may be null.
     */
    protected String createReport(Persona employee, CostCenter costCenter, UUID cashAdvanceId) throws Exception {
        LocalDate today = LocalDate.now();
        String body = postJson("/xms/employee/expense-reports", employee, """
                {"title":"Client visit %s","businessPurpose":"Client visit to review the rollout plan",
                 "costCenterId":"%s","currencyId":"%s",%s
                 "tripStartDate":"%s","tripEndDate":"%s","reportingPeriodStart":"%s","reportingPeriodEnd":"%s"}
                """.formatted(suffix(), costCenter.getCostCenterId(), inr.getCurrencyId(),
                cashAdvanceId != null ? "\"cashAdvanceId\":\"" + cashAdvanceId + "\"," : "",
                today, today, today.minusDays(5), today.minusDays(1)), 201);
        return read(body, "$.data.reportId");
    }

    protected String addLine(Persona employee, String reportId, ExpenseCategory category, String amount, String extraJson) throws Exception {
        String body = postJson("/xms/employee/expense-reports/" + reportId + "/line-items", employee,
                lineJson(category, amount, "Taxi and meals", extraJson), 201);
        return read(body, "$.data.lineItemId");
    }

    protected String lineJson(ExpenseCategory category, String amount, String description, String extraJson) {
        return """
                {"categoryId":"%s","expenseDate":"%s","merchantName":"City Cabs","description":"%s",
                 "amount":%s,"currencyId":"%s","taxAmount":0%s}
                """.formatted(category.getCategoryId(), LocalDate.now(), description, amount,
                inr.getCurrencyId(), extraJson == null ? "" : "," + extraJson);
    }

    protected String submitReport(Persona employee, String reportId) throws Exception {
        return call(post("/xms/employee/expense-reports/" + reportId + "/submit"), employee, 200);
    }

    protected void managerReviews(String reportId, String lineItemId, String decision, String comment) throws Exception {
        postJson("/xms/approvals/" + reportId + "/line-items/" + lineItemId + "/review", MANAGER,
                "{\"decision\":\"%s\",\"comment\":%s}".formatted(decision, comment == null ? "null" : "\"" + comment + "\""), 200);
    }

    protected void financeVerifies(String reportId, String lineItemId) throws Exception {
        postJson("/xms/finance-verification/" + reportId + "/line-items/" + lineItemId + "/verify", FINANCE,
                "{\"taxChecked\":true}", 200);
    }

    protected String report(String reportId, Persona viewer) throws Exception {
        return getJson("/xms/employee/expense-reports/" + reportId, viewer);
    }

    protected String apCompletesPayment(String reportId, String amountPaid, String reference) throws Exception {
        return postJson("/xms/ap-payments/" + reportId + "/complete", AP, """
                {"paymentReference":"%s","paymentMethod":"BANK_TRANSFER","paymentDate":"%s","amountPaid":%s,"remarks":"NEFT"}
                """.formatted(reference, LocalDate.now(), amountPaid), 200);
    }

    /** Line approved by the manager and verified by Finance: the report ends APPROVED. */
    protected void approveAndVerify(String reportId, String lineItemId) throws Exception {
        managerReviews(reportId, lineItemId, "APPROVED", null);
        assertThat((String) read(report(reportId, FINANCE), "$.data.reportStatus")).isEqualTo("PENDING_FINANCE_VERIFICATION");
        financeVerifies(reportId, lineItemId);
        assertThat((String) read(report(reportId, FINANCE), "$.data.reportStatus")).isEqualTo("APPROVED");
    }
}
