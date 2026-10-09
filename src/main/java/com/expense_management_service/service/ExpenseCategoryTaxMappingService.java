package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseCategoryTaxMappingRequest;
import com.expense_management_service.dto.response.ExpenseCategoryTaxMappingResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.TaxCode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Effective-dated category -> tax code mapping. At most one mapping covers a category on any date;
 * a mapping that has taken effect is never rewritten, only ended, so the code an expense date
 * resolves to never changes retroactively. {@code ExpenseCategory.taxCode} mirrors today's mapping.
 */
public interface ExpenseCategoryTaxMappingService {

    /** Newest first. */
    List<ExpenseCategoryTaxMappingResponse> list(UUID categoryId);

    ExpenseCategoryTaxMappingResponse create(UUID categoryId, ExpenseCategoryTaxMappingRequest request);

    /** A started mapping may only have its end date changed; a scheduled one may change fully. */
    ExpenseCategoryTaxMappingResponse update(UUID categoryId, UUID mappingId, ExpenseCategoryTaxMappingRequest request);

    /** Only a mapping that starts today or later (so no earlier date has resolved it) can be removed. */
    void delete(UUID categoryId, UUID mappingId);

    /** The code that applies to the category on {@code date}: mapped then, and active and in effect then. */
    Optional<TaxCode> resolveTaxCode(UUID categoryId, LocalDate date);

    /** Category id -> code mapped on {@code date} (any status), in one query. */
    Map<UUID, TaxCode> mappedCodesOn(LocalDate date);

    /**
     * Compatibility path for the category form's single tax code field: ends (or removes, if it
     * starts today or later) the mapping in effect today and, if {@code newTaxCode} is given, maps
     * it from {@code from} until the next scheduled mapping.
     */
    void replaceCurrentMapping(ExpenseCategory category, TaxCode newTaxCode, LocalDate from);

    /** Removes every mapping of a category that is being deleted (audited). */
    void deleteAllForCategory(UUID categoryId);

    /** Re-points every category's {@code taxCode} string at the mapping in effect today. Returns how many changed. */
    int syncCategoryTaxCodes();
}
