package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.ReceiptReleaseDecisionRequest;
import com.expense_management_service.dto.request.ReceiptReleaseRequest;
import com.expense_management_service.dto.request.SupplementaryPaymentConfirmRequest;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptRelease;
import com.expense_management_service.entity.SupplementaryClaimItem;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import com.expense_management_service.entity.SupplementaryPayment;
import com.expense_management_service.enums.PaymentMethod;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.ApPaymentBatchRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptReleaseRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.repository.SupplementaryClaimHistoryRepository;
import com.expense_management_service.repository.SupplementaryExpenseClaimRepository;
import com.expense_management_service.repository.SupplementaryPaymentRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.PermissionConstants;
import com.expense_management_service.service.NotificationService;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Payment confirmation, receipt attachment and two-person receipt release. Mocks only; no database. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplementaryPaymentReceiptReleaseTest {

    private static final String EMPLOYEE = "EMP1";
    private static final String FINANCE_APPROVER = "FIN1";
    private static final String AP_USER = "AP1";

    @Mock SupplementaryPaymentRepository paymentRepository;
    @Mock SupplementaryExpenseClaimRepository claimRepository;
    @Mock SupplementaryClaimHistoryRepository historyRepository;
    @Mock ApPaymentBatchRepository apPaymentBatchRepository;
    @Mock ExpenseReportRepository expenseReportRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock NotificationService notificationService;
    @Mock CurrentUserService currentUserService;
    @Mock ReceiptRepository receiptRepository;
    @Mock ReceiptReleaseRepository releaseRepository;
    @Mock StorageService storageService;
    @Mock org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @Mock com.expense_management_service.repository.ReceiptOcrRepository receiptOcrRepository;

    @InjectMocks SupplementaryPaymentServiceImpl paymentService;
    @InjectMocks SupplementaryReceiptServiceImpl receiptService;
    @InjectMocks ReceiptReleaseServiceImpl releaseService;

    // ---------------------------------------------------------------- fixtures

    private SupplementaryExpenseClaim approvedClaim() {
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).reportNumber("RPT-1")
                .employeeId(EMPLOYEE).reportStatus(ReportStatus.REIMBURSED).build();
        return SupplementaryExpenseClaim.builder()
                .claimId(UUID.randomUUID())
                .claimNumber("SUP-1")
                .original(report)
                .employeeId(EMPLOYEE)
                .createdBy(EMPLOYEE)
                .currencyCode("INR")
                .totalAmount(new BigDecimal("100.0000"))
                .status(SupplementaryExpenseClaim.PAYMENT_PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();
    }

    private SupplementaryPayment requestedPayment(SupplementaryExpenseClaim claim) {
        return SupplementaryPayment.builder()
                .paymentId(UUID.randomUUID())
                .claim(claim)
                .amount(new BigDecimal("100.0000"))
                .currencyCode("INR")
                .status(SupplementaryPayment.REQUESTED)
                .requestedBy(FINANCE_APPROVER)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    private SupplementaryPaymentConfirmRequest confirmation(String reference, BigDecimal amount) {
        return new SupplementaryPaymentConfirmRequest(reference, PaymentMethod.values()[0], LocalDate.now(), amount, null);
    }

    private SupplementaryClaimItem item(UUID id) {
        return SupplementaryClaimItem.builder().claimItemId(id).expenseDate(LocalDate.now()).build();
    }

    // ---------------------------------------------------------------- AP payment

    @Test
    void confirmingPaymentSettlesTheClaimOnlyOnce() {
        SupplementaryExpenseClaim claim = approvedClaim();
        SupplementaryPayment payment = requestedPayment(claim);
        when(paymentRepository.findByIdForUpdate(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(AP_USER);
        when(currentUserService.getActorId()).thenReturn(AP_USER);
        when(paymentRepository.save(any(SupplementaryPayment.class))).thenAnswer(i -> i.getArgument(0));
        when(claimRepository.save(any(SupplementaryExpenseClaim.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0001", new BigDecimal("100")));

        assertEquals(SupplementaryPayment.PAID, payment.getStatus());
        assertEquals(SupplementaryExpenseClaim.SETTLED, claim.getStatus());
        assertEquals(true, claim.getSettledAt() != null);

        // A second confirmation of the same payment must not record anything again.
        assertThrows(BusinessRuleViolationException.class, () ->
                paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0002", new BigDecimal("100"))));
    }

    @Test
    void employeeCannotConfirmTheirOwnPayment() {
        SupplementaryExpenseClaim claim = approvedClaim();
        SupplementaryPayment payment = requestedPayment(claim);
        when(paymentRepository.findByIdForUpdate(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);

        assertThrows(AccessDeniedException.class, () ->
                paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0003", new BigDecimal("100"))));
    }

    @Test
    void financeApproverCannotAlsoConfirmThePayment() {
        SupplementaryExpenseClaim claim = approvedClaim();
        SupplementaryPayment payment = requestedPayment(claim);
        when(paymentRepository.findByIdForUpdate(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE_APPROVER);
        when(currentUserService.getActorId()).thenReturn(FINANCE_APPROVER);

        assertThrows(AccessDeniedException.class, () ->
                paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0004", new BigDecimal("100"))));
    }

    @Test
    void amountMustMatchTheClaimWithinTolerance() {
        SupplementaryExpenseClaim claim = approvedClaim();
        SupplementaryPayment payment = requestedPayment(claim);
        when(paymentRepository.findByIdForUpdate(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(AP_USER);
        when(currentUserService.getActorId()).thenReturn(AP_USER);

        assertThrows(BusinessRuleViolationException.class, () ->
                paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0005", new BigDecimal("90"))));
        assertEquals(SupplementaryExpenseClaim.PAYMENT_PENDING, claim.getStatus());
    }

    @Test
    void bankReferenceAlreadyUsedByAnotherPaymentIsRefused() {
        SupplementaryExpenseClaim claim = approvedClaim();
        SupplementaryPayment payment = requestedPayment(claim);
        when(paymentRepository.findByIdForUpdate(payment.getPaymentId())).thenReturn(Optional.of(payment));
        when(claimRepository.findByIdForUpdate(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(AP_USER);
        when(currentUserService.getActorId()).thenReturn(AP_USER);
        when(paymentRepository.existsByBankReference(anyString())).thenReturn(true);

        assertThrows(BusinessRuleViolationException.class, () ->
                paymentService.confirm(payment.getPaymentId(), confirmation("BANK-REF-0006", new BigDecimal("100"))));
    }

    @Test
    void approvalCreatesOnePaymentRequestPerClaim() {
        SupplementaryExpenseClaim claim = approvedClaim();
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE_APPROVER);
        when(currentUserService.getActorId()).thenReturn(FINANCE_APPROVER);
        when(paymentRepository.existsByClaim_ClaimId(claim.getClaimId())).thenReturn(false);
        when(paymentRepository.save(any(SupplementaryPayment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.requestPayment(claim);
        verify(paymentRepository).save(any(SupplementaryPayment.class));

        when(paymentRepository.existsByClaim_ClaimId(claim.getClaimId())).thenReturn(true);
        assertThrows(BusinessRuleViolationException.class, () -> paymentService.requestPayment(claim));
    }

    // ---------------------------------------------------------------- receipts

    @Test
    void onlyPdfAndImageReceiptsAreAccepted() {
        SupplementaryExpenseClaim claim = approvedClaim();
        claim.setStatus(SupplementaryExpenseClaim.DRAFT);
        UUID itemId = UUID.randomUUID();
        claim.getItems().add(item(itemId));
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        assertThrows(BusinessRuleViolationException.class, () ->
                receiptService.upload(claim.getClaimId(), itemId, text));
    }

    @Test
    void originalReceiptCannotBeAttachedWithoutFinanceRelease() {
        SupplementaryExpenseClaim claim = approvedClaim();
        claim.setStatus(SupplementaryExpenseClaim.DRAFT);
        UUID itemId = UUID.randomUUID();
        claim.getItems().add(item(itemId));
        Receipt original = Receipt.builder().receiptId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_ORIGINAL).fileHash("h1").build();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);
        when(receiptRepository.findByIdForUpdate(original.getReceiptId())).thenReturn(Optional.of(original));
        when(releaseRepository.findFirstByReceiptIdOrderByDecidedAtDesc(original.getReceiptId())).thenReturn(Optional.empty());

        assertThrows(BusinessRuleViolationException.class, () ->
                receiptService.attach(claim.getClaimId(), itemId, original.getReceiptId()));
        assertNull(original.getSupplementaryClaimItem());
    }

    @Test
    void releasedReceiptAttachesOnceAndIsConsumed() {
        SupplementaryExpenseClaim claim = approvedClaim();
        claim.setStatus(SupplementaryExpenseClaim.DRAFT);
        UUID itemId = UUID.randomUUID();
        claim.getItems().add(item(itemId));
        Receipt original = Receipt.builder().receiptId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_ORIGINAL).fileHash("h2").build();
        ReceiptRelease release = ReceiptRelease.builder().releaseId(UUID.randomUUID()).receiptId(original.getReceiptId())
                .status(ReceiptRelease.RELEASED).requestedBy("A").requestedAt(LocalDateTime.now()).build();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);
        when(receiptRepository.findByIdForUpdate(original.getReceiptId())).thenReturn(Optional.of(original));
        when(releaseRepository.findFirstByReceiptIdOrderByDecidedAtDesc(original.getReceiptId())).thenReturn(Optional.of(release));

        receiptService.attach(claim.getClaimId(), itemId, original.getReceiptId());

        assertEquals(itemId, original.getSupplementaryClaimItem().getClaimItemId());
        assertEquals(true, release.getConsumedAt() != null);

        // The same receipt cannot attach a second time.
        assertThrows(BusinessRuleViolationException.class, () ->
                receiptService.attach(claim.getClaimId(), itemId, original.getReceiptId()));
    }

    @Test
    void suggestionShowsProcessingUntilOcrFinishes() {
        Receipt mine = Receipt.builder().receiptId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_SUPPLEMENTARY).build();
        when(receiptRepository.findById(mine.getReceiptId())).thenReturn(Optional.of(mine));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(mine.getReceiptId()))
                .thenReturn(Optional.empty());

        var suggestion = receiptService.suggestionFor(mine.getReceiptId());

        assertEquals("PROCESSING", suggestion.ocrStatus());
        assertNull(suggestion.amount());
    }

    @Test
    void receiptOfAnotherEmployeeCannotBeAttached() {
        SupplementaryExpenseClaim claim = approvedClaim();
        claim.setStatus(SupplementaryExpenseClaim.DRAFT);
        UUID itemId = UUID.randomUUID();
        claim.getItems().add(item(itemId));
        Receipt theirs = Receipt.builder().receiptId(UUID.randomUUID()).employeeId("EMP2")
                .attachmentKind(Receipt.ATTACHMENT_SUPPLEMENTARY).build();
        when(claimRepository.findById(claim.getClaimId())).thenReturn(Optional.of(claim));
        when(currentUserService.getEmployeeId()).thenReturn(EMPLOYEE);
        when(currentUserService.getActorId()).thenReturn(EMPLOYEE);
        when(receiptRepository.findByIdForUpdate(theirs.getReceiptId())).thenReturn(Optional.of(theirs));

        assertThrows(AccessDeniedException.class, () ->
                receiptService.attach(claim.getClaimId(), itemId, theirs.getReceiptId()));
    }

    // ---------------------------------------------------------------- two-person release

    @Test
    void releaseRequestNeedsTheRejectedReceiptAndAReason() {
        Receipt accepted = Receipt.builder().receiptId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_ORIGINAL).verificationStatus(Receipt.VERIFICATION_VERIFIED).build();
        when(currentUserService.getPermissions()).thenReturn(List.of(PermissionConstants.RECEIPT_RELEASE_REQUEST));
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE_APPROVER);
        when(currentUserService.getActorId()).thenReturn(FINANCE_APPROVER);
        when(receiptRepository.findById(accepted.getReceiptId())).thenReturn(Optional.of(accepted));

        assertThrows(BusinessRuleViolationException.class, () ->
                releaseService.request(new ReceiptReleaseRequest(accepted.getReceiptId(), "Valid reason text")));
    }

    @Test
    void requesterCannotApproveTheirOwnRelease() {
        ReceiptRelease release = ReceiptRelease.builder().releaseId(UUID.randomUUID()).receiptId(UUID.randomUUID())
                .requestedBy(FINANCE_APPROVER).requestedAt(LocalDateTime.now()).status(ReceiptRelease.REQUESTED).build();
        when(currentUserService.getPermissions()).thenReturn(List.of(PermissionConstants.RECEIPT_RELEASE_APPROVE));
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE_APPROVER);
        when(currentUserService.getActorId()).thenReturn(FINANCE_APPROVER);
        when(releaseRepository.findByIdForUpdate(release.getReleaseId())).thenReturn(Optional.of(release));

        assertThrows(AccessDeniedException.class, () ->
                releaseService.approve(release.getReleaseId(), new ReceiptReleaseDecisionRequest(null)));
        assertEquals(ReceiptRelease.REQUESTED, release.getStatus());
    }

    @Test
    void differentFinanceUserReleasesAndTheRequestIsKept() {
        ReceiptRelease release = ReceiptRelease.builder().releaseId(UUID.randomUUID()).receiptId(UUID.randomUUID())
                .requestedBy(FINANCE_APPROVER).requestedAt(LocalDateTime.now()).requestReason("Reason text here")
                .status(ReceiptRelease.REQUESTED).build();
        when(currentUserService.getPermissions()).thenReturn(List.of(PermissionConstants.RECEIPT_RELEASE_APPROVE));
        when(currentUserService.getEmployeeId()).thenReturn("FIN2");
        when(releaseRepository.findByIdForUpdate(release.getReleaseId())).thenReturn(Optional.of(release));
        when(releaseRepository.save(any(ReceiptRelease.class))).thenAnswer(i -> i.getArgument(0));

        var response = releaseService.approve(release.getReleaseId(), new ReceiptReleaseDecisionRequest("Checked"));

        assertEquals(ReceiptRelease.RELEASED, response.status());
        assertEquals("FIN2", response.decidedBy());
        assertEquals("Reason text here", response.requestReason());
    }

    @Test
    void releaseFailsClosedWithoutTheApprovePermission() {
        when(currentUserService.getPermissions()).thenReturn(List.of(PermissionConstants.RECEIPT_RELEASE_REQUEST));

        assertThrows(AccessDeniedException.class, () ->
                releaseService.approve(UUID.randomUUID(), new ReceiptReleaseDecisionRequest(null)));
    }
}
