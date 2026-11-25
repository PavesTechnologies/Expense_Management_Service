package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.dto.response.ItcRegisterResponse;
import com.expense_management_service.dto.response.TaxReportResponse;
import com.expense_management_service.dto.response.TaxReportResponse.Row;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.ItcStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.service.TaxAuditService;
import com.expense_management_service.service.TaxReportService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tax reporting on the line snapshots (BR-TAX-017: base currency). Grouping happens in the
 * database; only submitted lines count (drafts, cancelled and rejected reports are not spend). Lines
 * entered before tax codes group as LEGACY.
 */
@Service
@Transactional(readOnly = true)
public class TaxReportServiceImpl implements TaxReportService {

    private static final Set<ReportStatus> NOT_SUBMITTED = ReportStatus.excludedFromTaxReporting();
    private static final String LEGACY = "LEGACY";
    private static final String NO_CODE = "NONE";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String MEASURES = "count(l), sum(l.baseAmount), sum(coalesce(l.baseTaxAmount, 0)), "
            + "sum(coalesce(l.baseRecoverableTaxAmount, 0)), sum(coalesce(l.baseNetAmount, l.baseAmount))";

    @PersistenceContext
    private EntityManager em;

    @Value("${exchange.rate.base-currency:INR}")
    private String baseCurrencyCode;

    @Autowired
    private TaxAuditService taxAuditService;

    private static final String LINE_ENTITY = "ExpenseLineItem";

    @Override
    public TaxReportResponse report(LocalDate from, LocalDate to, String groupBy, UUID categoryId, UUID taxCodeId) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : YearMonth.from(end).minusMonths(11).atDay(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from cannot be after to");
        }
        String by = groupBy == null ? "month" : groupBy;
        List<Row> rows = switch (by) {
            case "month" -> byMonth(start, end, categoryId, taxCodeId);
            case "taxCode" -> byTaxCode(start, end, categoryId, taxCodeId);
            case "category" -> byCategory(start, end, categoryId, taxCodeId);
            case "component" -> byComponent(start, end, categoryId, taxCodeId);
            default -> throw new IllegalArgumentException("groupBy must be one of month, taxCode, component, category");
        };
        return new TaxReportResponse(start, end, by, baseCurrencyCode, rows, totals(rows, "component".equals(by)));
    }

    @Override
    public DashboardResponse.TaxSummary summary(int months) {
        YearMonth thisMonth = YearMonth.now();
        List<Row> series = byMonth(thisMonth.minusMonths(months - 1L).atDay(1), thisMonth.atEndOfMonth(), null, null);
        Row current = series.get(series.size() - 1);

        long awaitingReview = em.createQuery(
                        "select count(l) from ExpenseLineItem l"
                                + " where l.taxValidationStatus in :flagged and l.report.reportStatus = :pendingFinance", Long.class)
                .setParameter("flagged", EnumSet.of(TaxValidationStatus.REQUIRES_FINANCE_REVIEW, TaxValidationStatus.MISMATCH))
                .setParameter("pendingFinance", ReportStatus.PENDING_FINANCE_VERIFICATION)
                .getSingleResult();

        Object[] ocr = em.createQuery(
                        "select count(l), sum(case when l.taxValidationReasons like '%OCR_MISMATCH%' then 1 else 0 end)"
                                + " from ExpenseLineItem l where l.ocrTaxAmount is not null and l.expenseDate >= :from"
                                + " and l.report.reportStatus not in :notSubmitted", Object[].class)
                .setParameter("from", thisMonth.atDay(1)).setParameter("notSubmitted", NOT_SUBMITTED)
                .getSingleResult();
        long withOcr = ocr[0] == null ? 0 : ((Number) ocr[0]).longValue();
        long mismatched = ocr[1] == null ? 0 : ((Number) ocr[1]).longValue();

        return new DashboardResponse.TaxSummary(
                current.taxAmount(), current.recoverableTaxAmount(), current.nonRecoverableTaxAmount(),
                awaitingReview,
                percent(BigDecimal.valueOf(mismatched), BigDecimal.valueOf(withOcr)),
                percent(current.taxAmount(), current.grossAmount()),
                series.stream().map(r -> new DashboardResponse.TaxMonth(r.key(), r.label(), r.taxAmount(),
                        r.recoverableTaxAmount(), r.nonRecoverableTaxAmount())).toList());
    }

    @Override
    public ItcRegisterResponse itcRegister(LocalDate from, LocalDate to, ItcStatus itcStatus, UUID categoryId, UUID taxCodeId) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from cannot be after to");
        }
        List<ExpenseLineItem> taxed = bind(em.createQuery("select l from ExpenseLineItem l join fetch l.report left join fetch l.category"
                + where(categoryId, taxCodeId) + " and coalesce(l.baseTaxAmount, 0) > 0"
                + " order by l.expenseDate, l.report.reportNumber", ExpenseLineItem.class), start, end, categoryId, taxCodeId).getResultList();

        List<ItcRegisterResponse.Line> lines = taxed.stream()
                .map(TaxReportServiceImpl::itcLine)
                .filter(l -> itcStatus == null || itcStatus.name().equals(l.itcStatus()))
                .toList();
        return new ItcRegisterResponse(start, end, baseCurrencyCode, lines, itcTotals(lines));
    }

    private static ItcRegisterResponse.Line itcLine(ExpenseLineItem l) {
        BigDecimal tax = dec(l.getBaseTaxAmount());
        BigDecimal recoverable = dec(l.getBaseRecoverableTaxAmount());
        return new ItcRegisterResponse.Line(l.getLineItemId(), l.getReport().getReportId(), l.getReport().getReportNumber(),
                l.getReport().getEmployeeId(), l.getReport().getReportStatus() != null ? l.getReport().getReportStatus().name() : null,
                l.getExpenseDate(), l.getMerchantName(), l.getCategory() != null ? l.getCategory().getCategoryName() : null,
                l.getTaxCode(), l.getSupplierGstin(), tax, l.getItcRecoverablePercent(), recoverable, tax.subtract(recoverable),
                l.getTaxValidationStatus() != null ? l.getTaxValidationStatus().name() : null,
                ItcStatus.of(recoverable, l.getTaxValidationStatus()).name(),
                l.getItcClaimPeriod(), l.getItcClaimedAt(), l.getItcClaimedBy());
    }

    @Override
    @Transactional
    public int claimItc(List<UUID> lineItemIds, String returnPeriod, String note, String employeeId) {
        if (returnPeriod == null || !returnPeriod.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("Choose the GST return month (yyyy-MM) the credit was claimed in.");
        }
        List<ExpenseLineItem> lines = loadLines(lineItemIds);
        List<String> problems = new ArrayList<>();
        for (ExpenseLineItem l : lines) {
            String why = claimProblem(l);
            if (why != null) problems.add(label(l) + ": " + why);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("These lines cannot be claimed - " + String.join("; ", problems));
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (ExpenseLineItem l : lines) {
            Map<String, Object> before = claimState(l);
            l.setItcClaimPeriod(returnPeriod);
            l.setItcClaimedAt(now);
            l.setItcClaimedBy(employeeId);
            taxAuditService.record(LINE_ENTITY, l.getLineItemId(), "ITC_CLAIMED", before, claimState(l), AuditSource.FINANCE, note);
        }
        return lines.size();
    }

    @Override
    @Transactional
    public int unclaimItc(List<UUID> lineItemIds, String reason, String employeeId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Give a reason for undoing the claim.");
        }
        List<ExpenseLineItem> lines = loadLines(lineItemIds);
        List<String> notClaimed = lines.stream().filter(l -> l.getItcClaimPeriod() == null).map(TaxReportServiceImpl::label).toList();
        if (!notClaimed.isEmpty()) {
            throw new IllegalArgumentException("Not claimed, nothing to undo: " + String.join(", ", notClaimed));
        }
        for (ExpenseLineItem l : lines) {
            Map<String, Object> before = claimState(l);
            l.setItcClaimPeriod(null);
            l.setItcClaimedAt(null);
            l.setItcClaimedBy(null);
            taxAuditService.record(LINE_ENTITY, l.getLineItemId(), "ITC_CLAIM_UNDONE", before, claimState(l), AuditSource.FINANCE, reason.trim());
        }
        return lines.size();
    }

    private List<ExpenseLineItem> loadLines(List<UUID> lineItemIds) {
        if (lineItemIds == null || lineItemIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one line.");
        }
        Set<UUID> ids = Set.copyOf(lineItemIds);
        List<ExpenseLineItem> lines = em.createQuery("select l from ExpenseLineItem l join fetch l.report where l.lineItemId in :ids",
                ExpenseLineItem.class).setParameter("ids", ids).getResultList();
        if (lines.size() != ids.size()) {
            throw new IllegalArgumentException("Some of the selected lines no longer exist.");
        }
        return lines;
    }

    /** Why a line cannot be claimed, or null when it can. */
    static String claimProblem(ExpenseLineItem l) {
        if (l.getItcClaimPeriod() != null) return "already claimed in " + l.getItcClaimPeriod();
        ItcStatus status = ItcStatus.of(dec(l.getBaseRecoverableTaxAmount()), l.getTaxValidationStatus());
        if (status == ItcStatus.NOT_RECOVERABLE) return "no recoverable tax";
        if (status == ItcStatus.UNVERIFIED) return "Finance has not verified the tax yet";
        if (l.getSupplierGstin() == null || l.getSupplierGstin().isBlank()) return "no supplier GSTIN";
        return null;
    }

    private static String label(ExpenseLineItem l) {
        String report = l.getReport() != null ? l.getReport().getReportNumber() : "?";
        return report + " (" + (l.getMerchantName() != null ? l.getMerchantName() : l.getLineItemId()) + ")";
    }

    private static Map<String, Object> claimState(ExpenseLineItem l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itcClaimPeriod", l.getItcClaimPeriod());
        m.put("itcClaimedAt", l.getItcClaimedAt() == null ? null : l.getItcClaimedAt().toString());
        m.put("itcClaimedBy", l.getItcClaimedBy());
        m.put("recoverableTaxAmount", dec(l.getBaseRecoverableTaxAmount()).toPlainString());
        m.put("supplierGstin", l.getSupplierGstin());
        return m;
    }

    private static ItcRegisterResponse.Totals itcTotals(List<ItcRegisterResponse.Line> lines) {
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal verified = BigDecimal.ZERO;
        BigDecimal unverified = BigDecimal.ZERO;
        long withoutGstin = 0;
        for (ItcRegisterResponse.Line l : lines) {
            tax = tax.add(l.taxAmount());
            if (ItcStatus.VERIFIED.name().equals(l.itcStatus())) {
                verified = verified.add(l.recoverableTaxAmount());
            } else if (ItcStatus.UNVERIFIED.name().equals(l.itcStatus())) {
                unverified = unverified.add(l.recoverableTaxAmount());
            }
            if (!ItcStatus.NOT_RECOVERABLE.name().equals(l.itcStatus()) && l.supplierGstin() == null) {
                withoutGstin++;
            }
        }
        BigDecimal recoverable = verified.add(unverified);
        return new ItcRegisterResponse.Totals(lines.size(), tax, recoverable, verified, unverified, tax.subtract(recoverable), withoutGstin);
    }

    // ------------------------------------------------------------------ groupings

    private List<Row> byMonth(LocalDate from, LocalDate to, UUID categoryId, UUID taxCodeId) {
        List<Object[]> result = bind(em.createQuery("select year(l.expenseDate), month(l.expenseDate), " + MEASURES
                + " from ExpenseLineItem l" + where(categoryId, taxCodeId)
                + " group by year(l.expenseDate), month(l.expenseDate)", Object[].class), from, to, categoryId, taxCodeId).getResultList();
        // Every month in the window appears, so the series has no gaps.
        Map<YearMonth, Row> byMonth = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            byMonth.put(m, row(m.toString(), monthLabel(m), 0L, BigDecimal.ZERO, null, null, BigDecimal.ZERO));
        }
        for (Object[] r : result) {
            YearMonth m = YearMonth.of(((Number) r[0]).intValue(), ((Number) r[1]).intValue());
            byMonth.put(m, row(m.toString(), monthLabel(m), r[2], r[3], r[4], r[5], r[6]));
        }
        return new ArrayList<>(byMonth.values());
    }

    private List<Row> byTaxCode(LocalDate from, LocalDate to, UUID categoryId, UUID taxCodeId) {
        List<Object[]> result = bind(em.createQuery("select l.taxCode, l.taxSource, " + MEASURES
                + " from ExpenseLineItem l" + where(categoryId, taxCodeId) + " group by l.taxCode, l.taxSource",
                Object[].class), from, to, categoryId, taxCodeId).getResultList();
        // One code has several sources (calculated, override, adjusted): merge them per code.
        Map<String, Row> merged = new LinkedHashMap<>();
        for (Object[] r : result) {
            String key = r[0] != null ? (String) r[0] : (r[1] == TaxSource.LEGACY ? LEGACY : NO_CODE);
            String label = r[0] != null ? (String) r[0] : (LEGACY.equals(key) ? "Entered before tax codes" : "No tax code");
            merged.merge(key, row(key, label, r[2], r[3], r[4], r[5], r[6]), TaxReportServiceImpl::add);
        }
        return sortedByTax(merged.values());
    }

    private List<Row> byCategory(LocalDate from, LocalDate to, UUID categoryId, UUID taxCodeId) {
        List<Object[]> result = bind(em.createQuery("select c.categoryId, c.categoryName, " + MEASURES
                + " from ExpenseLineItem l left join l.category c" + where(categoryId, taxCodeId)
                + " group by c.categoryId, c.categoryName", Object[].class), from, to, categoryId, taxCodeId).getResultList();
        return sortedByTax(result.stream()
                .map(r -> row(r[0] == null ? NO_CODE : r[0].toString(), r[1] == null ? "Uncategorized" : (String) r[1],
                        r[2], r[3], r[4], r[5], r[6]))
                .toList());
    }

    /** Per component (CGST, SGST, IGST, CESS ...); recoverable uses each line's own ITC %. */
    private List<Row> byComponent(LocalDate from, LocalDate to, UUID categoryId, UUID taxCodeId) {
        List<Object[]> result = bind(em.createQuery("select tc.componentCode, count(distinct l), sum(coalesce(tc.baseTaxAmount, 0)),"
                + " sum(coalesce(tc.baseTaxAmount, 0) * coalesce(l.itcRecoverablePercent, 0) / 100)"
                + " from ExpenseLineTaxComponent tc join tc.lineItem l" + where(categoryId, taxCodeId)
                + " group by tc.componentCode", Object[].class), from, to, categoryId, taxCodeId).getResultList();
        return sortedByTax(result.stream()
                .map(r -> row(r[0].toString(), r[0].toString(), r[1], null, r[2], r[3], null))
                .toList());
    }

    // ------------------------------------------------------------------ helpers

    /** Shared filter: submitted lines in the date window, optionally one category / tax code. */
    private static String where(UUID categoryId, UUID taxCodeId) {
        return " where l.expenseDate >= :from and l.expenseDate <= :to and l.report.reportStatus not in :notSubmitted"
                + (categoryId != null ? " and l.category.categoryId = :categoryId" : "")
                + (taxCodeId != null ? " and l.taxCodeId = :taxCodeId" : "");
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> q, LocalDate from, LocalDate to, UUID categoryId, UUID taxCodeId) {
        q.setParameter("from", from).setParameter("to", to).setParameter("notSubmitted", NOT_SUBMITTED);
        if (categoryId != null) q.setParameter("categoryId", categoryId);
        if (taxCodeId != null) q.setParameter("taxCodeId", taxCodeId);
        return q;
    }

    /** gross / net null = not meaningful for the grouping (component view). */
    private static Row row(String key, String label, Object count, Object gross, Object tax, Object recoverable, Object net) {
        BigDecimal t = dec(tax);
        BigDecimal rec = dec(recoverable);
        return new Row(key, label, count == null ? 0 : ((Number) count).longValue(),
                gross == null ? null : dec(gross), t, rec, t.subtract(rec), net == null ? null : dec(net));
    }

    private static Row add(Row a, Row b) {
        return new Row(a.key(), a.label(), a.lineCount() + b.lineCount(), sum(a.grossAmount(), b.grossAmount()),
                sum(a.taxAmount(), b.taxAmount()), sum(a.recoverableTaxAmount(), b.recoverableTaxAmount()),
                sum(a.nonRecoverableTaxAmount(), b.nonRecoverableTaxAmount()), sum(a.netAmount(), b.netAmount()));
    }

    /** In the component view a line with two components counts once per component. */
    private static Row totals(List<Row> rows, boolean componentView) {
        Row zero = new Row("TOTAL", "Total", 0, componentView ? null : BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, componentView ? null : BigDecimal.ZERO);
        return rows.stream().reduce(zero, TaxReportServiceImpl::add);
    }

    private static List<Row> sortedByTax(Collection<Row> rows) {
        return rows.stream().sorted((x, y) -> y.taxAmount().compareTo(x.taxAmount())).toList();
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    private static BigDecimal dec(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) return BigDecimal.ZERO;
        return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static String monthLabel(YearMonth m) {
        return m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
