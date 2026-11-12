package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.enums.SplitType;
import com.expense_management_service.mapper.BudgetEncumbranceMapper;
import com.expense_management_service.mapper.CostCenterBudgetMapper;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import jakarta.persistence.EntityManager;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-readiness audit, Part 6 (Real Database Concurrency Testing) - the one section the prior
 * report left as a documented, untested limitation. Proves {@code BudgetEncumbranceServiceImpl
 * .validateAndEncumber}'s pessimistic-lock design against a REAL MySQL instance (the same Aiven
 * database this project's own {@code .env} already points local dev at - see {@link RealDbSmokeIT}
 * for the bare connectivity proof and the reasoning for this exact test-slice shape), not just
 * reasoned about from the code. H2 is deliberately not used anywhere here: its locking/MVCC semantics
 * do not match InnoDB's, so it cannot stand in for this proof.
 * <p>
 * Uses the REAL {@code CostCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase}
 * (backed by {@code @Lock(PESSIMISTIC_WRITE)}, i.e. a MySQL {@code SELECT ... FOR UPDATE}) and the REAL
 * {@code BudgetEncumbranceServiceImpl} bean (brought in via {@code @Import} since {@code @DataJpaTest}
 * otherwise only scans JPA infrastructure, not {@code @Service} beans) - this is not a reimplementation
 * of the locking logic, it is the production call path itself, exercised by two real concurrent
 * database transactions on two real threads with two independent connections.
 * <p>
 * Test data is entirely self-contained and self-cleaning: every row created here carries a random
 * {@link #marker} in its unique/code columns, and {@link #cleanUp()} deletes exactly those rows (by
 * id, in FK-safe order) after every test, touching nothing else in the shared remote schema.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none", // additive-only test - never let Hibernate touch the real schema
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // real DB, no embedded H2 swap-in
@ContextConfiguration(initializers = DotenvApplicationInitializer.class) // same .env loader main() wires manually -
                                                                          // see RealDbSmokeIT for why this is required
@Import({
        BudgetEncumbranceServiceImpl.class,
        CostCenterBudgetServiceImpl.class,
        BudgetEncumbranceMapper.class,
        CostCenterBudgetMapper.class
}) // the real production service beans, not a reimplementation of their locking logic
@Transactional(propagation = Propagation.NOT_SUPPORTED) // no auto-wrapping/rollback - each thread manages its own
class BudgetEncumbranceRealDbConcurrencyIT {

    @Autowired
    private CurrencyRepository currencyRepository;
    @Autowired
    private GlAccountRepository glAccountRepository;
    @Autowired
    private ExpenseCategoryRepository expenseCategoryRepository;
    @Autowired
    private CostCenterRepository costCenterRepository;
    @Autowired
    private CostCenterBudgetRepository costCenterBudgetRepository;
    @Autowired
    private ExpenseReportRepository expenseReportRepository;
    @Autowired
    private BudgetEncumbranceRepository budgetEncumbranceRepository;
    @Autowired
    private BudgetEncumbranceServiceImpl budgetEncumbranceService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    private TransactionTemplate txTemplate;
    private final String marker = "XIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

    private final List<UUID> reportIdsToClean = new ArrayList<>();
    private final List<UUID> budgetIdsToClean = new ArrayList<>();
    private final List<UUID> costCenterIdsToClean = new ArrayList<>();
    private UUID categoryIdToClean;
    private UUID glAccountIdToClean;
    private UUID currencyIdToClean;

    @BeforeEach
    void initTransactionTemplate() {
        txTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        txTemplate.executeWithoutResult(status -> {
            if (!reportIdsToClean.isEmpty()) {
                entityManager.createQuery("delete from BudgetEncumbrance e where e.report.reportId in :ids")
                        .setParameter("ids", reportIdsToClean).executeUpdate();
                entityManager.createQuery("delete from ExpenseSplit s where s.lineItem.report.reportId in :ids")
                        .setParameter("ids", reportIdsToClean).executeUpdate();
                entityManager.createQuery("delete from ExpenseLineItem li where li.report.reportId in :ids")
                        .setParameter("ids", reportIdsToClean).executeUpdate();
                entityManager.createQuery("delete from ExpenseReport r where r.reportId in :ids")
                        .setParameter("ids", reportIdsToClean).executeUpdate();
            }
            if (!budgetIdsToClean.isEmpty()) {
                entityManager.createQuery("delete from CostCenterBudget b where b.budgetId in :ids")
                        .setParameter("ids", budgetIdsToClean).executeUpdate();
            }
            if (!costCenterIdsToClean.isEmpty()) {
                entityManager.createQuery("delete from CostCenter c where c.costCenterId in :ids")
                        .setParameter("ids", costCenterIdsToClean).executeUpdate();
            }
            if (categoryIdToClean != null) {
                entityManager.createQuery("delete from ExpenseCategory c where c.categoryId = :id")
                        .setParameter("id", categoryIdToClean).executeUpdate();
            }
            if (glAccountIdToClean != null) {
                entityManager.createQuery("delete from GlAccount g where g.glAccountId = :id")
                        .setParameter("id", glAccountIdToClean).executeUpdate();
            }
            if (currencyIdToClean != null) {
                entityManager.createQuery("delete from Currency c where c.currencyId = :id")
                        .setParameter("id", currencyIdToClean).executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Test 1 - overspend prevention, no duplicate encumbrances, no partial state from the loser
    // ------------------------------------------------------------------------------------------

    /**
     * Two reports, same Cost Center/fiscal year, each needing 8000 against a 10000 available budget -
     * together they overspend it (16000 > 10000), but neither alone does. Both threads race to call
     * the REAL {@code validateAndEncumber} at the same instant (forced via a {@link CyclicBarrier}),
     * each in its own transaction/connection. {@code findWithLockByCostCenter_...}'s {@code SELECT
     * ... FOR UPDATE} must serialize the two: whichever commits first sees 10000 available and
     * succeeds; the second re-reads {@code effectiveAvailable} AFTER acquiring the lock (so it sees
     * the first's now-committed encumbrance) and must fail with "Insufficient budget" - its entire
     * transaction rolls back, so it leaves no partial encumbrance row behind.
     */
    @Test
    void concurrentValidateAndEncumber_cannotOverspendSharedBudget_noDuplicates_noPartialState() throws Exception {
        GlAccount glAccount = createGlAccount();
        Currency currency = createCurrency();
        ExpenseCategory category = createCategory(glAccount);
        CostCenter costCenter = createCostCenter("SHARED");
        CostCenterBudget budget = createBudget(costCenter, new BigDecimal("10000.0000"));

        UUID reportA = createUnsplitReport(costCenter, currency, category, "A", new BigDecimal("8000.0000"));
        UUID reportB = createUnsplitReport(costCenter, currency, category, "B", new BigDecimal("8000.0000"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptResult> futureA = pool.submit(() -> attemptEncumber(barrier, reportA, 1));
            Future<AttemptResult> futureB = pool.submit(() -> attemptEncumber(barrier, reportB, 1));

            AttemptResult resultA = futureA.get(30, TimeUnit.SECONDS);
            AttemptResult resultB = futureB.get(30, TimeUnit.SECONDS);

            List<AttemptResult> results = List.of(resultA, resultB);
            long successCount = results.stream().filter(AttemptResult::success).count();

            assertThat(successCount)
                    .as("Exactly one of the two concurrent 8000-against-10000 submissions must win: %s", results)
                    .isEqualTo(1);

            String failureMessage = results.stream()
                    .filter(r -> !r.success())
                    .findFirst()
                    .orElseThrow()
                    .errorMessage();
            assertThat(failureMessage).contains("Insufficient budget");

            List<BudgetEncumbrance> active = budgetEncumbranceRepository
                    .findByBudget_BudgetIdAndStatus(budget.getBudgetId(), BudgetEncumbranceStatus.ACTIVE);
            assertThat(active)
                    .as("No duplicate encumbrances and no partial state left by the losing transaction")
                    .hasSize(1);
            assertThat(active.get(0).getAmount()).isEqualByComparingTo("8000.0000");
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Test 2 - multi-Cost-Center deadlock avoidance via sorted-UUID lock ordering
    // ------------------------------------------------------------------------------------------

    /**
     * Two reports each need locks on the SAME two Cost Centers (A and B), but list their splits in
     * OPPOSITE order (X: A-then-B, Y: B-then-A) - if {@code validateAndEncumber} locked in
     * request/iteration order, this is the classic lock-ordering deadlock setup (X holds A waiting
     * for B while Y holds B waiting for A). Decision 7's fix sorts Cost Center UUIDs before locking,
     * so both threads must always acquire in the same order regardless of which report asks for
     * which first - this proves that against real InnoDB row locks, not just by reading the code.
     * Both requests fit the budget (3000+3000=6000 &lt;= 10000 per Cost Center), so a correct,
     * non-deadlocking implementation must have BOTH succeed, quickly.
     */
    @Test
    void concurrentValidateAndEncumber_acrossMultipleCostCentersInOppositeOrder_doesNotDeadlock() throws Exception {
        GlAccount glAccount = createGlAccount();
        Currency currency = createCurrency();
        ExpenseCategory category = createCategory(glAccount);
        CostCenter costCenterA = createCostCenter("DEADLOCK-A");
        CostCenter costCenterB = createCostCenter("DEADLOCK-B");
        createBudget(costCenterA, new BigDecimal("10000.0000"));
        CostCenterBudget budgetB = createBudget(costCenterB, new BigDecimal("10000.0000"));

        UUID reportX = createSplitReport(currency, category, "X", List.of(
                new SplitSpec(costCenterA, new BigDecimal("3000.0000")),
                new SplitSpec(costCenterB, new BigDecimal("3000.0000"))));
        UUID reportY = createSplitReport(currency, category, "Y", List.of(
                new SplitSpec(costCenterB, new BigDecimal("3000.0000")),
                new SplitSpec(costCenterA, new BigDecimal("3000.0000"))));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptResult> futureX = pool.submit(() -> attemptEncumber(barrier, reportX, 1));
            Future<AttemptResult> futureY = pool.submit(() -> attemptEncumber(barrier, reportY, 1));

            // A real deadlock would surface here as a TimeoutException (hang) or a lock-wait-timeout
            // exception surfacing inside AttemptResult - either way this test fails, loudly.
            AttemptResult resultX = futureX.get(25, TimeUnit.SECONDS);
            AttemptResult resultY = futureY.get(25, TimeUnit.SECONDS);

            assertThat(resultX.success()).as("Report X failed unexpectedly: %s", resultX.errorMessage()).isTrue();
            assertThat(resultY.success()).as("Report Y failed unexpectedly: %s", resultY.errorMessage()).isTrue();

            List<BudgetEncumbrance> activeB = budgetEncumbranceRepository
                    .findByBudget_BudgetIdAndStatus(budgetB.getBudgetId(), BudgetEncumbranceStatus.ACTIVE);
            assertThat(activeB).hasSize(2);
            assertThat(activeB.stream().map(BudgetEncumbrance::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("6000.0000");
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixture helpers - every row tagged with `marker`, tracked for cleanUp()
    // ------------------------------------------------------------------------------------------

    private record SplitSpec(CostCenter costCenter, BigDecimal amount) {
    }

    private record AttemptResult(boolean success, String errorMessage) {
        static AttemptResult ok() {
            return new AttemptResult(true, null);
        }

        static AttemptResult failed(String message) {
            return new AttemptResult(false, message);
        }
    }

    /** Runs the REAL production call path in its own transaction/connection, released only after both threads reach the barrier at the same instant. */
    private AttemptResult attemptEncumber(CyclicBarrier barrier, UUID reportId, int cycle) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            return AttemptResult.failed("barrier wait failed: " + e);
        }
        try {
            txTemplate.executeWithoutResult(status -> {
                ExpenseReport freshReport = expenseReportRepository.findById(reportId).orElseThrow();
                budgetEncumbranceService.validateAndEncumber(freshReport, cycle);
            });
            return AttemptResult.ok();
        } catch (Exception e) {
            return AttemptResult.failed(e.getMessage());
        }
    }

    private GlAccount createGlAccount() {
        GlAccount glAccount = GlAccount.builder()
                .glAccountCode(marker + "-GL")
                .glAccountName("Concurrency Test GL " + marker)
                .accountType("EXPENSE")
                .status("ACTIVE")
                .build();
        GlAccount saved = txTemplate.execute(status -> glAccountRepository.save(glAccount));
        glAccountIdToClean = saved.getGlAccountId();
        return saved;
    }

    private Currency createCurrency() {
        Currency currency = Currency.builder()
                .currencyCode(marker + "-CUR")
                .currencyName("Concurrency Test Currency " + marker)
                .symbol("$")
                .decimalPlaces(2)
                .status("ACTIVE")
                .build();
        Currency saved = txTemplate.execute(status -> currencyRepository.save(currency));
        currencyIdToClean = saved.getCurrencyId();
        return saved;
    }

    private ExpenseCategory createCategory(GlAccount glAccount) {
        ExpenseCategory category = ExpenseCategory.builder()
                .categoryCode(marker + "-CAT")
                .categoryName("Concurrency Test Category " + marker)
                .glAccount(glAccount)
                .receiptRequired(false)
                .effectiveFrom(LocalDate.now().minusYears(1))
                .status("ACTIVE")
                .build();
        ExpenseCategory saved = txTemplate.execute(status -> expenseCategoryRepository.save(category));
        categoryIdToClean = saved.getCategoryId();
        return saved;
    }

    private CostCenter createCostCenter(String suffix) {
        CostCenter costCenter = CostCenter.builder()
                .costCenterCode(marker + "-CC-" + suffix)
                .costCenterName("Concurrency Test CC " + suffix + " " + marker)
                .departmentUuid(UUID.randomUUID())
                .allowUnbudgeted(false)
                .status("ACTIVE")
                .build();
        CostCenter saved = txTemplate.execute(status -> costCenterRepository.save(costCenter));
        costCenterIdsToClean.add(saved.getCostCenterId());
        return saved;
    }

    private CostCenterBudget createBudget(CostCenter costCenter, BigDecimal amount) {
        CostCenterBudget budget = CostCenterBudget.builder()
                .costCenter(costCenter)
                .fiscalYear("2026")
                .budgetAmount(amount)
                .availableBudget(amount)
                .allowRollover(false)
                .build();
        CostCenterBudget saved = txTemplate.execute(status -> costCenterBudgetRepository.save(budget));
        budgetIdsToClean.add(saved.getBudgetId());
        return saved;
    }

    private UUID createUnsplitReport(CostCenter costCenter, Currency currency, ExpenseCategory category,
                                      String employeeSuffix, BigDecimal amount) {
        ExpenseReport report = ExpenseReport.builder()
                .reportNumber(marker + "-RPT-" + employeeSuffix)
                .employeeId(marker + "-EMP-" + employeeSuffix)
                .title("Concurrency Test Report " + employeeSuffix + " " + marker)
                .fiscalYear("2026")
                .costCenter(costCenter)
                .currency(currency)
                .totalAmount(amount)
                .reimbursableAmount(amount)
                .build();

        ExpenseLineItem lineItem = ExpenseLineItem.builder()
                .report(report)
                .category(category)
                .expenseDate(LocalDate.now())
                .amount(amount)
                .currency(currency)
                .baseAmount(amount)
                .netAmount(amount)
                .build();
        report.getExpenseLineItems().add(lineItem);

        ExpenseReport saved = txTemplate.execute(status -> expenseReportRepository.save(report));
        reportIdsToClean.add(saved.getReportId());
        return saved.getReportId();
    }

    private UUID createSplitReport(Currency currency, ExpenseCategory category, String employeeSuffix,
                                    List<SplitSpec> splitSpecs) {
        BigDecimal total = splitSpecs.stream().map(SplitSpec::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        ExpenseReport report = ExpenseReport.builder()
                .reportNumber(marker + "-RPT-" + employeeSuffix)
                .employeeId(marker + "-EMP-" + employeeSuffix)
                .title("Concurrency Test Split Report " + employeeSuffix + " " + marker)
                .fiscalYear("2026")
                .costCenter(splitSpecs.get(0).costCenter()) // header CC - irrelevant once split; resolveTargets ignores it
                .currency(currency)
                .totalAmount(total)
                .reimbursableAmount(total)
                .build();

        ExpenseLineItem lineItem = ExpenseLineItem.builder()
                .report(report)
                .category(category)
                .expenseDate(LocalDate.now())
                .amount(total)
                .currency(currency)
                .baseAmount(total)
                .netAmount(total)
                .build();
        report.getExpenseLineItems().add(lineItem);

        int order = 1;
        for (SplitSpec spec : splitSpecs) {
            ExpenseSplit split = ExpenseSplit.builder()
                    .lineItem(lineItem)
                    .costCenter(spec.costCenter())
                    .splitType(SplitType.FIXED_AMOUNT)
                    .allocatedAmount(spec.amount())
                    .splitOrder(order++)
                    .build();
            lineItem.getExpenseSplits().add(split);
        }

        ExpenseReport saved = txTemplate.execute(status -> expenseReportRepository.save(report));
        reportIdsToClean.add(saved.getReportId());
        return saved.getReportId();
    }
}
