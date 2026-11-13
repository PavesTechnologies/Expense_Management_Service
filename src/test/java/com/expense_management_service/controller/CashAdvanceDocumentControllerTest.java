package com.expense_management_service.controller;

import com.expense_management_service.config.SecurityConfig;
import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.enums.CashAdvanceDocumentCategory;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.JwtAuthConverter;
import com.expense_management_service.service.CashAdvanceDocumentService;
import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.expense_management_service.security.RoleConstants.ROLE_GENERAL;
import static com.expense_management_service.security.RoleConstants.ROLE_MANAGER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The document endpoints: the multipart contract, status codes, and the role gates. The rules are tested in the service. */
@WebMvcTest(CashAdvanceDocumentController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class})
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid")
class CashAdvanceDocumentControllerTest {

    private static final String BASE = "/xms/employee/cash-advances/{advanceId}/documents";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CashAdvanceDocumentService documentService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final UUID advanceId = UUID.randomUUID();

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "itinerary.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void upload_returns201_withTheDocumentDetails() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(documentService.upload(eq(advanceId), eq(CashAdvanceDocumentCategory.TRAVEL_ITINERARY), any())).thenReturn(
                new CashAdvanceDocumentResponse(documentId, advanceId, CashAdvanceDocumentCategory.TRAVEL_ITINERARY,
                        "itinerary.pdf", "application/pdf", 8L, "EMP001", LocalDateTime.now()));

        mockMvc.perform(multipart(BASE, advanceId)
                        .file(pdf())
                        .param("category", "TRAVEL_ITINERARY")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.data.category").value("TRAVEL_ITINERARY"))
                .andExpect(jsonPath("$.data.originalFileName").value("itinerary.pdf"));
    }

    @Test
    void upload_returns400_withoutAFile() throws Exception {
        mockMvc.perform(multipart(BASE, advanceId)
                        .param("category", "OTHER")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }

    @Test
    void upload_returns400_withAnUnknownCategory() throws Exception {
        mockMvc.perform(multipart(BASE, advanceId)
                        .file(pdf())
                        .param("category", "SOMETHING_ELSE")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(documentService);
    }

    @Test
    void upload_returns400_whenTheServiceRefusesTheFile() throws Exception {
        when(documentService.upload(any(), any(), any()))
                .thenThrow(new BusinessRuleViolationException("This file type is not allowed."));

        mockMvc.perform(multipart(BASE, advanceId)
                        .file(new MockMultipartFile("file", "run.exe", "application/octet-stream", new byte[] {1, 2}))
                        .param("category", "OTHER")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This file type is not allowed."));
    }

    @Test
    void upload_isNotAvailableToAManager() throws Exception {
        mockMvc.perform(multipart(BASE, advanceId)
                        .file(pdf())
                        .param("category", "OTHER")
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(documentService);
    }

    @Test
    void upload_isRefusedWithoutSignIn() throws Exception {
        mockMvc.perform(multipart(BASE, advanceId).file(pdf()).param("category", "OTHER"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(documentService);
    }

    @Test
    void list_returns200_forTheOwner() throws Exception {
        when(documentService.list(advanceId)).thenReturn(List.of());
        mockMvc.perform(get(BASE, advanceId).with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void list_isRefusedWithoutSignIn() throws Exception {
        mockMvc.perform(get(BASE, advanceId)).andExpect(status().isUnauthorized());
        verifyNoInteractions(documentService);
    }

    @Test
    void view_and_download_returnTheSignedLink() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(documentService.viewUrl(advanceId, documentId))
                .thenReturn(new com.expense_management_service.dto.response.ReceiptUrlResponse("https://signed/v", LocalDateTime.now()));
        when(documentService.downloadUrl(advanceId, documentId))
                .thenReturn(new com.expense_management_service.dto.response.ReceiptUrlResponse("https://signed/d", LocalDateTime.now()));

        mockMvc.perform(get(BASE + "/{documentId}/view", advanceId, documentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://signed/v"));
        mockMvc.perform(get(BASE + "/{documentId}/download", advanceId, documentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://signed/d"));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID documentId = UUID.randomUUID();
        mockMvc.perform(delete(BASE + "/{documentId}", advanceId, documentId)
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_GENERAL))))
                .andExpect(status().isNoContent());
        verify(documentService).delete(advanceId, documentId);
    }

    @Test
    void delete_isNotAvailableToAManager() throws Exception {
        mockMvc.perform(delete(BASE + "/{documentId}", advanceId, UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority(ROLE_MANAGER))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(documentService);
    }

    @Test
    void delete_isRefusedWithoutSignIn() throws Exception {
        mockMvc.perform(delete(BASE + "/{documentId}", advanceId, UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(documentService);
    }
}
