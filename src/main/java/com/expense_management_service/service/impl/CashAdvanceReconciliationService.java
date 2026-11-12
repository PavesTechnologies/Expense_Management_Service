package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.CashAdvanceReconciliationLine;
import com.expense_management_service.entity.CashAdvanceReconciliationRun;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceReconciliationLineRepository;
import com.expense_management_service.repository.CashAdvanceReconciliationRunRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Finance reconciliation of cash advance balances. A snapshot is read-only. A run stores the snapshot for one period and
 * moves DRAFT, SUBMITTED, APPROVED, CLOSED. Flagged lines need a resolution note before approval. A note never changes
 * a balance, and a CLOSED run is immutable. Nothing in this service writes to {@code cash_advance}.
 */
@Service
@RequiredArgsConstructor
public class CashAdvanceReconciliationService {

    static final int MIN_NOTE_LENGTH = 10;

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceCarryForwardRepository carryForwardRepository;
    private final CashAdvanceRepaymentRepository repaymentRepository;
    private final CashAdvanceAdjustmentRepository adjustmentRepository;
    private final CashAdvanceReconciliationRunRepository runRepository;
    private final CashAdvanceReconciliationLineRepository lineRepository;
    private final CashAdvanceBalanceLedger balanceLedger;
    private final CurrentUserService currentUserService;

    /** Month (1 to 12) in which the financial year starts. Zero means Finance has not confirmed it. */
    @Value("${expense.finance-year-start-month:0}")
    private int financeYearStartMonth = 0;

    // ---------------------------------------------------------------- read-only snapshot

    @Transactional(readOnly = true)
    public Snapshot snapshot(String periodType, LocalDate anchor) {
        requireFinance();
        CashAdvanceReconciliationCalculator.Period period =
                CashAdvanceReconciliationCalculator.period(periodType, anchor, financeYearStartMonth);
        List<CashAdvanceReconciliationCalculator.LineSnapshot> lines = calculate(period);
        return new Snapshot(period.periodType(), period.start(), period.end(),
                lines.stream().map(l -> toLineView(null, l)).toList());
    }

    // ---------------------------------------------------------------- runs

    @Transactional
    public RunView createRun(String periodType, LocalDate anchor) {
        requireFinance();
        String actor = currentUserService.getEmployeeId();
        CashAdvanceReconciliationCalculator.Period period =
                CashAdvanceReconciliationCalculator.period(periodType, anchor, financeYearStartMonth);

        if (runRepository.findFirstByPeriodTypeAndPeriodStartAndStatusNot(
                period.periodType(), period.start(), CashAdvanceReconciliationRun.CLOSED).isPresent()) {
            throw new BusinessRuleViolationException("An open reconciliation run already exists for this period.");
        }

        CashAdvanceReconciliationRun run = CashAdvanceReconciliationRun.builder()
                .periodType(period.periodType())
                .periodStart(period.start())
                .periodEnd(period.end())
                .status(CashAdvanceReconciliationRun.DRAFT)
                .createdBy(actor)
                .createdAt(LocalDateTime.now())
                .build();
        for (CashAdvanceReconciliationCalculator.LineSnapshot snap : calculate(period)) {
            CashAdvanceReconciliationLine line = CashAdvanceReconciliationLine.builder()
                    .run(run)
                    .advance(snap.advance())
                    .employeeId(snap.employeeId())
                    .expectedClosing(snap.expected())
                    .storedClosing(snap.stored())
                    .discrepancy(snap.discrepancy())
                    .flagged(snap.flagged())
                    .laterMovements(snap.laterMovements())
                    .build();
            run.getLines().add(line);
        }
        CashAdvanceReconciliationRun saved = runRepository.save(run);
        balanceLedger.recordChange("CashAdvanceReconciliationRun", saved.getReconciliationRunId(), "RECONCILIATION_CREATED",
                null, "Status: DRAFT, Lines: " + saved.getLines().size(), actor, "Reconciliation run created");
        return toRunView(saved);
    }

    @Transactional(readOnly = true)
    public RunView getRun(UUID runId) {
        requireFinance();
        return toRunView(findRun(runId));
    }

    @Transactional
    public RunView resolveLine(UUID runId, UUID lineId, String note) {
        requireFinance();
        CashAdvanceReconciliationRun run = findRun(runId);
        requireOpen(run);
        CashAdvanceReconciliationLine line = run.getLines().stream()
                .filter(l -> l.getReconciliationLineId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation line not found: " + lineId));
        if (!line.isFlagged()) {
            throw new BusinessRuleViolationException("Only flagged lines need a resolution note.");
        }
        String trimmed = requireNote(note);
        String actor = currentUserService.getEmployeeId();
        line.setResolutionNote(trimmed);
        line.setResolvedBy(actor);
        line.setResolvedAt(LocalDateTime.now());
        balanceLedger.recordChange("CashAdvanceReconciliationRun", runId, "RECONCILIATION_LINE_RESOLVED",
                null, "Advance: " + line.getAdvance().getAdvanceId(), actor, trimmed);
        return toRunView(runRepository.save(run));
    }

    @Transactional
    public RunView submit(UUID runId) {
        requireFinance();
        CashAdvanceReconciliationRun run = findRun(runId);
        requireStatus(run, CashAdvanceReconciliationRun.DRAFT, "submitted");
        String actor = currentUserService.getEmployeeId();
        run.setStatus(CashAdvanceReconciliationRun.SUBMITTED);
        run.setSubmittedBy(actor);
        run.setSubmittedAt(LocalDateTime.now());
        balanceLedger.recordChange("CashAdvanceReconciliationRun", runId, "RECONCILIATION_SUBMITTED",
                "Status: DRAFT", "Status: SUBMITTED", actor, "Submitted for Finance approval");
        return toRunView(runRepository.save(run));
    }

    @Transactional
    public RunView approve(UUID runId) {
        requireFinance();
        CashAdvanceReconciliationRun run = findRun(runId);
        requireStatus(run, CashAdvanceReconciliationRun.SUBMITTED, "approved");
        String actor = currentUserService.getEmployeeId();
        if (actor == null || actor.equals(run.getCreatedBy()) || actor.equals(run.getSubmittedBy())) {
            throw new BusinessRuleViolationException("The person who created or submitted a reconciliation run cannot approve it.");
        }
        long unresolved = run.getLines().stream()
                .filter(l -> l.isFlagged() && (l.getResolutionNote() == null || l.getResolutionNote().isBlank()))
                .count();
        if (unresolved > 0) {
            throw new BusinessRuleViolationException(unresolved + " flagged line(s) need a resolution note before approval.");
        }
        run.setStatus(CashAdvanceReconciliationRun.APPROVED);
        run.setApprovedBy(actor);
        run.setApprovedAt(LocalDateTime.now());
        balanceLedger.recordChange("CashAdvanceReconciliationRun", runId, "RECONCILIATION_APPROVED",
                "Status: SUBMITTED", "Status: APPROVED", actor, "Approved by Finance");
        return toRunView(runRepository.save(run));
    }

    /** Closes an approved run. A closed run is immutable: no line, note or status can change after this. */
    @Transactional
    public RunView close(UUID runId) {
        requireFinance();
        CashAdvanceReconciliationRun run = findRun(runId);
        requireStatus(run, CashAdvanceReconciliationRun.APPROVED, "closed");
        String actor = currentUserService.getEmployeeId();
        run.setStatus(CashAdvanceReconciliationRun.CLOSED);
        run.setClosedBy(actor);
        run.setClosedAt(LocalDateTime.now());
        balanceLedger.recordChange("CashAdvanceReconciliationRun", runId, "RECONCILIATION_CLOSED",
                "Status: APPROVED", "Status: CLOSED", actor, "Period closed");
        return toRunView(runRepository.save(run));
    }

    // ---------------------------------------------------------------- helpers

    private List<CashAdvanceReconciliationCalculator.LineSnapshot> calculate(CashAdvanceReconciliationCalculator.Period period) {
        List<CashAdvance> advances = cashAdvanceRepository.findAll();
        return CashAdvanceReconciliationCalculator.snapshot(period.end(), advances,
                carryForwardRepository.findByStatusIn(List.of(
                        CashAdvanceCarryForward.APPLIED, CashAdvanceCarryForward.REVERSED)),
                repaymentRepository.findAll(),
                adjustmentRepository.findAll());
    }

    private CashAdvanceReconciliationRun findRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Reconciliation run not found: " + runId));
    }

    private static void requireOpen(CashAdvanceReconciliationRun run) {
        if (CashAdvanceReconciliationRun.CLOSED.equals(run.getStatus()) || CashAdvanceReconciliationRun.APPROVED.equals(run.getStatus())) {
            throw new BusinessRuleViolationException("A run in status " + run.getStatus() + " can no longer change.");
        }
    }

    private static void requireStatus(CashAdvanceReconciliationRun run, String expected, String verb) {
        if (!expected.equals(run.getStatus())) {
            throw new BusinessRuleViolationException("Only " + expected.toLowerCase(Locale.ROOT) + " runs can be " + verb
                    + ". Current status: " + run.getStatus());
        }
    }

    private static String requireNote(String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() < MIN_NOTE_LENGTH) {
            throw new IllegalArgumentException("Give a resolution note of at least " + MIN_NOTE_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static void requireFinance() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean finance = auth != null && auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_FINANCE".equals(role) || "ROLE_FINANCE_EXECUTIVE".equals(role) || "ROLE_ADMIN".equals(role);
        });
        if (!finance) {
            throw new BusinessRuleViolationException("Only Finance can run or approve reconciliation.");
        }
    }

    private static LineView toLineView(UUID lineId, CashAdvanceReconciliationCalculator.LineSnapshot l) {
        return new LineView(lineId, l.advance().getAdvanceId(), l.employeeId(), l.expected(), l.stored(), l.discrepancy(),
                l.flagged(), l.laterMovements(), null, null);
    }

    private static RunView toRunView(CashAdvanceReconciliationRun run) {
        List<LineView> lines = new ArrayList<>();
        for (CashAdvanceReconciliationLine l : run.getLines()) {
            lines.add(new LineView(l.getReconciliationLineId(), l.getAdvance().getAdvanceId(), l.getEmployeeId(),
                    l.getExpectedClosing(), l.getStoredClosing(), l.getDiscrepancy(), l.isFlagged(), l.getLaterMovements(),
                    l.getResolutionNote(), l.getResolvedBy()));
        }
        return new RunView(run.getReconciliationRunId(), run.getPeriodType(), run.getPeriodStart(), run.getPeriodEnd(),
                run.getStatus(), run.getCreatedBy(), run.getCreatedAt(), run.getApprovedBy(), run.getClosedBy(), lines);
    }

    public record Snapshot(String periodType, LocalDate periodStart, LocalDate periodEnd, List<LineView> lines) {
    }

    public record RunView(UUID runId, String periodType, LocalDate periodStart, LocalDate periodEnd, String status,
                          String createdBy, LocalDateTime createdAt, String approvedBy, String closedBy,
                          List<LineView> lines) {
    }

    public record LineView(UUID lineId, UUID advanceId, String employeeId, BigDecimal expectedClosing,
                           BigDecimal storedClosing, BigDecimal discrepancy, boolean flagged, int laterMovements,
                           String resolutionNote, String resolvedBy) {
    }
}
