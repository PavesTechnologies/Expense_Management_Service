package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.LateReceiptResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.event.ReceiptUploadedEvent;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.ReceiptDuplicateService;
import com.expense_management_service.service.ReceiptDuplicateService.DuplicateMatch;
import com.expense_management_service.service.ReceiptDuplicateService.DuplicateSummary;
import com.expense_management_service.service.ReceiptDuplicateService.MatchReason;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Late and missing receipts: attaching a receipt after the report is approved, reimbursed or closed, and the Finance/AP
 * review that follows. None of these paths is allowed to change the expense's amount, status or payment.
 */
@ExtendWith(MockitoExtension.class)
class LateReceiptAttachmentTest {

    private static final String EMPLOYEE = "5100014";
    private static final String OTHER_EMPLOYEE = "9999999";
    private static final String FINANCE_USER = "FIN001";
    private static final String REASON = "The hotel sent the receipt two months after checkout";
    /** A receipt date that is in the past whenever the test runs, so the future-date rule never affects these cases. */
    private static final LocalDate RECEIPT_DATE = LocalDate.now().minusDays(30);

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock private StorageService storageService;
    @Mock private CurrentUserService currentUserService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ApprovalAssignmentRepository approvalAssignmentRepository;
    @Mock private DelegationService delegationService;
    @Mock private ReceiptDuplicateService receiptDuplicateService;
    @Mock private ReceiptOcrRepository receiptOcrRepository;
    @Mock private AuditLogRepository auditLogRepository;

    private ReceiptServiceImpl service;
    private UUID lineItemId;
    private UUID reportId;
    private ExpenseReport report;
    private ExpenseLineItem line;

    @BeforeEach
    void setUp() {
        service = new ReceiptServiceImpl(receiptRepository, expenseReportRepository, expenseLineItemRepository,
                storageService, currentUserService, new ReceiptMapper(), applicationEventPublisher,
                approvalAssignmentRepository, delegationService, receiptDuplicateService,
                receiptOcrRepository, auditLogRepository);
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "presignedUrlTtlMinutes", 15L);

        lineItemId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        report = ExpenseReport.builder().reportId(reportId).employeeId(EMPLOYEE)
                .reportNumber("EXP-001").reportStatus(ReportStatus.APPROVED).build();
        line = ExpenseLineItem.builder().lineItemId(lineItemId).report(report)
                .expenseDate(LocalDate.of(2026, 11, 5)).amount(new BigDecimal("2500.00"))
                .currency(Currency.builder().currencyCode("INR").build()).build();
        lenient().when(receiptDuplicateService.summarize(any())).thenReturn(DuplicateSummary.NONE);
    }

    private void asCaller(String employeeId, String role) {
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(
                UUID.randomUUID(), null, employeeId, employeeId + "@example.com", "Test User", List.of(role), List.of()));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "hotel-receipt.pdf", "application/pdf", "%PDF-1.4 late hotel receipt".getBytes());
    }

    private void stubSave() {
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });
    }

    private Receipt lateReceipt(String uploadedBy) {
        return Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(line).employeeId(EMPLOYEE)
                .originalFileName("hotel-receipt.pdf").uploadedBy(uploadedBy).uploadedAt(java.time.LocalDateTime.now())
                .attachmentKind(Receipt.ATTACHMENT_LATE).verificationStatus(Receipt.VERIFICATION_PENDING)
                .actualReceiptDate(RECEIPT_DATE).lateReason(REASON).build();
    }

    // ---------------------------------------------------------------- attach

    @Test
    void attach_storesALateReceipt_onAnApprovedReport_andLeavesTheExpenseUnchanged() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));
        when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of());
        stubSave();

        LocalDate printed = RECEIPT_DATE;
        LateReceiptResponse response = service.attachLate(lineItemId, pdf(), printed, REASON);

        assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);
        assertThat(response.actualReceiptDate()).isEqualTo(printed);
        assertThat(response.expenseDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(response.lineAmount()).isEqualByComparingTo("2500.00");
        assertThat(response.lateReason()).isEqualTo(REASON);
        assertThat(response.uploadedBy()).isEqualTo(EMPLOYEE);
        // The claim is untouched: amount and report status stay as they were.
        assertThat(line.getAmount()).isEqualByComparingTo("2500.00");
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.APPROVED);
        // OCR starts automatically, as for any upload, and the attempt is audited.
        verify(applicationEventPublisher).publishEvent(any(ReceiptUploadedEvent.class));
        verify(auditLogRepository).save(argThat((AuditLog a) -> "LATE_RECEIPT_ATTACHED".equals(a.getAction())
                && REASON.equals(a.getReason())));
    }

    @Test
    void attach_isRefused_onADraftReport_andNothingIsStored() {
        asCaller(EMPLOYEE, "GENERAL");
        report.setReportStatus(ReportStatus.DRAFT);
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("upload the receipt normally");
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_isAllowed_onReimbursedAndClosedReports() {
        for (ReportStatus status : List.of(ReportStatus.REIMBURSED, ReportStatus.CLOSED)) {
            asCaller(EMPLOYEE, "GENERAL");
            report.setReportStatus(status);
            when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));
            when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of());
            stubSave();

            LateReceiptResponse response = service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON);

            assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);
        }
    }

    @Test
    void attach_isRefused_forAFutureReceiptDate() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), LocalDate.now().plusDays(3), REASON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be in the future");
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_requiresTheReceiptDate_andAReasonOfAtLeastTenCharacters() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), null, REASON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date printed");
        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, "  short  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 10 characters");
        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 10 characters");
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_isRefused_forAnotherEmployeesReport() {
        asCaller(OTHER_EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON))
                .isInstanceOf(AccessDeniedException.class);
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_isRefused_whenTheSameFileIsAlreadyOnThisExpense() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));
        Receipt sameLine = Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(line).employeeId(EMPLOYEE).build();
        when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of(sameLine));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already attached to this expense");
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_isRefused_whenTheSameFileIsClaimedOnAnotherSubmittedReport() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));
        ExpenseReport other = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .reportNumber("EXP-009").reportStatus(ReportStatus.PENDING_APPROVAL).build();
        ExpenseLineItem otherLine = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(other).build();
        Receipt claimed = Receipt.builder().receiptId(UUID.randomUUID()).report(other).lineItem(otherLine).employeeId(EMPLOYEE).build();
        when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of(claimed));

        assertThatThrownBy(() -> service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("claimed on expense report EXP-009");
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void attach_isAllowed_whenTheSameFileOnlyExistsOnADraftReport() {
        asCaller(EMPLOYEE, "GENERAL");
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(line));
        ExpenseReport draft = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .reportNumber("EXP-004").reportStatus(ReportStatus.DRAFT).build();
        ExpenseLineItem draftLine = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(draft).build();
        Receipt unclaimed = Receipt.builder().receiptId(UUID.randomUUID()).report(draft).lineItem(draftLine).employeeId(EMPLOYEE).build();
        when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of(unclaimed));
        stubSave();

        LateReceiptResponse response = service.attachLate(lineItemId, pdf(), RECEIPT_DATE, REASON);

        assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);
    }

    // ---------------------------------------------------------------- review

    @Test
    void verify_byFinance_marksTheReceiptVerified_andAuditsTheDecision() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "FINANCE");

        LateReceiptResponse response = service.verifyLate(receipt.getReceiptId(), "Matches the hotel folio");

        assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_VERIFIED);
        assertThat(response.verifiedBy()).isEqualTo(FINANCE_USER);
        assertThat(response.verificationNote()).isEqualTo("Matches the hotel folio");
        assertThat(line.getAmount()).isEqualByComparingTo("2500.00");
        verify(auditLogRepository).save(argThat((AuditLog a) -> "LATE_RECEIPT_VERIFIED".equals(a.getAction())));
    }

    @Test
    void verify_isRefused_forTheUploader_andForTheEmployeeWhoseExpenseItIs() {
        Receipt receipt = lateReceipt(FINANCE_USER);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "FINANCE");
        assertThatThrownBy(() -> service.verifyLate(receipt.getReceiptId(), null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("your own expense");

        Receipt employeesOwn = lateReceipt("CLERK01");
        when(receiptRepository.findById(employeesOwn.getReceiptId())).thenReturn(Optional.of(employeesOwn));
        asCaller(EMPLOYEE, "FINANCE");
        assertThatThrownBy(() -> service.verifyLate(employeesOwn.getReceiptId(), null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("your own expense");
    }

    @Test
    void verify_isRefused_forAnEmployeeWithoutAReviewRole() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(OTHER_EMPLOYEE, "GENERAL");

        assertThatThrownBy(() -> service.verifyLate(receipt.getReceiptId(), "Looks fine"))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("Only Finance or AP");
    }

    @Test
    void verify_isRefused_whileTheReceiptMatchesAClaimedOne() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "AP_EXECUTIVE");
        DuplicateMatch blocking = new DuplicateMatch(receipt.getReceiptId(), "hotel-receipt.pdf", lineItemId,
                MatchReason.SAME_FILE, UUID.randomUUID(), UUID.randomUUID(), "EXP-009", true, false,
                "This exact receipt file has already been claimed on your expense report EXP-009.");
        when(receiptDuplicateService.findMatches(receipt)).thenReturn(List.of(blocking));

        assertThatThrownBy(() -> service.verifyLate(receipt.getReceiptId(), "Verified"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Reject this receipt instead");
        assertThat(receipt.getVerificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);
    }

    @Test
    void verify_needsANote_whenTheReceiptTotalDiffersFromTheClaim() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "FINANCE");
        ReceiptOcr ocr = ReceiptOcr.builder().amount(new BigDecimal("2600.00")).currencyCode("INR")
                .processingStatus(OcrStatus.OCR_COMPLETED).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receipt.getReceiptId()))
                .thenReturn(Optional.of(ocr));

        assertThatThrownBy(() -> service.verifyLate(receipt.getReceiptId(), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("2600.00").hasMessageContaining("2500.00");
        assertThat(receipt.getVerificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);

        LateReceiptResponse response = service.verifyLate(receipt.getReceiptId(), "Hotel tax was added on checkout");
        assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_VERIFIED);
    }

    @Test
    void verify_isRefused_onceTheReceiptHasAlreadyBeenDecided() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        receipt.setVerificationStatus(Receipt.VERIFICATION_REJECTED);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "FINANCE");

        assertThatThrownBy(() -> service.verifyLate(receipt.getReceiptId(), "Changed my mind"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("already been rejected");
    }

    @Test
    void reject_requiresAReason_andKeepsTheReceiptAsHistory() {
        Receipt receipt = lateReceipt(EMPLOYEE);
        when(receiptRepository.findById(receipt.getReceiptId())).thenReturn(Optional.of(receipt));
        asCaller(FINANCE_USER, "FINANCE_EXECUTIVE");

        assertThatThrownBy(() -> service.rejectLate(receipt.getReceiptId(), "no"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(receiptRepository, never()).save(any(Receipt.class));

        LateReceiptResponse response = service.rejectLate(receipt.getReceiptId(), "Receipt total does not match the folio");

        assertThat(response.verificationStatus()).isEqualTo(Receipt.VERIFICATION_REJECTED);
        assertThat(receipt.getVerificationNote()).isEqualTo("Receipt total does not match the folio");
        verify(auditLogRepository).save(argThat((AuditLog a) -> "LATE_RECEIPT_REJECTED".equals(a.getAction())));
    }

    @Test
    void reviewQueue_isOnlyForFinanceAndAp() {
        asCaller(EMPLOYEE, "GENERAL");
        assertThatThrownBy(() -> service.getPendingLateReceipts()).isInstanceOf(AccessDeniedException.class);

        asCaller(FINANCE_USER, "AP_EXECUTIVE");
        Receipt pending = lateReceipt(EMPLOYEE);
        when(receiptRepository.findByAttachmentKindAndVerificationStatusOrderByUploadedAtAsc(
                Receipt.ATTACHMENT_LATE, Receipt.VERIFICATION_PENDING)).thenReturn(List.of(pending));

        assertThat(service.getPendingLateReceipts()).hasSize(1);
    }

    @Test
    void reportView_listsOnlyLateReceipts() {
        asCaller(EMPLOYEE, "GENERAL");
        Receipt original = Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(line).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_ORIGINAL).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(receiptRepository.findByReport_ReportId(reportId)).thenReturn(List.of(original, lateReceipt(EMPLOYEE)));

        List<LateReceiptResponse> late = service.getLateForReport(reportId);

        assertThat(late).hasSize(1);
        assertThat(late.get(0).verificationStatus()).isEqualTo(Receipt.VERIFICATION_PENDING);
    }
}
