package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.entity.ExpenseCategory;
import org.springframework.stereotype.Component;

@Component
public class ExpenseCategoryMapper {

    public ExpenseCategory toEntity(ExpenseCategoryRequest request) {
        return ExpenseCategory.builder()
                .categoryCode(request.categoryCode())
                .categoryName(request.categoryName())
                .description(request.description())
                .receiptRequired(request.receiptRequired())
                .maxLimit(request.maxLimit())
                .taxCode(request.taxCode())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .status(request.status())
                .build();
    }

    public void updateEntity(ExpenseCategory entity, ExpenseCategoryRequest request) {
        entity.setCategoryCode(request.categoryCode());
        entity.setCategoryName(request.categoryName());
        entity.setDescription(request.description());
        entity.setReceiptRequired(request.receiptRequired());
        entity.setMaxLimit(request.maxLimit());
        entity.setTaxCode(request.taxCode());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setStatus(request.status());
    }

    public ExpenseCategoryResponse toResponse(ExpenseCategory entity) {
        return new ExpenseCategoryResponse(
                entity.getCategoryId(),
                entity.getCategoryCode(),
                entity.getCategoryName(),
                entity.getGlAccount() != null ? entity.getGlAccount().getGlAccountId() : null,
                entity.getGlAccount() != null ? entity.getGlAccount().getGlAccountName() : null,
                entity.getDescription(),
                entity.getReceiptRequired(),
                entity.getMaxLimit(),
                entity.getTaxCode(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
