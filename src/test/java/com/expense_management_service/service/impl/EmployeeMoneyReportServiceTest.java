package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EmployeeMoneyReportResponse.Group;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-employee rollup rules, with the shapes of real records (Sindhu's advances and reports). */
class EmployeeMoneyReportServiceTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static ExpenseReport report(ReportStatus status, PaymentRoutingStatus routing, String reimbursable, String paid, boolean linked) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).reportStatus(status).paymentRoutingStatus(routing)
                .reimbursableAmount(reimbursable == null ? null : bd(reimbursable)).paidAmount(paid == null ? null : bd(paid))
                .cashAdvance(linked ? CashAdvance.builder().advanceId(UUID.randomUUID()).build() : null).build();
    }

    private static CashAdvance paidOut(String amount, String cash, String carriedIn, String carriedOut) {
        return CashAdvance.builder().advanceId(UUID.randomUUID()).status("IN_PROGRESS").amount(bd(amount))
                .requestedAmount(bd(cash)).approvedAmount(bd(amount)).disbursedAmount(bd(cash)).disbursedAt(LocalDateTime.now())
                .carriedInAmount(carriedIn == null ? null : bd(carriedIn)).carriedOutAmount(carriedOut == null ? null : bd(carriedOut))
                .build();
    }

    private static CashAdvanceRepayment repayment(String amount, String method) {
        return CashAdvanceRepayment.builder().amount(bd(amount)).paymentMethod(method).build();
    }

    @Test
    void expenses_everyClaimedRupeeInOneBucket() {
        var acc = new EmployeeMoneyReportService.Acc("INR");
        acc.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0", null, true), bd("3450"), bd("0"), bd("0"));
        acc.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED, "1205", "1205", false), bd("1205"), bd("57.38"), bd("0"));
        acc.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0", null, false), bd("194.25"), bd("0"), bd("0"));
        acc.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "1000", "400", false), bd("1000"), bd("0"), bd("0"));
        acc.addReport(report(ReportStatus.PENDING_APPROVAL, PaymentRoutingStatus.NONE, null, null, false), bd("2142"), bd("102"), bd("50"));
        acc.addReport(report(ReportStatus.QUERY_RAISED, PaymentRoutingStatus.NONE, null, null, false), bd("100"), bd("0"), bd("0"));
        acc.addReport(report(ReportStatus.CANCELLED, PaymentRoutingStatus.NONE, null, null, false), bd("999"), bd("0"), bd("0"));

        Group g = acc.toGroup("5100014", "dept", "Sindhu", 1, null);
        eq("8091.25", g.expenses().claimed()); // the cancelled report is not claimed
        eq("2142", g.expenses().inApproval());
        eq("100", g.expenses().sentBack());
        eq("5849.25", g.expenses().approved());
        eq("3450", g.expenses().coveredByAdvance()); // linked to an advance
        eq("194.25", g.expenses().noPaymentDue());   // not linked: no payment due, not "covered by advance"
        eq("1605", g.expenses().paid());             // 1205 + 400 already paid of the 1000
        eq("600", g.expenses().due());
        eq("159.38", g.tax().total());
        eq("50", g.tax().recoverable());
        eq("109.38", g.tax().nonRecoverable());
        assertEquals(0, g.expenses().approved().compareTo(g.expenses().coveredByAdvance().add(g.expenses().noPaymentDue())
                .add(g.expenses().paid()).add(g.expenses().due())), "approved = covered + no payment + paid + due");
    }

    @Test
    void advances_heldEqualsWhereItWent_withSalaryDeductionApartFromRefunds() {
        var acc = new EmployeeMoneyReportService.Acc("INR");
        CashAdvance mumbai = paidOut("8000", "8000", null, null);
        CashAdvance visit = paidOut("3518", "2000", "1518", null);
        CashAdvance pune = paidOut("4000", "4000", null, "550");
        acc.addAdvance(mumbai, List.of(), List.of(repayment("2526", "PAYROLL")), BigDecimal.ZERO);
        acc.addAdvance(visit, List.of(), List.of(), bd("3518"));
        acc.addAdvance(pune, List.of(), List.of(repayment("100", "BANK_TRANSFER")), BigDecimal.ZERO);
        acc.addAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).status("PENDING_FINANCE_APPROVAL")
                .amount(bd("2000")).requestedAmount(bd("2000")).build(), List.of(), List.of(), null);
        acc.addAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).status("REJECTED")
                .amount(bd("500")).requestedAmount(bd("500")).build(), List.of(), List.of(), null);

        Group g = acc.toGroup("5100014", "dept", "Sindhu", 1, null);
        assertEquals(4, g.advances().count(), "the rejected advance is left out");
        eq("2000", g.advances().pendingApproval());
        eq("14000", g.advances().paidOut());
        eq("1518", g.advances().carriedIn());
        eq("2526", g.advances().salaryDeducted());
        eq("100", g.advances().refunded());
        eq("550", g.advances().carriedOut());
        eq("3518", g.advances().outstanding());
    }

    @Test
    void merge_addsEmployeesIntoTheirDepartmentAndTheTotal() {
        var a = new EmployeeMoneyReportService.Acc("INR");
        a.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED, "357", "357", false), bd("357"), bd("0"), bd("0"));
        var b = new EmployeeMoneyReportService.Acc("INR");
        b.addReport(report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED, "572", "572", false), bd("572"), bd("0"), bd("0"));
        var dept = new EmployeeMoneyReportService.Acc("INR");
        dept.merge(a);
        dept.merge(b);
        eq("929", dept.toGroup("dept", null, null, 2, null).expenses().paid());
    }

    @Test
    void timeline_picksDaysWeeksOrMonthsByRangeLength_andKeepsEmptyBuckets() {
        var to = LocalDate.of(2026, 11, 24);
        var paid = Map.of(LocalDate.of(2026, 11, 20), bd("5000"));
        var claimed = Map.of(LocalDate.of(2026, 11, 20), new BigDecimal[]{bd("1200"), bd("120"), bd("60")},
                LocalDate.of(2026, 11, 22), new BigDecimal[]{bd("300"), bd("30"), bd("0")});

        var week = EmployeeMoneyReportService.timeline(to.minusDays(6), to, paid, claimed);
        assertEquals(7, week.size());
        assertEquals("DAY", week.get(0).granularity());
        assertEquals("20 Nov", week.get(2).label());
        eq("5000", week.get(2).advancesPaidOut());
        eq("1200", week.get(2).expensesClaimed());
        eq("120", week.get(2).tax());
        eq("60", week.get(2).recoverableTax());
        eq("0", week.get(3).expensesClaimed());

        var ninety = EmployeeMoneyReportService.timeline(to.minusDays(89), to, paid, claimed);
        assertEquals("WEEK", ninety.get(0).granularity());
        var last = ninety.get(ninety.size() - 1);       // week of Mon 23 Nov
        var before = ninety.get(ninety.size() - 2);     // week of Mon 16 Nov holds 20 and 22 Nov
        assertEquals("2026-11-23", last.period());
        eq("1500", before.expensesClaimed());
        eq("150", before.tax());
        eq("5000", before.advancesPaidOut());

        var all = EmployeeMoneyReportService.timeline(null, to, Map.of(LocalDate.of(2026, 4, 3), bd("100")), claimed);
        assertEquals("MONTH", all.get(0).granularity());
        assertEquals("Apr 2026", all.get(0).label());
        assertEquals(8, all.size());
        eq("1500", all.get(7).expensesClaimed());
    }
}
