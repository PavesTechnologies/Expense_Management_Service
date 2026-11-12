package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.TaxJournalResponse;
import com.expense_management_service.dto.response.TaxJournalResponse.Entry;
import com.expense_management_service.dto.response.TaxJournalResponse.Journal;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.TaxJournalService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxJournalServiceImpl implements TaxJournalService {

    /** system_configuration key for the employee payable account code (falls back to EMPLOYEE_PAYABLE). */
    static final String EMPLOYEE_PAYABLE_KEY = "TAX_JOURNAL_EMPLOYEE_PAYABLE_GL";
    private static final String UNMAPPED = "UNMAPPED";

    @PersistenceContext
    private EntityManager em;

    private final TaxCodeRepository taxCodeRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;

    @Value("${exchange.rate.base-currency:INR}")
    private String baseCurrencyCode;

    @Override
    public TaxJournalResponse export(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from cannot be after to");
        }
        List<ExpenseReport> reports = em.createQuery("select r from ExpenseReport r"
                        + " where r.approvedAt >= :from and r.approvedAt < :to and r.paymentRoutingStatus <> :none"
                        + " order by r.approvedAt", ExpenseReport.class)
                .setParameter("from", start.atStartOfDay()).setParameter("to", end.plusDays(1).atStartOfDay())
                .setParameter("none", PaymentRoutingStatus.NONE)
                .getResultList();

        Map<UUID, Optional<GlAccount>> inputTaxGl = new LinkedHashMap<>();
        String payable = systemConfigurationRepository.findByConfigKey(EMPLOYEE_PAYABLE_KEY)
                .map(SystemConfiguration::getConfigValue).filter(v -> !v.isBlank()).orElse("EMPLOYEE_PAYABLE");
        Set<String> warnings = new LinkedHashSet<>();

        List<Journal> journals = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (ExpenseReport report : reports) {
            Journal journal = journalFor(report, payable, inputTaxGl, warnings);
            journals.add(journal);
            totalDebit = totalDebit.add(journal.totalDebit());
            totalCredit = totalCredit.add(journal.totalCredit());
        }
        return new TaxJournalResponse(start, end, baseCurrencyCode, journals, totalDebit, totalCredit, List.copyOf(warnings));
    }

    private Journal journalFor(ExpenseReport report, String payableAccount, Map<UUID, Optional<GlAccount>> inputTaxGl, Set<String> warnings) {
        // Debits keyed by account, in first-seen order: expense GLs, then input tax GLs.
        Map<String, Entry> expense = new LinkedHashMap<>();
        Map<String, Entry> inputTax = new LinkedHashMap<>();
        BigDecimal gross = BigDecimal.ZERO;

        for (ExpenseLineItem line : report.getExpenseLineItems()) {
            BigDecimal lineGross = nz(line.getBaseAmount());
            BigDecimal recoverable = nz(line.getBaseRecoverableTaxAmount());
            gross = gross.add(lineGross);

            GlAccount expenseGl = line.getCategory() != null ? line.getCategory().getGlAccount() : null;
            if (expenseGl == null) {
                warnings.add(report.getReportNumber() + ": category " + (line.getCategory() != null ? line.getCategory().getCategoryName() : "(none)")
                        + " has no GL account");
            }
            // Net + non-recoverable tax is the expense; the recoverable share is an asset.
            debit(expense, expenseGl, "EXPENSE", lineGross.subtract(recoverable), "Expense incl. non-recoverable tax");

            if (recoverable.signum() > 0) {
                GlAccount taxGl = line.getTaxCodeId() == null ? null : inputTaxGl
                        .computeIfAbsent(line.getTaxCodeId(), id -> taxCodeRepository.findById(id).map(TaxCode::getInputTaxGlAccount))
                        .orElse(null);
                if (taxGl == null) {
                    warnings.add(report.getReportNumber() + ": tax code " + line.getTaxCode() + " has no input tax GL account");
                }
                debit(inputTax, taxGl, "INPUT_TAX", recoverable, "Recoverable input tax (" + line.getTaxCode() + ")");
            }
        }

        List<Entry> entries = new ArrayList<>(expense.values());
        entries.addAll(inputTax.values());
        entries.add(new Entry(payableAccount, "Employee payable", "PAYABLE", BigDecimal.ZERO, gross,
                "Reimbursement due to " + report.getEmployeeId()));
        BigDecimal debits = entries.stream().map(Entry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().map(Entry::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Journal(report.getReportId(), report.getReportNumber(), report.getEmployeeId(),
                report.getApprovedAt().toLocalDate(), entries, debits, credits, debits.compareTo(credits) == 0);
    }

    private static void debit(Map<String, Entry> byAccount, GlAccount gl, String type, BigDecimal amount, String memo) {
        if (amount.signum() == 0) {
            return;
        }
        String code = gl != null ? gl.getGlAccountCode() : UNMAPPED;
        String name = gl != null ? gl.getGlAccountName() : "Unmapped account";
        byAccount.merge(code, new Entry(code, name, type, amount, BigDecimal.ZERO, memo),
                (a, b) -> new Entry(a.accountCode(), a.accountName(), a.entryType(), a.debit().add(b.debit()), BigDecimal.ZERO, a.memo()));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
