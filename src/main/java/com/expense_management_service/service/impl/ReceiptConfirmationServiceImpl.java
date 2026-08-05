package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.service.ReceiptConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    private final ReceiptRepository receiptRepository;
    private final ReceiptOcrRepository receiptOcrRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseLineItemService expenseLineItemService;
    private final CurrencyRepository currencyRepository;
    private final CurrentUserService currentUserService;

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

        ExpenseLineItemRequest lineItemRequest = new ExpenseLineItemRequest(
                request.categoryId(), expenseDate, merchantName, request.description(), amount, currencyId,
                taxAmount, request.costCenterId(), request.projectId(), request.clientBillable());

        ExpenseLineItemResponse created = expenseLineItemService.create(receipt.getReport().getReportId(), lineItemRequest);
        markCreatedByOcr(created.lineItemId(), latestOcr.isPresent());
        linkReceiptToLineItem(receipt, created.lineItemId());

        log.info("Confirmed receipt {} — created line item {}", receipt.getReceiptId(), created.lineItemId());
        return new ReceiptConfirmResponse(receipt.getReceiptId(), created.lineItemId(), OcrStatus.VERIFIED, false, amount,
                latestOcr.map(ReceiptOcr::getAmount).orElse(null));
    }

    private ReceiptConfirmResponse attachToExistingLineItem(Receipt receipt, ReceiptConfirmRequest request, Optional<ReceiptOcr> latestOcr) {
        ExpenseLineItem existing = expenseLineItemRepository.findById(request.lineItemId())
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + request.lineItemId()));
        if (!existing.getReport().getReportId().equals(receipt.getReport().getReportId())) {
            throw new IllegalArgumentException("lineItemId does not belong to this receipt's expense report");
        }

        BigDecimal enteredAmount = existing.getAmount();
        BigDecimal extractedAmount = latestOcr.map(ReceiptOcr::getAmount).orElse(null);
        boolean amountMismatch = extractedAmount != null && enteredAmount.compareTo(extractedAmount) != 0;

        linkReceiptToLineItem(receipt, existing.getLineItemId());

        log.info("Confirmed receipt {} — linked to existing line item {} (amountMismatch={})",
                receipt.getReceiptId(), existing.getLineItemId(), amountMismatch);
        return new ReceiptConfirmResponse(receipt.getReceiptId(), existing.getLineItemId(), OcrStatus.VERIFIED,
                amountMismatch, enteredAmount, extractedAmount);
    }

    private void linkReceiptToLineItem(Receipt receipt, UUID lineItemId) {
        ExpenseLineItem lineItem = expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
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
