package com.expense_management_service.repository;

import com.expense_management_service.entity.TaxCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaxCodeRepository extends JpaRepository<TaxCode, UUID> {

    Optional<TaxCode> findByTaxCodeIgnoreCase(String taxCode);

    List<TaxCode> findAllByOrderByTaxCodeAsc();

    List<TaxCode> findByStatusIgnoreCaseOrderByRatePercentAsc(String status);
}
