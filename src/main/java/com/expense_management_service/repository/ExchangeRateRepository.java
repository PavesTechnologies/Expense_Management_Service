package com.expense_management_service.repository;

import com.expense_management_service.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {
}
