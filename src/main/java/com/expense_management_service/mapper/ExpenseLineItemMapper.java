package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import org.springframework.stereotype.Component;

@Component
public class ExpenseLineItemMapper {

    public ExpenseLineItem toEntity(ExpenseLineItemRequest request) {
        return ExpenseLineItem.builder()
                .expenseDate(request.expenseDate())
                .merchantName(request.merchantName())
                .description(request.description())
                .amount(request.amount())
                .taxAmount(request.taxAmount())
                .clientBillable(request.clientBillable())
                .build();
    }

    public void updateEntity(ExpenseLineItem entity, ExpenseLineItemRequest request) {
        entity.setExpenseDate(request.expenseDate());
        entity.setMerchantName(request.merchantName());
        entity.setDescription(request.description());
        entity.setAmount(request.amount());
        entity.setTaxAmount(request.taxAmount());
        entity.setClientBillable(request.clientBillable());
    }

    public ExpenseLineItemResponse toResponse(ExpenseLineItem entity, boolean categoryActive) {
        var category = entity.getCategory();
        return new ExpenseLineItemResponse(
                entity.getLineItemId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getReport() != null ? entity.getReport().getReportNumber() : null,
                entity.getReport() != null ? entity.getReport().getReportStatus() : null,
                category != null ? category.getCategoryId() : null,
                category != null ? category.getCategoryName() : null,
                categoryActive,
                category != null && Boolean.TRUE.equals(category.getReceiptRequired()),
                category != null ? category.getMaxLimit() : null,
                entity.getExpenseDate(),
                entity.getMerchantName(),
                entity.getDescription(),
                entity.getAmount(),
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getExchangeRate(),
                entity.getBaseAmount(),
                entity.getTaxAmount(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getProject() != null ? entity.getProject().getProjectId() : null,
                entity.getProject() != null ? entity.getProject().getProjectName() : null,
                entity.getClientBillable(),
                entity.getLineStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
