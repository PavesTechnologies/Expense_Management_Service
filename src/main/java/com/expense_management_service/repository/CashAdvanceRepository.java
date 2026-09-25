package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CashAdvanceRepository extends JpaRepository<CashAdvance, UUID> {
    List<CashAdvance> findByEmployeeId(String employeeId);
    List<CashAdvance> findByEmployeeIdAndStatus(String employeeId, String status);
    List<CashAdvance> findByEmployeeIdAndStatusIn(String employeeId, Collection<String> statuses);
    List<CashAdvance> findByStatus(String status);
    List<CashAdvance> findByStatusIn(Collection<String> statuses);
    List<CashAdvance> findByManagerId(String managerId);
    List<CashAdvance> findByManagerIdAndStatus(String managerId, String status);
    List<CashAdvance> findByManagerIdIn(Collection<String> managerIds);
    List<CashAdvance> findByManagerIdInAndStatus(Collection<String> managerIds, String status);
    List<CashAdvance> findByManagerIdInAndStatusIn(Collection<String> managerIds, Collection<String> statuses);
}
