package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {
}
