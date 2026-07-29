package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.ReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_ADMIN;
import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_FINANCE;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test for {@link ReceiptController}: verifies the multipart upload
 * contract and coarse RBAC. Ownership/status-gating/file validation live in the service
 * layer and are covered by {@code ReceiptServiceImplTest}, not here.
 */
@WebMvcTest(ReceiptController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class ReceiptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReceiptService receiptService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static ReceiptResponse sampleResponse(UUID lineItemId, UUID receiptId) {
        return new ReceiptResponse(receiptId, lineItemId, "taxi-receipt.pdf", "application/pdf",
                12345, "5100014", LocalDateTime.now());
    }

    @Test
    void upload_returns201_forEmployee() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "taxi-receipt.pdf", "application/pdf", "content".getBytes());
        when(receiptService.upload(eq(lineItemId), any())).thenReturn(sampleResponse(lineItemId, receiptId));

        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/receipts", lineItemId)
                        .file(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalFileName").value("taxi-receipt.pdf"));
    }

    @Test
    void upload_returns403_forFinance() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "taxi-receipt.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/receipts", lineItemId)
                        .file(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void upload_returns401_whenUnauthenticated() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "taxi-receipt.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/xms/employee/expense-line-items/{lineItemId}/receipts", lineItemId).file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAllForLineItem_returns200_forManager() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        when(receiptService.getAllForLineItem(lineItemId))
                .thenReturn(List.of(sampleResponse(lineItemId, UUID.randomUUID())));

        mockMvc.perform(get("/xms/employee/expense-line-items/{lineItemId}/receipts", lineItemId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lineItemId").value(lineItemId.toString()));
    }

    @Test
    void getById_returns200_forEmployee() throws Exception {
        UUID lineItemId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        when(receiptService.getById(receiptId)).thenReturn(sampleResponse(lineItemId, receiptId));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptId").value(receiptId.toString()));
    }

    @Test
    void getViewUrl_returns200_andNeverExposesObjectKey() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(receiptService.getViewUrl(receiptId))
                .thenReturn(new ReceiptUrlResponse("https://signed-view-url", LocalDateTime.now().plusMinutes(15)));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/view", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://signed-view-url"));
    }

    @Test
    void getDownloadUrl_returns200() throws Exception {
        UUID receiptId = UUID.randomUUID();
        when(receiptService.getDownloadUrl(receiptId))
                .thenReturn(new ReceiptUrlResponse("https://signed-download-url", LocalDateTime.now().plusMinutes(15)));

        mockMvc.perform(get("/xms/employee/receipts/{receiptId}/download", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://signed-download-url"));
    }

    @Test
    void delete_returns204_forEmployee() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/receipts/{receiptId}", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns204_forAdmin() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/receipts/{receiptId}", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_ADMIN))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_returns403_forFinance() throws Exception {
        UUID receiptId = UUID.randomUUID();

        mockMvc.perform(delete("/xms/employee/receipts/{receiptId}", receiptId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_FINANCE))))
                .andExpect(status().isForbidden());
    }
}
