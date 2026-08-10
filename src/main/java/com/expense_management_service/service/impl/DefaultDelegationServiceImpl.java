package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.enums.DelegationStatus;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import com.expense_management_service.service.DelegationService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class DefaultDelegationServiceImpl implements DelegationService {

    private final ApprovalDelegationRepository approvalDelegationRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean canAct(String actingEmployeeId, String approverId) {
        if (actingEmployeeId == null || approverId == null) {
            return false;
        }
        if (actingEmployeeId.equals(approverId)) {
            return true;
        }
        return resolveActiveDelegate(approverId)
                .map(actingEmployeeId::equals)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveActiveDelegate(String approverId) {
        LocalDate today = LocalDate.now();
        return approvalDelegationRepository.findByDelegatorIdAndStatusNot(approverId, DelegationStatus.CANCELLED).stream()
                .filter(d -> isInEffect(d, today))
                .max(Comparator.comparing(ApprovalDelegation::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ApprovalDelegation::getDelegateId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> resolveApproverIdsActingFor(String actingEmployeeId) {
        Set<String> approverIds = new HashSet<>();
        approverIds.add(actingEmployeeId);

        LocalDate today = LocalDate.now();
        approvalDelegationRepository.findByDelegateIdAndStatusNot(actingEmployeeId, DelegationStatus.CANCELLED).stream()
                .filter(d -> isInEffect(d, today))
                .map(ApprovalDelegation::getDelegatorId)
                // resolveActiveDelegate re-applies the "most recently created wins" precedence rule,
                // so a delegator with a newer delegation naming someone else is correctly excluded here.
                .filter(delegatorId -> resolveActiveDelegate(delegatorId).map(actingEmployeeId::equals).orElse(false))
                .forEach(approverIds::add);

        return approverIds;
    }

    private boolean isInEffect(ApprovalDelegation delegation, LocalDate today) {
        return delegation.getStartDate() != null && !today.isBefore(delegation.getStartDate())
                && delegation.getEndDate() != null && !today.isAfter(delegation.getEndDate());
    }
}
