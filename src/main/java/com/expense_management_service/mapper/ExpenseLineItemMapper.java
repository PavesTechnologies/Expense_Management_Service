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
                .exchangeRate(request.exchangeRate())
                .baseAmount(request.baseAmount())
                .taxAmount(request.taxAmount())
                .clientBillable(request.clientBillable())
                .lineStatus(request.lineStatus())
                .build();
    }

    public void updateEntity(ExpenseLineItem entity, ExpenseLineItemRequest request) {
        entity.setExpenseDate(request.expenseDate());
        entity.setMerchantName(request.merchantName());
        entity.setDescription(request.description());
        entity.setAmount(request.amount());
        entity.setExchangeRate(request.exchangeRate());
        entity.setBaseAmount(request.baseAmount());
        entity.setTaxAmount(request.taxAmount());
        entity.setClientBillable(request.clientBillable());
        entity.setLineStatus(request.lineStatus());
    }

    public ExpenseLineItemResponse toResponse(ExpenseLineItem entity) {
        return new ExpenseLineItemResponse(
                entity.getLineItemId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getReport() != null ? entity.getReport().getReportNumber() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryId() : null,
                entity.getCategory() != null ? entity.getCategory().getCategoryName() : null,
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
