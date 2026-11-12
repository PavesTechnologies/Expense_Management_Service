package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.service.TaxSnapshotService;
import com.expense_management_service.service.ReceiptConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReceiptConfirmationService} implementation. Reuses {@code ExpenseLineItemService.create}
 * for actual line item creation rather than duplicating its validation, currency-conversion, and
 * policy-evaluation logic — this class only merges OCR + employee-supplied data into the request
 * shape that service already expects, and links the receipt to the result afterward.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReceiptConfirmationServiceImpl implements ReceiptConfirmationService {

    /** A claimed amount within a cent of the receipt total is a rounding difference, not a mismatch. */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");

    private final ReceiptRepository receiptRepository;
    private final ReceiptOcrRepository receiptOcrRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseLineItemService expenseLineItemService;
    private final CurrencyRepository currencyRepository;
    private final CurrentUserService currentUserService;
    private final TaxSnapshotService taxSnapshotService;
    private final AuditLogRepository auditLogRepository;

    @Override
    public ReceiptConfirmResponse confirm(UUID receiptId, ReceiptConfirmRequest request) {
        Receipt receipt = findReceipt(receiptId);
        assertOwnerOrAdmin(receipt.getEmployeeId());

        Optional<ReceiptOcr> latestOcr = receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)
                .filter(ocr -> ocr.getProcessingStatus() == OcrStatus.OCR_COMPLETED);

        ReceiptConfirmResponse response = request.lineItemId() != null
                ? attachToExistingLineItem(receipt, request, latestOcr)
                : createNewLineItem(receipt, request, latestOcr);

        receipt.setOcrStatus(OcrStatus.VERIFIED.name());
        receiptRepository.save(receipt);
        return response;
    }

    private ReceiptConfirmResponse createNewLineItem(Receipt receipt, ReceiptConfirmRequest request, Optional<ReceiptOcr> latestOcr) {
        if (request.categoryId() == null) {
            throw new IllegalArgumentException("categoryId is required to create a new line item — OCR never determines category");
        }
        BigDecimal amount = firstNonNull(request.amount(), latestOcr.map(ReceiptOcr::getAmount).orElse(null));
        if (amount == null) {
            throw new IllegalArgumentException("amount is required — OCR did not extract one and none was supplied");
        }
        LocalDate expenseDate = firstNonNull(request.expenseDate(), latestOcr.map(ReceiptOcr::getReceiptDate).orElse(null));
        if (expenseDate == null) {
            throw new IllegalArgumentException("expenseDate is required — OCR did not extract one and none was supplied");
        }
        String merchantName = firstNonNull(request.merchantName(), latestOcr.map(ReceiptOcr::getMerchantName).orElse(null));
        BigDecimal taxAmount = firstNonNull(request.taxAmount(), latestOcr.map(ReceiptOcr::getTaxAmount).orElse(null));
        UUID currencyId = resolveCurrencyId(request.currencyId(), latestOcr.map(ReceiptOcr::getCurrencyCode).orElse(null));

        BigDecimal extractedAmount = latestOcr.map(ReceiptOcr::getAmount).orElse(null);
        boolean amountMismatch = isAmountMismatch(amount, extractedAmount, currencyId, latestOcr);
        if (amountMismatch && amount.compareTo(extractedAmount) > 0 && !hasText(request.description())) {
            throw new BusinessRuleViolationException("The receipt total is " + extractedAmount.toPlainString()
                    + " but " + amount.toPlainString() + " was entered. Claim no more than the receipt shows, "
                    + "or add a description explaining the difference.");
        }

        ExpenseLineItemRequest lineItemRequest = new ExpenseLineItemRequest(
                request.categoryId(), expenseDate, merchantName, request.description(), amount, currencyId,
                taxAmount, request.costCenterId(), request.projectId(), request.clientBillable(),
                request.taxCodeId(), request.taxOverrideReason(), request.clientId(), request.allocationReason());

        ExpenseLineItemResponse created = expenseLineItemService.create(receipt.getReport().getReportId(), lineItemRequest);
        markCreatedByOcr(created.lineItemId(), latestOcr.isPresent());
        linkReceiptToLineItem(receipt, created.lineItemId());
        attachOcrTax(created.lineItemId(), latestOcr);
        if (amountMismatch) {
            auditAmountMismatch(created.lineItemId(), amount, extractedAmount, request.description());
        }

        log.info("Confirmed receipt {} — created line item {} (amountMismatch={})",
                receipt.getReceiptId(), created.lineItemId(), amountMismatch);
        return new ReceiptConfirmResponse(receipt.getReceiptId(), created.lineItemId(), OcrStatus.VERIFIED, amountMismatch,
                amount, extractedAmount);
    }

    /**
     * The claimed amount differs from the receipt total OCR read, beyond a cent of rounding. Only compared when
     * both are in the same currency - a claim entered in another currency than the receipt's can't be checked
     * against its total.
     */
    private boolean isAmountMismatch(BigDecimal amount, BigDecimal extractedAmount, UUID currencyId, Optional<ReceiptOcr> latestOcr) {
        if (extractedAmount == null || extractedAmount.signum() <= 0) {
            return false;
        }
        String ocrCurrency = latestOcr.map(ReceiptOcr::getCurrencyCode).orElse(null);
        if (ocrCurrency != null) {
            Optional<String> lineCurrency = currencyRepository.findById(currencyId).map(Currency::getCurrencyCode);
            if (lineCurrency.isPresent() && !lineCurrency.get().equalsIgnoreCase(ocrCurrency)) {
                return false;
            }
        }
        return amount.subtract(extractedAmount).abs().compareTo(AMOUNT_TOLERANCE) > 0;
    }

    /** Records what the receipt said against what was claimed, so a reviewer can trace the difference. */
    private void auditAmountMismatch(UUID lineItemId, BigDecimal amount, BigDecimal extractedAmount, String description) {
        auditLogRepository.save(AuditLog.builder()
                .entityName("ExpenseLineItem")
                .entityId(lineItemId)
                .action("RECEIPT_AMOUNT_MISMATCH")
                .oldValue("Receipt total " + extractedAmount.toPlainString())
                .newValue("Claimed " + amount.toPlainString())
                .reason(hasText(description) ? description.trim() : null)
                .performedBy(currentUserService.getCurrentUser().employeeId())
                .performedAt(LocalDateTime.now())
                .build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ReceiptConfirmResponse attachToExistingLineItem(Receipt receipt, ReceiptConfirmRequest request, Optional<ReceiptOcr> latestOcr) {
        ExpenseLineItem existing = expenseLineItemRepository.findById(request.lineItemId())
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + request.lineItemId()));
        if (!existing.getReport().getReportId().equals(receipt.getReport().getReportId())) {
            throw new IllegalArgumentException("lineItemId does not belong to this receipt's expense report");
        }

        BigDecimal enteredAmount = existing.getAmount();
        BigDecimal extractedAmount = latestOcr.map(ReceiptOcr::getAmount).orElse(null);
        boolean amountMismatch = existing.getCurrency() != null
                ? isAmountMismatch(enteredAmount, extractedAmount, existing.getCurrency().getCurrencyId(), latestOcr)
                : extractedAmount != null && enteredAmount.subtract(extractedAmount).abs().compareTo(AMOUNT_TOLERANCE) > 0;

        linkReceiptToLineItem(receipt, existing.getLineItemId());
        attachOcrTax(existing.getLineItemId(), latestOcr);
        if (amountMismatch) {
            auditAmountMismatch(existing.getLineItemId(), enteredAmount, extractedAmount, existing.getDescription());
        }

        log.info("Confirmed receipt {} — linked to existing line item {} (amountMismatch={})",
                receipt.getReceiptId(), existing.getLineItemId(), amountMismatch);
        return new ReceiptConfirmResponse(receipt.getReceiptId(), existing.getLineItemId(), OcrStatus.VERIFIED,
                amountMismatch, enteredAmount, extractedAmount);
    }

    /** Copies OCR's tax (and how sure it was) and the supplier GSTIN onto the line as evidence and re-validates the line's tax. */
    private void attachOcrTax(UUID lineItemId, Optional<ReceiptOcr> latestOcr) {
        latestOcr.filter(ocr -> ocr.getTaxAmount() != null || ocr.getSupplierGstin() != null).ifPresent(ocr ->
                expenseLineItemRepository.findById(lineItemId).ifPresent(line ->
                        taxSnapshotService.attachOcrEvidence(line, ocr.getTaxAmount(), ocr.getTaxConfidence(), ocr.getSupplierGstin())));
    }

    private void linkReceiptToLineItem(Receipt receipt, UUID lineItemId) {
        ExpenseLineItem lineItem = expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
        List<Receipt> existingReceipts = receiptRepository.findByLineItem_LineItemId(lineItemId);
        for (Receipt existing : existingReceipts) {
            if (!existing.getReceiptId().equals(receipt.getReceiptId())) {
                existing.setLineItem(null);
                receiptRepository.save(existing);
            }
        }
        receipt.setLineItem(lineItem);
    }

    /**
     * Patches {@code createdBy} directly through the repository rather than through
     * {@code ExpenseLineItemService}, which has no parameter for it — a deliberately narrow
     * exception to "reuse create() as-is", limited to one purely informational column that
     * nothing else in that module reads or branches on.
     */
    private void markCreatedByOcr(UUID lineItemId, boolean fromOcr) {
        if (!fromOcr) {
            return;
        }
        expenseLineItemRepository.findById(lineItemId).ifPresent(lineItem -> {
            lineItem.setCreatedBy("OCR");
            expenseLineItemRepository.save(lineItem);
        });
    }

    private UUID resolveCurrencyId(UUID requestedCurrencyId, String extractedCurrencyCode) {
        if (requestedCurrencyId != null) {
            return requestedCurrencyId;
        }
        if (extractedCurrencyCode != null) {
            Optional<Currency> resolved = currencyRepository.findByCurrencyCodeIgnoreCase(extractedCurrencyCode);
            if (resolved.isPresent()) {
                return resolved.get().getCurrencyId();
            }
        }
        throw new IllegalArgumentException(
                "currencyId is required — OCR's extracted currency code did not match a known currency");
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private Receipt findReceipt(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found with id: " + receiptId));
    }

    private void assertOwnerOrAdmin(String employeeId) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!employeeId.equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only confirm receipts on your own expense report");
        }
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
