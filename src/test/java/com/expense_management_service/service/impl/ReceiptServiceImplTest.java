package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.storage.StorageException;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceImplTest {

    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private CurrentUserService currentUserService;

    private ReceiptServiceImpl receiptService;

    private final String employeeId = "5100014";
    private UUID lineItemId;
    private UUID reportId;
    private ExpenseReport draftReport;
    private ExpenseLineItem lineItem;

    @BeforeEach
    void setUp() {
        receiptService = new ReceiptServiceImpl(
                receiptRepository, expenseLineItemRepository, storageService, currentUserService, new ReceiptMapper());
        ReflectionTestUtils.setField(receiptService, "maxFileSizeBytes", 10L * 1024 * 1024);
        ReflectionTestUtils.setField(receiptService, "presignedUrlTtlMinutes", 15L);

        lineItemId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus("DRAFT").build();
        lineItem = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport).build();
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("EMPLOYEE"), List.of());
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "taxi-receipt.pdf", "application/pdf", "%PDF-1.4 dummy receipt content".getBytes());
    }

    private void stubOwnerAndLineItem() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));
    }

    @Test
    void upload_savesReceiptMetadata_andUploadsToStorage_whenValid() {
        stubOwnerAndLineItem();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenAnswer(inv -> {
            Receipt saved = inv.getArgument(0);
            saved.setReceiptId(UUID.randomUUID());
            return saved;
        });

        MockMultipartFile file = pdfFile();
        ReceiptResponse response = receiptService.upload(lineItemId, file);

        assertThat(response.originalFileName()).isEqualTo("taxi-receipt.pdf");
        assertThat(response.contentType()).isEqualTo("application/pdf");
        assertThat(response.uploadedBy()).isEqualTo(employeeId);
        verify(storageService).upload(argThatKeyContains("receipts/" + employeeId + "/" + reportId + "/" + lineItemId + "/"), eq(file));
    }

    private String argThatKeyContains(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(key -> key != null && key.startsWith(prefix));
    }

    @Test
    void upload_rollsBackS3Object_whenMetadataSaveFails() {
        stubOwnerAndLineItem();
        when(receiptRepository.saveAndFlush(any(Receipt.class))).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> receiptService.upload(lineItemId, pdfFile()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(storageService).delete(anyString());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenFileEmpty() {
        stubOwnerAndLineItem();
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> receiptService.upload(lineItemId, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenFileTooLarge() {
        stubOwnerAndLineItem();
        ReflectionTestUtils.setField(receiptService, "maxFileSizeBytes", 5L);

        assertThatThrownBy(() -> receiptService.upload(lineItemId, pdfFile()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum allowed size");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenContentTypeNotAllowed() {
        stubOwnerAndLineItem();
        MockMultipartFile exe = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "content".getBytes());

        assertThatThrownBy(() -> receiptService.upload(lineItemId, exe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenExtensionMismatchesContentType() {
        stubOwnerAndLineItem();
        // Spoofed content-type but a disallowed extension
        MockMultipartFile spoofed = new MockMultipartFile("file", "script.js", "application/pdf", "content".getBytes());

        assertThatThrownBy(() -> receiptService.upload(lineItemId, spoofed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extension");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsIllegalArgumentException_whenBytesDoNotMatchDeclaredType() {
        stubOwnerAndLineItem();
        // Extension AND content-type both claim PDF (e.g. a renamed executable), but the
        // actual bytes don't carry the PDF magic number — this must still be caught.
        MockMultipartFile fakePdf = new MockMultipartFile("file", "invoice.pdf", "application/pdf", "MZ-not-really-a-pdf".getBytes());

        assertThatThrownBy(() -> receiptService.upload(lineItemId, fakePdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsBusinessRuleViolation_whenReportNotEditable() {
        draftReport.setReportStatus("SUBMITTED");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> receiptService.upload(lineItemId, pdfFile()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> receiptService.upload(lineItemId, pdfFile()))
                .isInstanceOf(AccessDeniedException.class);

        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void upload_throwsResourceNotFoundException_whenLineItemMissing() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptService.upload(lineItemId, pdfFile()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllForLineItem_returnsMetadata_whenOwner() {
        stubOwnerAndLineItem();
        Receipt receipt = Receipt.builder().receiptId(UUID.randomUUID()).lineItem(lineItem)
                .originalFileName("a.pdf").objectKey("key").build();
        when(receiptRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(receipt));

        assertThat(receiptService.getAllForLineItem(lineItemId)).hasSize(1);
    }

    @Test
    void getAllForLineItem_throwsAccessDenied_whenNotOwner() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(lineItem));

        assertThatThrownBy(() -> receiptService.getAllForLineItem(lineItemId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getViewUrl_delegatesToStorageServiceWithInlineDisposition() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem)
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
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem)
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
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        receiptService.delete(receiptId);

        verify(storageService).delete("receipts/key");
        verify(receiptRepository).delete(receipt);
    }

    @Test
    void delete_stillRemovesMetadata_whenS3DeleteFails() {
        UUID receiptId = UUID.randomUUID();
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        org.mockito.Mockito.doThrow(new StorageException("s3 down")).when(storageService).delete("receipts/key");

        receiptService.delete(receiptId);

        verify(receiptRepository).delete(receipt);
    }

    @Test
    void delete_throwsBusinessRuleViolation_whenReportNotEditable() {
        UUID receiptId = UUID.randomUUID();
        draftReport.setReportStatus("SUBMITTED");
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> receiptService.delete(receiptId)).isInstanceOf(BusinessRuleViolationException.class);

        verify(receiptRepository, never()).delete(any());
    }

    @Test
    void delete_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        UUID receiptId = UUID.randomUUID();
        draftReport.setEmployeeId("someone-else");
        Receipt receipt = Receipt.builder().receiptId(receiptId).lineItem(lineItem).objectKey("receipts/key").build();
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));

        assertThatThrownBy(() -> receiptService.delete(receiptId)).isInstanceOf(AccessDeniedException.class);

        verify(receiptRepository, never()).delete(any());
    }
}
