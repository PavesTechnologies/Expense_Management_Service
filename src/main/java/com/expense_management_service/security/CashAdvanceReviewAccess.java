package com.expense_management_service.security;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.service.DelegationService;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets a Reporting Manager review one cash advance only when it is assigned to them: the advance's manager, or a
 * manager who has delegated their approvals to them. Used from {@code @PreAuthorize} on the cash advance detail and
 * manager-decision endpoints, so an unassigned Reporting Manager is refused with 403 before any service code runs.
 *
 * <p>Every other role keeps its existing rule; this bean is consulted only for the Reporting Manager role.
 */
@Component("cashAdvanceReviewAccess")
@RequiredArgsConstructor
public class CashAdvanceReviewAccess {

    private final CashAdvanceRepository cashAdvanceRepository;
    private final DelegationService delegationService;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public boolean isAssignedApprover(UUID advanceId) {
        if (advanceId == null) {
            return false;
        }
        String me = currentUserService.getEmployeeId();
        if (me == null || me.isBlank()) {
            return false;
        }
        return cashAdvanceRepository.findById(advanceId)
                .map(CashAdvance::getManagerId)
                .filter(managerId -> !managerId.isBlank())
                .map(managerId -> actingFor(me).contains(managerId))
                .orElse(false);
    }

    private Set<String> actingFor(String me) {
        return delegationService.resolveApproverIdsActingFor(me);
    }
}
