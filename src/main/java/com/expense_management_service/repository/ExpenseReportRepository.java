package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExpenseReportRepository extends JpaRepository<ExpenseReport, UUID> {
}
