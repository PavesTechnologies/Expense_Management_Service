package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.dto.response.LineTaxComponentResponse;
import com.expense_management_service.dto.response.LineTaxResponse;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public ExpenseLineItemResponse toResponse(ExpenseLineItem entity, boolean categoryActive, String baseCurrencyCode,
                                               List<PolicyWarningResponse> policyWarnings) {
        var category = entity.getCategory();
        return new ExpenseLineItemResponse(
                entity.getLineItemId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getReport() != null ? entity.getReport().getReportNumber() : null,
                entity.getReport() != null && entity.getReport().getReportStatus() != null
                        ? entity.getReport().getReportStatus().name() : null,
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
                baseCurrencyCode,
                entity.getTaxAmount(),
                entity.getNetAmount(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getProject() != null ? entity.getProject().getProjectId() : null,
                entity.getProject() != null ? entity.getProject().getProjectName() : null,
                entity.getResolvedClientId(),
                entity.getResolvedClientName(),
                entity.getClientBillable(),
                entity.getLineStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                policyWarnings,
                toTaxResponse(entity)
        );
    }

    public LineTaxResponse toTaxResponse(ExpenseLineItem entity) {
        return new LineTaxResponse(
                entity.getTaxCodeId(),
                entity.getTaxCode(),
                entity.getTaxType() != null ? entity.getTaxType().name() : null,
                entity.getTaxRatePercent(),
                entity.getTaxTreatment() != null ? entity.getTaxTreatment().name() : null,
                entity.getNetAmount(),
                entity.getTaxAmount(),
                entity.getCalculatedTaxAmount(),
                entity.getTaxSource() != null ? entity.getTaxSource().name() : null,
                entity.getTaxOverrideReason(),
                entity.getItcRecoverablePercent(),
                entity.getRecoverableTaxAmount(),
                entity.getBaseTaxAmount(),
                entity.getBaseNetAmount(),
                entity.getBaseRecoverableTaxAmount(),
                entity.getOcrTaxAmount(),
                entity.getTaxValidationStatus() != null ? entity.getTaxValidationStatus().name() : null,
                entity.getTaxValidationReasons() == null || entity.getTaxValidationReasons().isBlank()
                        ? List.of() : List.of(entity.getTaxValidationReasons().split(",")),
                entity.getTaxSnapshotAt(),
                entity.getTaxComponents() == null ? List.of() : entity.getTaxComponents().stream()
                        .map(c -> new LineTaxComponentResponse(c.getComponentCode().name(), c.getLabel(), c.getRatePercent(),
                                c.getTaxAmount(), c.getBaseTaxAmount(), c.getSource().name()))
                        .toList()
        );
    }
}
