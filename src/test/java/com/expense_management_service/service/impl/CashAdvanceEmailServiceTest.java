package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashAdvanceEmailServiceTest {

    private static final String EMPLOYEE = "5100014";
    private static final String MANAGER = "5100023";
    private static final String OWNER = "5100030";

    @Mock
    private UmsClient umsClient;

    @Mock
    private EmailOutboxService emailOutbox;

    private CashAdvanceEmailService service;

    @BeforeEach
    void setUp() {
        service = new CashAdvanceEmailService(umsClient, emailOutbox);
        ReflectionTestUtils.setField(service, "financeAddress", "");
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://portal.example.com/");
    }

    @Test
    void submitted_queuesManagerRequestAndEmployeeConfirmation() {
        when(umsClient.getAllUsers()).thenReturn(users());

        service.onSubmitted(advance());

        verify(emailOutbox).enqueue(eq("rama@example.com"), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_MANAGER_REQUEST), anyString());
        verify(emailOutbox).enqueue(eq("sindhu@example.com"), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_EMPLOYEE_SUBMITTED), anyString());
    }

    @Test
    void submitted_bodyShowsEmployeeInATable_andLinksToTheApprovals() {
        when(umsClient.getAllUsers()).thenReturn(users());

        service.onSubmitted(advance());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailOutbox).enqueue(eq("rama@example.com"), anyString(), body.capture(),
                eq(CashAdvanceEmailService.EMAIL_MANAGER_REQUEST), anyString());
        assertThat(body.getValue())
                .contains("| Employee ID")
                .contains("| " + EMPLOYEE)
                .contains("https://portal.example.com/expense-management/approvals");
    }

    @Test
    void submitted_withoutManagerEmail_skipsTheManagerMailOnly() {
        when(umsClient.getAllUsers()).thenReturn(List.of(
                user(EMPLOYEE, "Sindhu", "Yanala", "sindhu@example.com"),
                user(MANAGER, "Rama", "Durgam", "")));

        service.onSubmitted(advance());

        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_MANAGER_REQUEST), anyString());
        verify(emailOutbox).enqueue(eq("sindhu@example.com"), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_EMPLOYEE_SUBMITTED), anyString());
    }

    @Test
    void costCenterStage_mailsTheCostCenterOwner() {
        when(umsClient.getAllUsers()).thenReturn(users());

        service.onCostCenterStage(advance());

        verify(emailOutbox).enqueue(eq("owner@example.com"), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_COST_CENTER_REQUEST), anyString());
    }

    @Test
    void financeStage_withoutAddress_queuesNothing() {
        service.onFinanceStage(advance());

        verify(emailOutbox, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void financeStage_withAddress_queuesTheFinanceRequest() {
        ReflectionTestUtils.setField(service, "financeAddress", "finance@example.com");
        when(umsClient.getAllUsers()).thenReturn(users());

        service.onFinanceStage(advance());

        verify(emailOutbox).enqueue(eq("finance@example.com"), anyString(), anyString(),
                eq(CashAdvanceEmailService.EMAIL_FINANCE_REQUEST), anyString());
    }

    private static CashAdvance advance() {
        CostCenter costCenter = CostCenter.builder()
                .costCenterName("Engineering")
                .ownerEmployeeId(OWNER)
                .build();
        return CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId(EMPLOYEE)
                .managerId(MANAGER)
                .costCenter(costCenter)
                .amount(new BigDecimal("3000.00"))
                .title("Client visit")
                .purpose("Pune client meeting")
                .build();
    }

    private static List<UmsUserResponse> users() {
        return List.of(
                user(EMPLOYEE, "Sindhu", "Yanala", "sindhu@example.com"),
                user(MANAGER, "Rama", "Durgam", "rama@example.com"),
                user(OWNER, "Venipriya", "P", "owner@example.com"));
    }

    private static UmsUserResponse user(String id, String first, String last, String mail) {
        return new UmsUserResponse(UUID.randomUUID(), Long.valueOf(id), first, last, mail, true);
    }
}
