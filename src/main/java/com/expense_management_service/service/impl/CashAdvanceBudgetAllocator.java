package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceFee;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.CashAdvanceFeeRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Allocates a cash advance's principal and fee against its cost center budget at final Finance
 * approval, and records the fee once.
 *
 * <p>Currency: the budget is held in the base currency. The principal is converted to base currency
 * with the stored exchange rate (the same rate source and rounding used across the system), and the
 * fee is calculated and recorded in base currency. The advance's stored {@code baseAmount} is not
 * used: for foreign-currency advances it holds the local amount, not a conversion.
 *
 * <p>Budget semantics (see {@link CostCenterBudget}):
 * <ul>
 *   <li>{@code availableBudget} is reduced by base principal + base fee in one step.</li>
 *   <li>{@code cashAdvanceAllocated} holds the base principal; {@code cashAdvanceFeeAllocated} holds the
 *       base fee. Each amount is counted once.</li>
 *   <li>The fee is also recorded as a company-paid expense report (CLOSED, zero reimbursable, no payment
 *       routing), in base currency. Budget is consumed here, once, at approval, so the report never
 *       triggers reservation or payment.</li>
 * </ul>
 *
 * <p>Concurrency: the budget row is locked with {@code PESSIMISTIC_WRITE} before the duplicate check, so
 * two final approvals on the same cost center run one after the other.
 *
 * <p>Must run inside the caller's transaction. Any exception rolls back the approval status and every
 * budget and expense write together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceBudgetAllocator {

    static final String FEE_RATE_CONFIG_KEY = "cash_advance.fee_rate";
    static final String FEE_CATEGORY_CONFIG_KEY = "cash_advance.fee_category_code";

    private final CostCenterBudgetRepository costCenterBudgetRepository;
    private final CashAdvanceFeeRepository cashAdvanceFeeRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateService exchangeRateService;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    /**
     * @param advance    the advance that has just received final approval; must have a cost center and currency
     * @param fiscalYear the fiscal year whose budget is charged (e.g. {@code 2026-2027})
     */
    public void allocateOnFinalApproval(CashAdvance advance, String fiscalYear) {
        CostCenter costCenter = advance.getCostCenter();

        if (advance.getAmount() == null || advance.getAmount().signum() <= 0) {
            throw new BusinessRuleViolationException("Cash advance amount must be greater than zero.");
        }

        // Lock first, check second. Checking for an existing fee before the lock would let two
        // concurrent approvals both see "no fee yet".
        Optional<CostCenterBudget> budget =
                costCenterBudgetRepository.findWithLockByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(
                        costCenter.getCostCenterId(), fiscalYear);

        if (cashAdvanceFeeRepository.existsByAdvance_AdvanceId(advance.getAdvanceId())) {
            log.info("Cash advance {} already has a recorded fee - skipping duplicate allocation.",
                    advance.getAdvanceId());
            return;
        }

        Currency base = resolveBaseCurrency();
        BigDecimal basePrincipal = toBaseCurrency(advance, base);

        BigDecimal rate = resolveFeeRate();
        BigDecimal baseFee = rate == null
                ? BigDecimal.ZERO
                : basePrincipal.multiply(rate).setScale(base.getDecimalPlaces(), RoundingMode.HALF_UP);

        if (budget.isEmpty()) {
            if (!Boolean.TRUE.equals(costCenter.getAllowUnbudgeted())) {
                throw new BusinessRuleViolationException(
                        "No Cost Center budget is configured for " + costCenter.getCostCenterCode()
                                + " for fiscal year " + fiscalYear + ".");
            }
            log.warn("No budget row for cost center {} in {}; advance {} is allocated without budget tracking.",
                    costCenter.getCostCenterCode(), fiscalYear, advance.getAdvanceId());
        } else {
            allocateAgainstBudget(budget.get(), basePrincipal, baseFee);
        }

        ExpenseReport feeReport = baseFee.signum() > 0
                ? createFeeReport(advance, baseFee, base, fiscalYear)
                : null;

        cashAdvanceFeeRepository.save(CashAdvanceFee.builder()
                .advance(advance)
                .costCenter(costCenter)
                .feeRate(rate == null ? BigDecimal.ZERO : rate)
                .amount(baseFee)
                .expenseReport(feeReport)
                .build());

        log.info("Cash advance {} allocated in {}: principal={} fee={} costCenter={} fiscalYear={}",
                advance.getAdvanceId(), base.getCurrencyCode(), basePrincipal, baseFee,
                costCenter.getCostCenterCode(), fiscalYear);
    }

    /**
     * Converts the advance's principal into base currency using the stored exchange rate for the
     * approval date. Throws when no rate is available, so the approval rolls back instead of
     * charging the budget in the wrong currency.
     */
    BigDecimal toBaseCurrency(CashAdvance advance, Currency base) {
        Currency local = advance.getCurrency();
        if (local == null || local.getCurrencyId() == null) {
            throw new BusinessRuleViolationException(
                    "Cash advance " + advance.getAdvanceId() + " has no currency; it cannot be charged to the budget.");
        }
        if (local.getCurrencyId().equals(base.getCurrencyId())) {
            return advance.getAmount().setScale(base.getDecimalPlaces(), RoundingMode.HALF_UP);
        }
        return exchangeRateService.convertAmount(
                advance.getAmount(), local.getCurrencyId(), base.getCurrencyId(), LocalDate.now());
    }

    Currency resolveBaseCurrency() {
        return currencyRepository.findByCurrencyCodeIgnoreCase(baseCurrencyCode)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Base currency '" + baseCurrencyCode + "' is not set up as a currency."));
    }

    private void allocateAgainstBudget(CostCenterBudget budget, BigDecimal basePrincipal, BigDecimal baseFee) {
        BigDecimal available = nz(budget.getAvailableBudget());
        BigDecimal total = basePrincipal.add(baseFee);

        if (total.compareTo(available) > 0) {
            throw new BusinessRuleViolationException(
                    "Cash advance principal " + basePrincipal + " plus fee " + baseFee + " totals " + total
                            + " in base currency, which exceeds the remaining Cost Center budget of " + available
                            + " (fiscal year " + budget.getFiscalYear() + ").");
        }

        budget.setAvailableBudget(available.subtract(total));
        budget.setCashAdvanceAllocated(nz(budget.getCashAdvanceAllocated()).add(basePrincipal));
        budget.setCashAdvanceFeeAllocated(nz(budget.getCashAdvanceFeeAllocated()).add(baseFee));
        costCenterBudgetRepository.save(budget);
    }

    private ExpenseReport createFeeReport(CashAdvance advance, BigDecimal baseFee, Currency base, String fiscalYear) {
        String categoryCode = requiredConfigValue(FEE_CATEGORY_CONFIG_KEY);
        ExpenseCategory category = expenseCategoryRepository.findFirstByCategoryCodeIgnoreCase(categoryCode)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Cash advance fee category '" + categoryCode + "' does not exist. Set "
                                + FEE_CATEGORY_CONFIG_KEY + " to an existing expense category code."));

        LocalDateTime now = LocalDateTime.now();
        String shortId = advance.getAdvanceId().toString().substring(0, 8).toUpperCase();

        ExpenseReport report = ExpenseReport.builder()
                .reportNumber("CAF-" + fiscalYear + "-" + shortId)
                .employeeId(advance.getEmployeeId())
                .title("Cash advance fee - " + shortId)
                .businessPurpose("Company-paid fee for cash advance " + advance.getAdvanceId())
                .fiscalYear(fiscalYear)
                .costCenter(advance.getCostCenter())
                .currency(base)
                .reportStatus(ReportStatus.CLOSED)
                .totalAmount(baseFee)
                .reimbursableAmount(BigDecimal.ZERO)
                .companyPaid(true)
                .paymentRoutingStatus(PaymentRoutingStatus.NONE)
                .version(0L)
                .submittedAt(now)
                .approvedAt(now)
                .closedAt(now)
                .build();
        ExpenseReport saved = expenseReportRepository.save(report);

        expenseLineItemRepository.save(ExpenseLineItem.builder()
                .report(saved)
                .category(category)
                .expenseDate(now.toLocalDate())
                .description("Cash advance fee for " + advance.getAdvanceId())
                .amount(baseFee)
                .currency(base)
                .baseAmount(baseFee)
                .netAmount(baseFee)
                .createdBy("SYSTEM")
                .build());

        return saved;
    }

    /**
     * Reads {@code cash_advance.fee_rate} as a fraction (0.02 = 2%). Returns null when unconfigured,
     * which means the fee is zero. A value that is set but invalid is an error, never a silent zero.
     */
    BigDecimal resolveFeeRate() {
        return systemConfigurationRepository.findByConfigKey(FEE_RATE_CONFIG_KEY)
                .map(SystemConfiguration::getConfigValue)
                .filter(v -> !v.isBlank())
                .map(CashAdvanceBudgetAllocator::parseRate)
                .orElse(null);
    }

    static BigDecimal parseRate(String raw) {
        BigDecimal rate;
        try {
            rate = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessRuleViolationException(
                    "Invalid " + FEE_RATE_CONFIG_KEY + " configuration value '" + raw + "'.");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessRuleViolationException(
                    FEE_RATE_CONFIG_KEY + " must be between 0 and 1 (for example 0.02 for 2%).");
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private String requiredConfigValue(String key) {
        return systemConfigurationRepository.findByConfigKey(key)
                .map(SystemConfiguration::getConfigValue)
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Configuration '" + key + "' is required to record a cash advance fee."));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
