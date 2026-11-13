package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceDocument;
import com.expense_management_service.enums.CashAdvanceDocumentCategory;
import com.expense_management_service.mapper.CashAdvanceDocumentMapper;
import com.expense_management_service.repository.CashAdvanceDocumentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.storage.StorageException;
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/** Upload, list and delete rules for supporting documents, with storage and the database both mocked. */
class CashAdvanceDocumentServiceImplTest {

    private static final String OWNER = "EMP001";
    private static final String MANAGER = "MGR001";
    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final byte[] PDF = "%PDF-1.4 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private CashAdvanceRepository cashAdvanceRepository;
    private CashAdvanceDocumentRepository documentRepository;
    private StorageService storageService;
    private CurrentUserService currentUserService;
    private CashAdvanceDocumentServiceImpl service;
    private UUID advanceId;
    private CashAdvance advance;

    @BeforeEach
    void setUp() {
        cashAdvanceRepository = mock(CashAdvanceRepository.class);
        documentRepository = mock(CashAdvanceDocumentRepository.class);
        storageService = mock(StorageService.class);
        currentUserService = mock(CurrentUserService.class);
        service = new CashAdvanceDocumentServiceImpl(cashAdvanceRepository, documentRepository, storageService,
                currentUserService, new CashAdvanceDocumentValidator(), new CashAdvanceDocumentMapper());
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", FIVE_MB);
        ReflectionTestUtils.setField(service, "maxFiles", 5);

        advanceId = UUID.randomUUID();
        advance = CashAdvance.builder().advanceId(advanceId).employeeId(OWNER).managerId(MANAGER)
                .amount(new BigDecimal("8000")).status("DRAFT").build();
        when(cashAdvanceRepository.findByIdForUpdate(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(currentUserService.getEmployeeId()).thenReturn(OWNER);
        when(documentRepository.save(any(CashAdvanceDocument.class))).thenAnswer(inv -> {
            CashAdvanceDocument d = inv.getArgument(0);
            if (d.getDocumentId() == null) {
                d.setDocumentId(UUID.randomUUID());
            }
            return d;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static MockMultipartFile pdfFile(String name) {
        return new MockMultipartFile("file", name, "application/pdf", PDF);
    }

    private void signInAs(String employeeId, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(employeeId, null, authorities));
    }

    // ------------------------------------------------------------------ upload: the happy path and its order

    @Test
    void upload_storesTheFileFirst_thenSavesItsMetadata_underAnOwnedKey() {
        CashAdvanceDocumentResponse response = service.upload(advanceId, CashAdvanceDocumentCategory.TRAVEL_ITINERARY,
                pdfFile("Pune itinerary.pdf"));

        var order = inOrder(storageService, documentRepository);
        order.verify(storageService).upload(startsWith("cash-advances/" + OWNER + "/" + advanceId + "/"), any());
        order.verify(documentRepository).save(any(CashAdvanceDocument.class));

        assertEquals(CashAdvanceDocumentCategory.TRAVEL_ITINERARY, response.category());
        assertEquals("Pune itinerary.pdf", response.originalFileName());
        assertEquals("application/pdf", response.contentType());
        assertEquals(PDF.length, response.fileSizeBytes());
    }

    @Test
    void upload_neverExposesTheStorageKey() {
        CashAdvanceDocumentResponse response = service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf"));
        assertFalse(response.toString().contains("cash-advances/"), "the response must not carry the storage key");
    }

    @Test
    void upload_sanitisesTheFileNameInTheKey_andKeepsTheOriginalName() {
        CashAdvanceDocumentResponse response = service.upload(advanceId, CashAdvanceDocumentCategory.OTHER,
                new MockMultipartFile("file", "..\\evil/..\\my file (1).pdf", "application/pdf", PDF));

        verify(storageService).upload(argThat(key -> key.matches("cash-advances/EMP001/[0-9a-f-]+/[0-9a-f-]+-my_file__1_\\.pdf")), any());
        assertEquals("my file (1).pdf", response.originalFileName());
    }

    // ------------------------------------------------------------------ upload: refusals before anything is stored

    @Test
    void upload_refusesAFileOverTheLimit_beforeAnythingIsStored() {
        byte[] big = new byte[(int) FIVE_MB + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertThrows(BusinessRuleViolationException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER,
                new MockMultipartFile("file", "big.pdf", "application/pdf", big)));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void upload_refusesAFormatNotAllowed_beforeAnythingIsStored() {
        assertThrows(BusinessRuleViolationException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER,
                new MockMultipartFile("file", "run.exe", "application/octet-stream", PDF)));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void upload_refusesWithoutACategory() {
        assertThrows(BusinessRuleViolationException.class, () -> service.upload(advanceId, null, pdfFile("a.pdf")));
        verifyNoInteractions(storageService);
    }

    @Test
    void upload_refusesAFifthDocumentWhenFiveAreAlreadyAttached() {
        when(documentRepository.countByAdvance_AdvanceIdAndDeletedAtIsNull(advanceId)).thenReturn(5L);
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("sixth.pdf")));
        assertTrue(ex.getMessage().contains("at most 5"), ex.getMessage());
        verifyNoInteractions(storageService);
    }

    @Test
    void upload_allowsTheFifthDocument() {
        when(documentRepository.countByAdvance_AdvanceIdAndDeletedAtIsNull(advanceId)).thenReturn(4L);
        assertDoesNotThrow(() -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("fifth.pdf")));
    }

    @Test
    void upload_refusesAnotherEmployeesRequest() {
        when(currentUserService.getEmployeeId()).thenReturn("EMP999");
        assertThrows(AccessDeniedException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf")));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void upload_refusesOnceTheRequestIsSubmitted() {
        advance.setStatus("SUBMITTED");
        assertThrows(BusinessRuleViolationException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf")));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void upload_refusesAnUnknownRequest() {
        UUID unknown = UUID.randomUUID();
        when(cashAdvanceRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.upload(unknown, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf")));
    }

    // ------------------------------------------------------------------ upload: failures

    @Test
    void upload_whenStorageFails_savesNothingAndReportsTheFailure() {
        doThrow(new StorageException("S3 down", null)).when(storageService).upload(anyString(), any());
        assertThrows(StorageException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf")));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void upload_whenTheMetadataCannotBeSaved_removesTheStoredFile() {
        when(documentRepository.save(any(CashAdvanceDocument.class))).thenThrow(new RuntimeException("db down"));
        assertThrows(RuntimeException.class, () -> service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf")));

        verify(storageService).upload(anyString(), any());
        verify(storageService).delete(argThat(key -> key.startsWith("cash-advances/" + OWNER + "/" + advanceId + "/")));
    }

    @Test
    void upload_whenTheTransactionRollsBackAfterStorage_removesTheStoredFile() {
        TransactionSynchronizationManager.initSynchronization();
        service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf"));

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(storageService).delete(anyString());
    }

    @Test
    void upload_whenTheTransactionCommits_keepsTheStoredFile() {
        TransactionSynchronizationManager.initSynchronization();
        service.upload(advanceId, CashAdvanceDocumentCategory.OTHER, pdfFile("a.pdf"));

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        verify(storageService, never()).delete(anyString());
    }

    // ------------------------------------------------------------------ list

    private CashAdvanceDocument attached(String key) {
        return CashAdvanceDocument.builder().documentId(UUID.randomUUID()).advance(advance).employeeId(OWNER)
                .category(CashAdvanceDocumentCategory.CLIENT_APPROVAL).originalFileName("approval.pdf")
                .storedFileName("s-approval.pdf").objectKey(key).contentType("application/pdf").fileSizeBytes(1024)
                .uploadedBy(OWNER).uploadedAt(LocalDateTime.now()).build();
    }

    @Test
    void list_isVisibleToTheOwner() {
        when(documentRepository.findByAdvance_AdvanceIdAndDeletedAtIsNullOrderByUploadedAtAsc(advanceId))
                .thenReturn(List.of(attached("k1")));
        assertEquals(1, service.list(advanceId).size());
    }

    @Test
    void list_isVisibleToTheAssignedManager() {
        signInAs(MANAGER, RoleConstants.ROLE_MANAGER);
        when(currentUserService.getEmployeeId()).thenReturn(MANAGER);
        when(documentRepository.findByAdvance_AdvanceIdAndDeletedAtIsNullOrderByUploadedAtAsc(advanceId)).thenReturn(List.of());
        assertDoesNotThrow(() -> service.list(advanceId));
    }

    @Test
    void list_isVisibleToFinance() {
        signInAs("FIN1", RoleConstants.ROLE_FINANCE);
        when(currentUserService.getEmployeeId()).thenReturn("FIN1");
        when(documentRepository.findByAdvance_AdvanceIdAndDeletedAtIsNullOrderByUploadedAtAsc(advanceId)).thenReturn(List.of());
        assertDoesNotThrow(() -> service.list(advanceId));
    }

    @Test
    void list_isRefusedToAnotherEmployee() {
        when(currentUserService.getEmployeeId()).thenReturn("EMP999");
        assertThrows(AccessDeniedException.class, () -> service.list(advanceId));
        verifyNoInteractions(documentRepository);
    }

    // ------------------------------------------------------------------ delete

    @Test
    void delete_softDeletesTheRow_andRemovesTheFile() {
        CashAdvanceDocument doc = attached("cash-advances/EMP001/x/y.pdf");
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));

        service.delete(advanceId, doc.getDocumentId());

        assertEquals(OWNER, doc.getDeletedBy());
        assertNotNull(doc.getDeletedAt());
        verify(documentRepository).save(doc);
        verify(documentRepository, never()).delete(any());
        verify(storageService).delete("cash-advances/EMP001/x/y.pdf");
    }

    @Test
    void delete_isRefusedOnceTheRequestIsSubmitted() {
        advance.setStatus("SUBMITTED");
        assertThrows(BusinessRuleViolationException.class, () -> service.delete(advanceId, UUID.randomUUID()));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void delete_isRefusedToAnotherEmployee() {
        when(currentUserService.getEmployeeId()).thenReturn(MANAGER);
        assertThrows(AccessDeniedException.class, () -> service.delete(advanceId, UUID.randomUUID()));
        verifyNoInteractions(storageService, documentRepository);
    }

    @Test
    void delete_ofAnAlreadyRemovedDocument_isNotFound() {
        CashAdvanceDocument doc = attached("k");
        doc.setDeletedAt(LocalDateTime.now());
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(advanceId, doc.getDocumentId()));
        verify(storageService, never()).delete(anyString());
    }

    // ------------------------------------------------------------------ view and download links

    @Test
    void viewUrl_isGivenToTheOwner_andUsesTheStoredObject() {
        CashAdvanceDocument doc = attached("cash-advances/EMP001/x/y.pdf");
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));
        when(storageService.generateViewUrl(eq("cash-advances/EMP001/x/y.pdf"), any())).thenReturn("https://signed/view");

        assertEquals("https://signed/view", service.viewUrl(advanceId, doc.getDocumentId()).url());
    }

    @Test
    void downloadUrl_namesTheFileAsTheEmployeeUploadedIt() {
        CashAdvanceDocument doc = attached("cash-advances/EMP001/x/y.pdf");
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));
        when(storageService.generateDownloadUrl(eq("cash-advances/EMP001/x/y.pdf"), eq("approval.pdf"), any()))
                .thenReturn("https://signed/download");

        assertEquals("https://signed/download", service.downloadUrl(advanceId, doc.getDocumentId()).url());
    }

    @Test
    void viewUrl_isRefusedToAnotherEmployee() {
        when(currentUserService.getEmployeeId()).thenReturn("EMP999");
        assertThrows(AccessDeniedException.class, () -> service.viewUrl(advanceId, UUID.randomUUID()));
        verifyNoInteractions(storageService);
    }

    @Test
    void viewUrl_ofARemovedDocument_isNotFound() {
        CashAdvanceDocument doc = attached("k");
        doc.setDeletedAt(LocalDateTime.now());
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));
        assertThrows(ResourceNotFoundException.class, () -> service.viewUrl(advanceId, doc.getDocumentId()));
        verifyNoInteractions(storageService);
    }

    @Test
    void delete_ofADocumentOnAnotherRequest_isNotFound() {
        CashAdvanceDocument doc = attached("k");
        doc.setAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(OWNER).status("DRAFT").build());
        when(documentRepository.findById(doc.getDocumentId())).thenReturn(Optional.of(doc));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(advanceId, doc.getDocumentId()));
    }
}
