package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.event.ReceiptUploadedEvent;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.storage.StorageException;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceImplTest {

    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private ExpenseReportRepository expenseReportRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private ReceiptServiceImpl receiptService;

    private final String employeeId = "5100014";
    private UUID lineItemId;
    private UUID reportId;
    private ExpenseReport draftReport;
    private ExpenseLineItem lineItem;

    @BeforeEach
    void setUp() {
        receiptService = new ReceiptServiceImpl(receiptRepository, expenseReportRepository, expenseLineItemRepository,
                storageService, currentUserService, new ReceiptMapper(), applicationEventPublisher);
        ReflectionTestUtils.setField(receiptService, "maxFileSizeBytes", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(receiptService, "presignedUrlTtlMinutes", 15L);

        lineItemId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus(ReportStatus.DRAFT).build();
        lineItem = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport).build();
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of());
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "taxi-receipt.pdf", "application/pdf", "%PDF-1.4 dummy receipt content".getBytes());
    }

    private void stubOwnerAndReport() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
    }

    private Receipt.ReceiptBuilder receiptOnReport() {
        return Receipt.builder().report(draftReport).employeeId(employeeId);
    }

    @Test
    void upload_savesReceiptMetadata_andUploadsToStorage_whenValid() {
        stubOwnerAndReport();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile file = pdfFile();
        ReceiptResponse response = receiptService.upload(reportId, file);

        assertThat(response.originalFileName()).isEqualTo("taxi-receipt.pdf");
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.uploadedBy()).isEqualTo(employeeId);
        assertThat(response.reportId()).isEqualTo(reportId);
        assertThat(response.lineItemId()).isNull();
        verify(storageService).upload(argThatKeyContains("receipts/" + employeeId + "/" + reportId + "/"), eq(file));

        ArgumentCaptor<ReceiptUploadedEvent> eventCaptor = ArgumentCaptor.forClass(ReceiptUploadedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().receiptId()).isEqualTo(response.receiptId());
    }

    @Test
    void upload_neverCallsOcrDirectly_onlyPublishesEvent() {
        // Decoupling check: ReceiptServiceImpl must have no way to call OCR directly — the only
        // interaction with anything OCR-related is publishing a plain event.
        stubOwnerAndReport();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        receiptService.upload(reportId, pdfFile());

        verify(applicationEventPublisher).publishEvent(any(ReceiptUploadedEvent.class));
        verifyNoMoreInteractions(applicationEventPublisher);
    }

    @Test
    void upload_stillSucceeds_evenThoughEventPublishingIsFireAndForget() {
        stubOwnerAndReport();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        ReceiptResponse response = receiptService.upload(reportId, pdfFile());

        assertThat(response.originalFileName()).isEqualTo("taxi-receipt.pdf");
    }

    private String argThatKeyContains(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(key -> key != null && key.startsWith(prefix));
    }

    @Test
    void upload_rollsBackS3Object_whenMetadataSaveFails() {
        stubOwnerAndReport();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> receiptService.upload(reportId, pdfFile()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storageService).delete(anyString());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenFileEmpty() {
        stubOwnerAndReport();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> receiptService.upload(reportId, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenFileTooLarge() {
        stubOwnerAndReport();
        ReflectionTestUtils.setField(receiptService, "maxFileSizeBytes", 5L);

        assertThatThrownBy(() -> receiptService.upload(reportId, pdfFile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed size");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenContentTypeNotAllowed() {
        stubOwnerAndReport();
        MockMultipartFile exe = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "content".getBytes());

        assertThatThrownBy(() -> receiptService.upload(reportId, exe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenExtensionMismatchesContentType() {
        stubOwnerAndReport();
        // Spoofed content-type but a disallowed extension
        MockMultipartFile spoofed = new MockMultipartFile("file", "script.js", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> receiptService.upload(reportId, spoofed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenBytesDoNotMatchDeclaredType() {
        stubOwnerAndReport();
        // Extension AND content-type both claim PDF (e.g. a renamed executable), but the
        // actual bytes don't carry the PDF magic number â€” this must still be caught.
        MockMultipartFile fakePdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ-not-really-a-pdf".getBytes());

        assertThatThrownBy(() -> receiptService.upload(reportId, fakePdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(storageService, never()).upload(anyString(), any());
    }

    /** Issue 8: WEBP receipts must be accepted for upload, storage, and viewing like any other image type. */
    @Test
    void upload_savesReceiptMetadata_whenWebpFile() {
        stubOwnerAndReport();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });
        byte[] webpBytes = new byte[] {
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x00, 0x00
        };
        MockMultipartFile webpFile = new MockMultipartFile("file", "receipt.webp", "image/webp", webpBytes);

        ReceiptResponse response = receiptService.upload(reportId, webpFile);

        assertThat(response.contentType()).isEqualTo("image/webp");
        verify(storageService).upload(anyString(), eq(webpFile));
    }

    @Test
    void upload_throwsIllegalArgumentException_whenWebpBytesLackRiffOrWebpMarker() {
        stubOwnerAndReport();
        // Declares WEBP but is missing the "WEBP" marker at offset 8 — only "RIFF" is present.
        byte[] notActuallyWebp = new byte[] {0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile fakeWebp = new MockMultipartFile("file", "receipt.webp", "image/webp", notActuallyWebp);

        assertThatThrownBy(() -> receiptService.upload(reportId, fakeWebp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsBusinessRuleViolation_whenReportNotEditable() {
        draftReport.setReportStatus(ReportStatus.PENDING_APPROVAL);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> receiptService.upload(reportId, pdfFile()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> receiptService.upload(reportId, pdfFile()))
                .isInstanceOf(AccessDeniedException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsResourceNotFoundException_whenReportMissing() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptService.upload(reportId, pdfFile()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadForLineItem_savesReceiptMetadata_associatedWithReportAndLineItem_whenValid() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        ArgumentCaptor<Receipt> receiptCaptor = ArgumentCaptor.forClass(Receipt.class);
        when(receiptRepository.saveAndFlush(receiptCaptor.capture())).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile file = pdfFile();
        ReceiptResponse response = receiptService.uploadForLineItem(lineItemId, file);

        assertThat(response.originalFileName()).isEqualTo("taxi-receipt.pdf");
        assertThat(response.reportId()).isEqualTo(reportId);
        assertThat(response.lineItemId()).isEqualTo(lineItemId);
        assertThat(receiptCaptor.getValue().getReport()).isSameAs(draftReport);
        assertThat(receiptCaptor.getValue().getLineItem()).isSameAs(lineItem);
        verify(storageService).upload(argThatKeyContains("receipts/" + employeeId + "/" + reportId + "/" + lineItemId + "/"), eq(file));

        ArgumentCaptor<ReceiptUploadedEvent> eventCaptor = ArgumentCaptor.forClass(ReceiptUploadedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().receiptId()).isEqualTo(response.receiptId());
    }

    @Test
    void uploadForLineItem_doesNotDeleteExistingReceipts_whenLineItemAlreadyHasReceipts() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        Receipt existingReceipt = Receipt.builder()
                .receiptId(UUID.randomUUID())
                .report(draftReport)
                .lineItem(lineItem)
                .employeeId(employeeId)
                .originalFileName("existing.pdf")
                .storedFileName("existing.pdf")
                .objectKey("key1")
                .build();

        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile file = pdfFile();
        ReceiptResponse response = receiptService.uploadForLineItem(lineItemId, file);

        assertThat(response.originalFileName()).isEqualTo("taxi-receipt.pdf");
        assertThat(response.lineItemId()).isEqualTo(lineItemId);
        verify(receiptRepository, never()).delete(any());
        verify(receiptRepository, never()).deleteById(any());
    }

    @Test
    void uploadForLineItem_rollsBackS3Object_whenMetadataSaveFails() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storageService).delete(anyString());
    }

    @Test
    void uploadForLineItem_doesNotCreateDbRecord_whenStorageUploadFails() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        org.mockito.Mockito.doThrow(new StorageException("s3 down")).when(storageService).upload(anyString(), any());

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(StorageException.class);

        verify(receiptRepository, never()).saveAndFlush(any());
    }

    @Test
    void uploadForLineItem_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(AccessDeniedException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_throwsBusinessRuleViolation_whenReportNotEditable() {
        draftReport.setReportStatus(ReportStatus.SUBMITTED);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_throwsResourceNotFoundException_whenLineItemMissing() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void uploadForLineItem_throwsIllegalArgumentException_whenFileEmpty() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_throwsIllegalArgumentException_whenFileTooLarge() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        ReflectionTestUtils.setField(receiptService, "maxFileSizeBytes", 5L);

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, pdfFile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed size");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_throwsIllegalArgumentException_whenContentTypeNotAllowed() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        MockMultipartFile exe = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "content".getBytes());

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, exe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_throwsIllegalArgumentException_whenBytesDoNotMatchDeclaredType() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        MockMultipartFile fakePdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ-not-really-a-pdf".getBytes());

        assertThatThrownBy(() -> receiptService.uploadForLineItem(lineItemId, fakePdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void uploadForLineItem_thenGetAllForLineItem_returnsTheUploadedReceipt() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        UUID receiptId = UUID.randomUUID();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(receiptId);
            return saved;
        });
        ReceiptResponse uploaded = receiptService.uploadForLineItem(lineItemId, pdfFile());

        Receipt persisted = receiptOnReport().receiptId(receiptId).lineItem(lineItem)
                .originalFileName("taxi-receipt.pdf").objectKey("key").build();
        when(receiptRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(persisted));

        List<ReceiptResponse> receipts = receiptService.getAllForLineItem(lineItemId);

        assertThat(receipts).extracting(ReceiptResponse::receiptId).containsExactly(uploaded.receiptId());
        assertThat(receipts).extracting(ReceiptResponse::lineItemId).containsExactly(lineItemId);
    }

    @Test
    void getAllForReport_returnsMetadata_whenOwner() {
        stubOwnerAndReport();
        Receipt receipt = receiptOnReport().receiptId(UUID.randomUUID())
                .originalFileName("a.pdf").objectKey("key").build();
        when(receiptRepository.findByReport_ReportId(reportId)).thenReturn(List.of(receipt));

        assertThat(receiptService.getAllForReport(reportId)).hasSize(1);
    }

    @Test
    void getAllForReport_throwsAccessDenied_whenNotOwner() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> receiptService.getAllForReport(reportId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAllForLineItem_returnsMetadata_whenOwner() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
        Receipt receipt = receiptOnReport().receiptId(UUID.randomUUID()).lineItem(lineItem)
                .originalFileName("a.pdf").objectKey("key").build();
        when(receiptRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(receipt));

        assertThat(receiptService.getAllForLineItem(lineItemId)).hasSize(1);
    }

    @Test
    void getViewUrl_delegatesToStorageServiceWithInlineDisposition() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = receiptOnReport().receiptId(receiptId)
                .originalFileName("a.pdf").objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        when(storageService.generateViewUrl(eq("receipts/key"), any(Duration.class))).thenReturn("https://signed-view-url");

        ReceiptUrlResponse response = receiptService.getViewUrl(receiptId);

        assertThat(response.url()).isEqualTo("https://signed-view-url");
    }

    @Test
    void getDownloadUrl_passesOriginalFileNameToStorageService() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = receiptOnReport().receiptId(receiptId)
                .originalFileName("taxi-receipt.pdf").objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        when(storageService.generateDownloadUrl(eq("receipts/key"), eq("taxi-receipt.pdf"), any(Duration.class)))
                .thenReturn("https://signed-download-url");

        ReceiptUrlResponse response = receiptService.getDownloadUrl(receiptId);

        assertThat(response.url()).isEqualTo("https://signed-download-url");
    }

    @Test
    void delete_removesS3ObjectAndMetadata_whenOwnerAndReportEditable() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = receiptOnReport().receiptId(receiptId).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        receiptService.delete(receiptId);

        verify(storageService).delete("receipts/key");
        verify(receiptRepository).delete(receipt);
    }

    @Test
    void delete_stillRemovesMetadata_whenS3DeleteFails() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = receiptOnReport().receiptId(receiptId).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        org.mockito.Mockito.doThrow(new StorageException("s3 down")).when(storageService).delete("receipts/key");

        receiptService.delete(receiptId);

        verify(receiptRepository).delete(receipt);
    }

    @Test
    void delete_throwsBusinessRuleViolation_whenReportNotEditable() {
        UUID receiptId = UUID.randomUUID();
        draftReport.setReportStatus(ReportStatus.PENDING_APPROVAL);
        Receipt receipt = receiptOnReport().receiptId(receiptId).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> receiptService.delete(receiptId)).isInstanceOf(BusinessRuleViolationException.class);

        verify(receiptRepository, never()).delete(any());
    }

    @Test
    void delete_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        UUID receiptId = UUID.randomUUID();
        draftReport.setEmployeeId("someone-else");
        Receipt receipt = receiptOnReport().receiptId(receiptId).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> receiptService.delete(receiptId)).isInstanceOf(AccessDeniedException.class);

        verify(receiptRepository, never()).delete(any());
    }
}
