package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalFlowCriterionRequest;
import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.ApprovalLevelApproverRequest;
import com.expense_management_service.dto.request.ApprovalLevelRequest;
import com.expense_management_service.dto.request.CatchAllFlowRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalFlowServiceImplTest {

    @Mock private ApprovalFlowRepository approvalFlowRepository;
    @Mock private AuditLogRepository auditLogRepository;

    private ApprovalFlowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApprovalFlowServiceImpl(approvalFlowRepository, new ApprovalFlowMapper(), auditLogRepository,
                org.mockito.Mockito.mock(com.expense_management_service.service.NotificationService.class),
                org.mockito.Mockito.mock(com.expense_management_service.security.CurrentUserService.class));
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApprovalLevelRequest oneManagerLevel(int order) {
        return new ApprovalLevelRequest(order, "Manager Review", LevelQuorum.SEQUENTIAL, null,
                List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.REPORTING_MANAGER, null)));
    }

    private ApprovalFlowRequest validFlowRequest() {
        return new ApprovalFlowRequest("Travel over 10k", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "10000")),
                List.of(oneManagerLevel(1)), "ACTIVE");
    }

    @Test
    void create_savesFlow_whenValid() {
        when(approvalFlowRepository.findAll()).thenReturn(List.of());
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> {
            ApprovalFlow f = inv.getArgument(0);
            f.setFlowId(UUID.randomUUID());
            return f;
        });

        ApprovalFlowResponse response = service.create(validFlowRequest());

        assertThat(response.name()).isEqualTo("Travel over 10k");
        assertThat(response.levels()).hasSize(1);
        assertThat(response.isCatchAll()).isFalse();
    }

    @Test
    void create_savesFinanceVerificationLevel_whenLevelTypeSpecified() {
        when(approvalFlowRepository.findAll()).thenReturn(List.of());
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> {
            ApprovalFlow f = inv.getArgument(0);
            f.setFlowId(UUID.randomUUID());
            return f;
        });
        ApprovalLevelRequest managerLevel = oneManagerLevel(1);
        ApprovalLevelRequest financeLevel = new ApprovalLevelRequest(2, "Finance Verification", LevelQuorum.SEQUENTIAL,
                LevelType.FINANCE_VERIFICATION,
                List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.FINANCE_OWNER, null)));
        ApprovalFlowRequest request = new ApprovalFlowRequest("Manager then Finance", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "1")),
                List.of(managerLevel, financeLevel), "ACTIVE");

        ApprovalFlowResponse response = service.create(request);

        assertThat(response.levels()).hasSize(2);
        assertThat(response.levels().get(0).levelType()).isEqualTo(LevelType.APPROVAL);
        assertThat(response.levels().get(1).levelType()).isEqualTo(LevelType.FINANCE_VERIFICATION);
    }

    @Test
    void create_defaultsLevelTypeToApproval_whenOmitted() {
        when(approvalFlowRepository.findAll()).thenReturn(List.of());
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> {
            ApprovalFlow f = inv.getArgument(0);
            f.setFlowId(UUID.randomUUID());
            return f;
        });

        ApprovalFlowResponse response = service.create(validFlowRequest());

        assertThat(response.levels().get(0).levelType()).isEqualTo(LevelType.APPROVAL);
    }

    @Test
    void create_throws_whenMoreThan10Levels() {
        List<ApprovalLevelRequest> levels = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(this::oneManagerLevel).toList();
        ApprovalFlowRequest request = new ApprovalFlowRequest("Too many levels", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "1")),
                levels, "ACTIVE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 10 levels");
    }

    @Test
    void create_throws_whenLevelOrdersDuplicated() {
        ApprovalFlowRequest request = new ApprovalFlowRequest("Dup levels", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "1")),
                List.of(oneManagerLevel(1), oneManagerLevel(1)), "ACTIVE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void create_throws_whenNamedUserHasNoSourceReference() {
        ApprovalLevelRequest level = new ApprovalLevelRequest(1, null, LevelQuorum.SEQUENTIAL, null,
                List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.NAMED_USER, null)));
        ApprovalFlowRequest request = new ApprovalFlowRequest("Bad named user", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "1")),
                List.of(level), "ACTIVE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceReference is required");
    }

    @Test
    void create_throws_whenCriteriaPatternReferencesUnknownIndex() {
        ApprovalFlowRequest request = new ApprovalFlowRequest("Bad pattern", 1, "1 AND 2",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.AMOUNT, CriterionOperator.GREATER_THAN, "1")),
                List.of(oneManagerLevel(1)), "ACTIVE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 2");
    }

    @Test
    void create_throws_whenOperatorInvalidForNonAmountField() {
        ApprovalFlowRequest request = new ApprovalFlowRequest("Bad operator", 1, "1",
                List.of(new ApprovalFlowCriterionRequest(1, CriterionField.DEPARTMENT, CriterionOperator.GREATER_THAN, "x")),
                List.of(oneManagerLevel(1)), "ACTIVE");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only valid for AMOUNT");
    }

    @Test
    void create_throws_whenPriorityAlreadyUsed() {
        ApprovalFlow existing = ApprovalFlow.builder().flowId(UUID.randomUUID()).priority(1).isCatchAll(false).build();
        when(approvalFlowRepository.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(validFlowRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority 1");
    }

    @Test
    void delete_throws_whenTargetIsCatchAllFlow() {
        UUID id = UUID.randomUUID();
        ApprovalFlow catchAll = ApprovalFlow.builder().flowId(id).isCatchAll(true).build();
        when(approvalFlowRepository.findById(id)).thenReturn(Optional.of(catchAll));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("catch-all");
    }

    @Test
    void getCatchAllFlow_throws_whenNotConfigured() {
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCatchAllFlow()).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateCatchAllFlow_createsIt_whenNoneExistsYet() {
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.empty());
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> {
            ApprovalFlow f = inv.getArgument(0);
            f.setFlowId(UUID.randomUUID());
            return f;
        });

        ApprovalFlowResponse response = service.updateCatchAllFlow(new CatchAllFlowRequest(List.of(oneManagerLevel(1))), "5100001");

        assertThat(response.isCatchAll()).isTrue();
        assertThat(response.levels()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // updateCatchAllFlow - audit trail (production-readiness: the level replace below is
    // destructive with no confirmation step anywhere, so every save must at least be traceable
    // after the fact)
    // ---------------------------------------------------------------------

    @Test
    void updateCatchAllFlow_writesAuditLogEntry_recordingOldAndNewLevels() {
        ApprovalFlow existing = ApprovalFlow.builder().flowId(UUID.randomUUID()).name("Catch-All").isCatchAll(true).status("ACTIVE").build();
        new ApprovalFlowMapper().replaceLevels(existing, List.of(oneManagerLevel(1)));
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.of(existing));
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> inv.getArgument(0));

        var costCenterOwnerLevel = new ApprovalLevelRequest(2, "Cost Center Owner Approval", LevelQuorum.SEQUENTIAL, null,
                List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.COST_CENTER_OWNER, null)));
        service.updateCatchAllFlow(new CatchAllFlowRequest(List.of(oneManagerLevel(1), costCenterOwnerLevel)), "5100099");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityName()).isEqualTo("ApprovalFlow");
        assertThat(saved.getEntityId()).isEqualTo(existing.getFlowId());
        assertThat(saved.getAction()).isEqualTo("CATCH_ALL_LEVELS_UPDATED");
        assertThat(saved.getPerformedBy()).isEqualTo("5100099");
        assertThat(saved.getPerformedAt()).isNotNull();
        assertThat(saved.getOldValue()).contains("L1").doesNotContain("COST_CENTER_OWNER");
        assertThat(saved.getNewValue()).contains("L1").contains("L2").contains("COST_CENTER_OWNER");
    }

    @Test
    void updateCatchAllFlow_auditLog_capturesADroppedLevel_beforeItIsGone() {
        // Regression for the exact reported incident: a save whose payload omits a previously
        // configured level (e.g. Cost Center Owner) permanently deletes it via replaceLevels'
        // orphanRemoval - the audit log must still show it existed beforehand.
        ApprovalFlow existing = ApprovalFlow.builder().flowId(UUID.randomUUID()).name("Catch-All").isCatchAll(true).status("ACTIVE").build();
        var costCenterOwnerLevel = new ApprovalLevelRequest(2, "Cost Center Owner Approval", LevelQuorum.SEQUENTIAL, null,
                List.of(new ApprovalLevelApproverRequest(1, ApproverSourceType.COST_CENTER_OWNER, null)));
        new ApprovalFlowMapper().replaceLevels(existing, List.of(oneManagerLevel(1), costCenterOwnerLevel));
        when(approvalFlowRepository.findByIsCatchAllTrue()).thenReturn(Optional.of(existing));
        when(approvalFlowRepository.save(any(ApprovalFlow.class))).thenAnswer(inv -> inv.getArgument(0));

        // A save that only sends Level 1 back - Level 2 (Cost Center Owner) is missing from the payload.
        service.updateCatchAllFlow(new CatchAllFlowRequest(List.of(oneManagerLevel(1))), "5100099");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getOldValue()).contains("COST_CENTER_OWNER");
        assertThat(saved.getNewValue()).doesNotContain("COST_CENTER_OWNER");
        assertThat(existing.getLevels()).hasSize(1); // confirms the level really is gone after the save
    }

    @Test
    void updateCatchAllFlow_throws_withoutWritingAuditLog_whenLevelsInvalid() {
        var duplicateOrderLevels = List.of(oneManagerLevel(1), oneManagerLevel(1));

        assertThatThrownBy(() -> service.updateCatchAllFlow(new CatchAllFlowRequest(duplicateOrderLevels), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(auditLogRepository, never()).save(any());
    }
}
