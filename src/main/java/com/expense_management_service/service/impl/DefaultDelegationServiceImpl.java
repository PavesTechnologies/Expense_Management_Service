package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalDelegation;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.enums.DelegationStatus;
import com.expense_management_service.repository.ApprovalDelegationRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.DelegationService;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class DefaultDelegationServiceImpl implements DelegationService {

    private final ApprovalDelegationRepository approvalDelegationRepository;
    private final EmployeeCacheRepository employeeCacheRepository;

    public DefaultDelegationServiceImpl(ApprovalDelegationRepository approvalDelegationRepository) {
        this.approvalDelegationRepository = approvalDelegationRepository;
        this.employeeCacheRepository = null;
    }

    @Autowired
    public DefaultDelegationServiceImpl(ApprovalDelegationRepository approvalDelegationRepository,
                                       EmployeeCacheRepository employeeCacheRepository) {
        this.approvalDelegationRepository = approvalDelegationRepository;
        this.employeeCacheRepository = employeeCacheRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAct(String actingEmployeeId, String approverId) {
        if (actingEmployeeId == null || approverId == null) {
            return false;
        }
        if (actingEmployeeId.equalsIgnoreCase(approverId)) {
            return true;
        }
        Set<String> actingUserIds = expandEmployeeIds(actingEmployeeId);
        Set<String> targetApproverIds = expandEmployeeIds(approverId);

        for (String actId : actingUserIds) {
            for (String apprId : targetApproverIds) {
                if (actId.equalsIgnoreCase(apprId)) {
                    return true;
                }
            }
        }

        return resolveActiveDelegate(approverId)
                .map(del -> actingUserIds.stream().anyMatch(del::equalsIgnoreCase))
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
        if (actingEmployeeId == null) {
            return approverIds;
        }
        approverIds.addAll(expandEmployeeIds(actingEmployeeId));

        LocalDate today = LocalDate.now();
        Set<String> queryIds = new HashSet<>(approverIds);
        for (String qId : queryIds) {
            approvalDelegationRepository.findByDelegateIdAndStatusNot(qId, DelegationStatus.CANCELLED).stream()
                    .filter(d -> isInEffect(d, today))
                    .map(ApprovalDelegation::getDelegatorId)
                    .filter(delegatorId -> resolveActiveDelegate(delegatorId).map(del -> queryIds.stream().anyMatch(del::equalsIgnoreCase)).orElse(false))
                    .forEach(delegatorId -> approverIds.addAll(expandEmployeeIds(delegatorId)));
        }

        return approverIds;
    }

    private Set<String> expandEmployeeIds(String empId) {
        Set<String> ids = new HashSet<>();
        if (empId == null || empId.isBlank()) {
            return ids;
        }
        ids.add(empId);
        if (employeeCacheRepository != null) {
            employeeCacheRepository.findByEmployeeId(empId).map(EmployeeCache::getEmployeeUuid).ifPresent(ids::add);
            employeeCacheRepository.findByEmployeeUuid(empId).map(EmployeeCache::getEmployeeId).ifPresent(ids::add);
        }
        return ids;
    }

    private boolean isInEffect(ApprovalDelegation delegation, LocalDate today) {
        return delegation.getStartDate() != null && !today.isBefore(delegation.getStartDate())
                && delegation.getEndDate() != null && !today.isAfter(delegation.getEndDate());
    }
}
