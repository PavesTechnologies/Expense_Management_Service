package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.mapper.ApprovalDelegationMapper;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * §14 backend gap: self-service delegation. Any employee (not just ADMIN/MANAGER/FINANCE) must be
 * able to set their own delegate, since any employee can be a resolved approver (§1.5). ADMIN may
 * act on anyone's delegation.
 */
// LENIENT: not every test needs the currentUserService stub (e.g. the ones asserting nothing was saved).
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApprovalDelegationServiceImplTest {

    @Mock private ApprovalDelegationRepository approvalDelegationRepository;
    @Mock private CurrentUserService currentUserService;

    private ApprovalDelegationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApprovalDelegationServiceImpl(approvalDelegationRepository, new ApprovalDelegationMapper(), currentUserService);
    }

    private void loginAs(String employeeId, String... roles) {
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), employeeId, "x@example.com", "X", List.of(roles), List.of()));
    }

    private ApprovalDelegationRequest requestFor(String delegatorId) {
        return new ApprovalDelegationRequest(delegatorId, "5100099", LocalDate.now(), LocalDate.now().plusDays(14), "ACTIVE");
    }

    @Test
    void create_allowsSelfService_whenDelegatorIsCaller() {
        loginAs("5100001", "GENERAL");
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot(any(), any())).thenReturn(List.of());
        when(approvalDelegationRepository.save(any(ApprovalDelegation.class))).thenAnswer(inv -> {
            ApprovalDelegation d = inv.getArgument(0);
            d.setDelegationId(UUID.randomUUID());
            return d;
        });

        var response = service.create(requestFor("5100001"));

        assertThat(response.delegatorId()).isEqualTo("5100001");
    }

    @Test
    void create_blocksNonAdmin_fromSettingSomeoneElsesDelegate() {
        loginAs("5100001", "GENERAL");

        assertThatThrownBy(() -> service.create(requestFor("5100002")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void create_allowsAdmin_toSetAnyonesDelegate() {
        loginAs("5100999", "ADMIN");
        when(approvalDelegationRepository.findByDelegatorIdAndStatusNot(any(), any())).thenReturn(List.of());
        when(approvalDelegationRepository.save(any(ApprovalDelegation.class))).thenAnswer(inv -> {
            ApprovalDelegation d = inv.getArgument(0);
            d.setDelegationId(UUID.randomUUID());
            return d;
        });

        var response = service.create(requestFor("5100002"));

        assertThat(response.delegatorId()).isEqualTo("5100002");
    }

    @Test
    void update_blocksNonAdmin_fromReassigningAnExistingDelegationToSomeoneElse() {
        loginAs("5100001", "GENERAL");
        UUID id = UUID.randomUUID();
        ApprovalDelegation existing = ApprovalDelegation.builder().delegationId(id).delegatorId("5100001").build();
        when(approvalDelegationRepository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(id, requestFor("5100002")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void update_allowsSelfService_onOwnDelegation() {
        loginAs("5100001", "GENERAL");
        UUID id = UUID.randomUUID();
        ApprovalDelegation existing = ApprovalDelegation.builder().delegationId(id).delegatorId("5100001").build();
        when(approvalDelegationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(approvalDelegationRepository.save(any(ApprovalDelegation.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(id, requestFor("5100001"));

        assertThat(response.delegatorId()).isEqualTo("5100001");
    }

    @Test
    void delete_blocksNonAdmin_fromDeletingSomeoneElsesDelegation() {
        loginAs("5100001", "GENERAL");
        UUID id = UUID.randomUUID();
        ApprovalDelegation existing = ApprovalDelegation.builder().delegationId(id).delegatorId("5100002").build();
        when(approvalDelegationRepository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void delete_allowsSelfService_onOwnDelegation() {
        loginAs("5100001", "GENERAL");
        UUID id = UUID.randomUUID();
        ApprovalDelegation existing = ApprovalDelegation.builder().delegationId(id).delegatorId("5100001").build();
        when(approvalDelegationRepository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);
    }

    @Test
    void delete_allowsAdmin_toDeleteAnyonesDelegation() {
        loginAs("5100999", "ADMIN");
        UUID id = UUID.randomUUID();
        ApprovalDelegation existing = ApprovalDelegation.builder().delegationId(id).delegatorId("5100002").build();
        when(approvalDelegationRepository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);
    }
}
