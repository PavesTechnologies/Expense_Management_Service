package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseCategoryTaxMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryTaxMappingRepository extends JpaRepository<ExpenseCategoryTaxMapping, UUID> {

    /** Newest first. */
    List<ExpenseCategoryTaxMapping> findByCategory_CategoryIdOrderByEffectiveFromDesc(UUID categoryId);

    @Query("SELECT m FROM ExpenseCategoryTaxMapping m JOIN FETCH m.taxCode "
            + "WHERE m.effectiveFrom <= :date AND (m.effectiveTo IS NULL OR m.effectiveTo >= :date)")
    List<ExpenseCategoryTaxMapping> findAllCoveringDate(@Param("date") LocalDate date);

    /** Categories mapped to the code on {@code date} or from a later date. */
    @Query("SELECT COUNT(DISTINCT m.category.categoryId) FROM ExpenseCategoryTaxMapping m "
            + "WHERE m.taxCode.taxCodeId = :taxCodeId AND (m.effectiveTo IS NULL OR m.effectiveTo >= :date)")
    long countCategoriesMappedOnOrAfter(@Param("taxCodeId") UUID taxCodeId, @Param("date") LocalDate date);

    List<ExpenseCategoryTaxMapping> findByTaxCode_TaxCodeId(UUID taxCodeId);

    void deleteByCategory_CategoryId(UUID categoryId);
}
