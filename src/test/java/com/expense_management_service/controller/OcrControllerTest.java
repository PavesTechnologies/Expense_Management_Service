package com.expense_management_service.controller;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.request.OcrOverrideRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.OcrStatusResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.OCRService;
import com.expense_management_service.service.ReceiptConfirmationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for {@link OcrController}: verifies routing and coarse RBAC. Pipeline
 * orchestration, ownership, and duplicate/confidence logic live in {@code OCRServiceImplTest}
 * and {@code ReceiptConfirmationServiceImplTest}, not here.
 */
@WebMvcTest(OcrController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class OcrControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OCRService ocrService;

    @MockitoBean
    private ReceiptConfirmationService receiptConfirmationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ReceiptOcrResponse sampleResponse(UUID receiptId, OcrStatus status) {
        return new ReceiptOcrResponse(UUID.randomUUID(), receiptId, "Acme Taxi", "INV-001",
                LocalDate.of(2026, 1, 15), null, "USD", new BigDecimal("100.00"), new BigDecimal("23.45"),
                new BigDecimal("123.45"), "UPI", new BigDecimal("0.90"),
                status, null, LocalDateTime.now(), 850L, "AWS_TEXTRACT", "AnalyzeExpense", false, false);
    }

    @Test
    void processReceipt_returns200_forAdmin() throws Exception {
        // Manual OCR trigger is admin/testing-only now — OCR starts automatically on upload.
        UUID receiptId = UUID.randomUUID();
        when(ocrService.processReceipt(receiptId)).thenReturn(sampleResponse(receiptId, OcrStatus.OCR_COMPLETED));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantName").value("Acme Taxi"));
    }

    @Test
    void processReceipt_returns403_forEmployee() throws Exception {
        // Requirement 1: not part of the normal employee workflow anymore.
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void processReceipt_returns403_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void processReceipt_returns401_whenUnauthenticated() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr", receiptId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLatestResult_returns200_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(ocrService.getLatestResult(receiptId)).thenReturn(sampleResponse(receiptId, OcrStatus.OCR_COMPLETED));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").value(receiptId.toString()))
                .andExpect(jsonPath("$.data.subtotal").value(100.00))
                .andExpect(jsonPath("$.data.taxAmount").value(23.45))
                .andExpect(jsonPath("$.data.totalAmount").value(123.45))
                .andExpect(jsonPath("$.data.paymentMethod").value("UPI"))
                .andExpect(jsonPath("$.data.invoiceNumber").value("INV-001"));
    }

    @Test
    void getLatestResult_returnsSuccessWithStatusPayload_whenOcrStillProcessing() throws Exception {
        // Requirement 9 (bug fix): while OCR is in flight, this must be a success response the
        // frontend can poll on — never an error just because no ReceiptOcr row exists yet.
        UUID receiptId = UUID.randomUUID();
        when(ocrService.getLatestResult(receiptId))
                .thenThrow(new ResourceNotFoundException("No OCR result yet for receipt: " + receiptId));
        when(ocrService.getStatus(receiptId)).thenReturn(new OcrStatusResponse(receiptId, OcrStatus.PROCESSING, LocalDateTime.now()));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("OCR is currently processing."))
                .andExpect(jsonPath("$.data.ocrStatus").value("PROCESSING"));
    }

    @Test
    void getLatestResult_returnsDistinctMessage_whenReceiptStillUploaded() throws Exception {
        // Regression test: UPLOADED must never be reported with the same "currently processing"
        // message as PROCESSING — that conflation was itself a bug, making a receipt where OCR
        // genuinely hasn't started indistinguishable from one legitimately in flight.
        UUID receiptId = UUID.randomUUID();
        when(ocrService.getLatestResult(receiptId))
                .thenThrow(new ResourceNotFoundException("No OCR result yet for receipt: " + receiptId));
        when(ocrService.getStatus(receiptId)).thenReturn(new OcrStatusResponse(receiptId, OcrStatus.UPLOADED, LocalDateTime.now()));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Receipt uploaded successfully. OCR has not started yet."))
                .andExpect(jsonPath("$.data.ocrStatus").value("UPLOADED"));
    }

    @Test
    void getLatestResult_returns404_whenNoResultAndNotInProgress() throws Exception {
        // No row, and the receipt isn't even in-flight (e.g. VERIFIED with a data
        // inconsistency, or any other unexpected state) — a genuine error, not a polling case.
        UUID receiptId = UUID.randomUUID();
        when(ocrService.getLatestResult(receiptId))
                .thenThrow(new ResourceNotFoundException("No OCR result yet for receipt: " + receiptId));
        when(ocrService.getStatus(receiptId)).thenReturn(new OcrStatusResponse(receiptId, OcrStatus.VERIFIED, LocalDateTime.now()));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/ocr", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryOcr_returns200_forEmployee() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(ocrService.retryOcr(receiptId)).thenReturn(sampleResponse(receiptId, OcrStatus.OCR_COMPLETED));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr/retry", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processingStatus").value("OCR_COMPLETED"));
    }

    @Test
    void retryOcr_returns403_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr/retry", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStatus_returns200_forEmployee() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(ocrService.getStatus(receiptId)).thenReturn(new OcrStatusResponse(receiptId, OcrStatus.PROCESSING, LocalDateTime.now()));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/ocr/status", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ocrStatus").value("PROCESSING"));
    }

    @Test
    void recordOverride_returns204_andDelegatesToService_forEmployee() throws Exception {
        UUID receiptId = UUID.randomUUID();
        String body = new ObjectMapper().writeValueAsString(new OcrOverrideRequest("amount", "100.00", "120.00", null));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr/override", receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNoContent());

        verify(ocrService).recordOverride(receiptId, "amount", "100.00", "120.00", null);
    }

    @Test
    void recordOverride_returns403_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();
        String body = new ObjectMapper().writeValueAsString(new OcrOverrideRequest("amount", "100.00", "120.00", null));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr/override", receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void recordOverride_returns400_whenFieldNameMissing() throws Exception {
        UUID receiptId = UUID.randomUUID();
        String body = new ObjectMapper().writeValueAsString(new OcrOverrideRequest("", "100.00", "120.00", null));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/ocr/override", receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirm_returns200_andDelegatesToConfirmationService_forEmployee() throws Exception {
        UUID receiptId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        ReceiptConfirmRequest request = new ReceiptConfirmRequest(null, UUID.randomUUID(), LocalDate.of(2026, 1, 15),
                "Acme Taxi", null, new BigDecimal("123.45"), UUID.randomUUID(), new BigDecimal("10.00"), null, null, null);
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(request);
        when(receiptConfirmationService.confirm(any(), any()))
                .thenReturn(new ReceiptConfirmResponse(receiptId, lineItemId, OcrStatus.VERIFIED, false, new BigDecimal("123.45"), new BigDecimal("123.45")));

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/confirm", receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineItemId").value(lineItemId.toString()))
                .andExpect(jsonPath("$.data.receiptStatus").value("VERIFIED"));
    }

    @Test
    void confirm_returns403_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();
        ReceiptConfirmRequest request = new ReceiptConfirmRequest(null, UUID.randomUUID(), LocalDate.of(2026, 1, 15),
                "Acme Taxi", null, new BigDecimal("123.45"), UUID.randomUUID(), new BigDecimal("10.00"), null, null, null);
        String body = new ObjectMapper().findAndRegisterModules().writeValueAsString(request);

        mockMvc.perform(post("/xms/employee/receipts/{receiptId}/confirm", receiptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }
}
