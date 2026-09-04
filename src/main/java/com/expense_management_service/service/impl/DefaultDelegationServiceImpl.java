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
import java.util.Optional;

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

    private boolean isInEffect(ApprovalDelegation delegation, LocalDate today) {
        return delegation.getStartDate() != null && !today.isBefore(delegation.getStartDate())
                && delegation.getEndDate() != null && !today.isAfter(delegation.getEndDate());
    }
}
