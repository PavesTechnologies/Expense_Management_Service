package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.DuplicateReceiptStatus;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.service.ReceiptDuplicateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptDuplicateServiceImpl implements ReceiptDuplicateService {

    /** Reports that are not (or no longer) a claim: never submitted, or finally rejected/cancelled. */
    private static final Set<ReportStatus> NOT_CLAIMED = EnumSet.of(ReportStatus.DRAFT, ReportStatus.REJECTED, ReportStatus.CANCELLED);

    private final ReceiptRepository receiptRepository;
    private final ReceiptOcrRepository receiptOcrRepository;

    @Override
    public List<DuplicateMatch> findMatches(Receipt receipt) {
        Map<UUID, DuplicateMatch> byOtherReceipt = new LinkedHashMap<>();

        if (StringUtils.hasText(receipt.getFileHash())) {
            for (Receipt other : receiptRepository.findByFileHash(receipt.getFileHash())) {
                addIfClaimed(byOtherReceipt, receipt, other, MatchReason.SAME_FILE);
            }
        }

        ReceiptOcr ocr = latestCompletedOcr(receipt);
        if (ocr != null) {
            String invoiceNumber = normalizeInvoiceNumber(ocr.getInvoiceNumber());
            if (invoiceNumber != null) {
                for (ReceiptOcr candidate : receiptOcrRepository.findCompletedByNormalizedInvoiceNumber(
                        OcrStatus.OCR_COMPLETED, invoiceNumber, receipt.getReceiptId())) {
                    if (sameSupplier(ocr, candidate) && sameOccasion(ocr, candidate)) {
                        addIfClaimed(byOtherReceipt, receipt, candidate.getReceipt(), MatchReason.SAME_INVOICE);
                    }
                }
            }
            if (ocr.getMerchantName() != null && ocr.getAmount() != null
                    && ocr.getCurrencyCode() != null && ocr.getReceiptDate() != null) {
                for (ReceiptOcr candidate : receiptOcrRepository
                        .findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                                ocr.getMerchantName(), ocr.getAmount(), ocr.getCurrencyCode(), ocr.getReceiptDate(),
                                OcrStatus.OCR_COMPLETED, receipt.getReceiptId())) {
                    addIfClaimed(byOtherReceipt, receipt, candidate.getReceipt(), MatchReason.SAME_DETAILS);
                }
            }
        }
        return new ArrayList<>(byOtherReceipt.values());
    }

    @Override
    public DuplicateSummary summarize(Receipt receipt) {
        List<DuplicateMatch> matches = findMatches(receipt);
        return matches.stream()
                .filter(DuplicateMatch::blocking)
                .findFirst()
                .map(m -> new DuplicateSummary(DuplicateReceiptStatus.BLOCKING, m.message()))
                .or(() -> matches.stream().findFirst()
                        .map(m -> new DuplicateSummary(DuplicateReceiptStatus.WARNING, m.message())))
                .orElse(DuplicateSummary.NONE);
    }

    @Override
    public List<DuplicateMatch> findWarningMatchesForLineItem(UUID lineItemId) {
        List<DuplicateMatch> warnings = new ArrayList<>();
        for (Receipt receipt : receiptRepository.findByLineItem_LineItemId(lineItemId)) {
            List<DuplicateMatch> matches = findMatches(receipt);
            // A receipt that is already blocked doesn't also need an explanation.
            if (matches.stream().noneMatch(DuplicateMatch::blocking)) {
                warnings.addAll(matches);
            }
        }
        return warnings;
    }

    @Override
    public List<DuplicateMatch> findBlockingMatchesForReport(ExpenseReport report) {
        return receiptRepository.findByReport_ReportId(report.getReportId()).stream()
                .filter(receipt -> receipt.getLineItem() != null)
                .flatMap(receipt -> findMatches(receipt).stream())
                .filter(DuplicateMatch::blocking)
                .toList();
    }

    /**
     * Records {@code other} as a match unless it is the same receipt, is on the same line item,
     * or isn't a claim (unattached, or its report is draft/rejected/cancelled). Keeps the
     * strongest reason when one receipt matches several ways.
     */
    private void addIfClaimed(Map<UUID, DuplicateMatch> byOtherReceipt, Receipt receipt, Receipt other, MatchReason reason) {
        if (other == null || other.getReceiptId() == null || other.getReceiptId().equals(receipt.getReceiptId())
                || other.getLineItem() == null || other.getReport() == null) {
            return;
        }
        boolean sameReport = receipt.getReport() != null
                && Objects.equals(other.getReport().getReportId(), receipt.getReport().getReportId());
        if (sameReport && receipt.getLineItem() != null
                && Objects.equals(other.getLineItem().getLineItemId(), receipt.getLineItem().getLineItemId())) {
            return;
        }
        if (!sameReport && NOT_CLAIMED.contains(other.getReport().getReportStatus())) {
            return;
        }
        DuplicateMatch existing = byOtherReceipt.get(other.getReceiptId());
        if (existing != null && existing.reason().ordinal() <= reason.ordinal()) {
            return;
        }
        boolean sameEmployee = Objects.equals(other.getEmployeeId(), receipt.getEmployeeId());
        byOtherReceipt.put(other.getReceiptId(), new DuplicateMatch(
                receipt.getReceiptId(),
                receipt.getOriginalFileName(),
                receipt.getLineItem() != null ? receipt.getLineItem().getLineItemId() : null,
                reason,
                other.getReceiptId(),
                other.getReport().getReportId(),
                other.getReport().getReportNumber(),
                sameEmployee,
                sameReport,
                message(reason, sameReport, sameEmployee, other.getReport().getReportNumber())));
    }

    private static String message(MatchReason reason, boolean sameReport, boolean sameEmployee, String otherReportNumber) {
        String what = switch (reason) {
            case SAME_FILE -> "This exact receipt file";
            case SAME_INVOICE -> "A receipt with the same invoice number from the same supplier";
            case SAME_DETAILS -> "A receipt from the same merchant for the same amount and date";
        };
        String where;
        if (sameReport) {
            where = "another expense in this report";
        } else if (sameEmployee) {
            where = "your expense report" + (otherReportNumber != null ? " " + otherReportNumber : "");
        } else {
            where = "another employee's expense report";
        }
        String outcome = reason == MatchReason.SAME_DETAILS
                ? " If this is a separate purchase, explain it before submitting."
                : " The same receipt can't be claimed twice.";
        return what + " has already been claimed on " + where + "." + outcome;
    }

    private ReceiptOcr latestCompletedOcr(Receipt receipt) {
        return receiptOcrRepository.findByReceipt_ReceiptIdOrderByProcessedAtDesc(receipt.getReceiptId()).stream()
                .filter(o -> o.getProcessingStatus() == OcrStatus.OCR_COMPLETED)
                .max(Comparator.comparing(ReceiptOcr::getProcessedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /** Upper-cased, spaces removed; null when too short to identify a bill on its own. */
    static String normalizeInvoiceNumber(String invoiceNumber) {
        if (invoiceNumber == null) {
            return null;
        }
        String normalized = invoiceNumber.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        return normalized.length() >= 3 ? normalized : null;
    }

    /** Same GSTIN when both carry one; otherwise the same merchant name. */
    private static boolean sameSupplier(ReceiptOcr a, ReceiptOcr b) {
        if (StringUtils.hasText(a.getSupplierGstin()) && StringUtils.hasText(b.getSupplierGstin())) {
            return a.getSupplierGstin().trim().equalsIgnoreCase(b.getSupplierGstin().trim());
        }
        String merchantA = normalizeMerchant(a.getMerchantName());
        return merchantA != null && merchantA.equals(normalizeMerchant(b.getMerchantName()));
    }

    /**
     * Invoice numbers can repeat across days (many restaurants reset bill numbers daily), so the
     * date must match too; the amount stands in only when a date wasn't extracted.
     */
    private static boolean sameOccasion(ReceiptOcr a, ReceiptOcr b) {
        if (a.getReceiptDate() != null && b.getReceiptDate() != null) {
            return a.getReceiptDate().equals(b.getReceiptDate());
        }
        return a.getAmount() != null && b.getAmount() != null && a.getAmount().compareTo(b.getAmount()) == 0;
    }

    private static String normalizeMerchant(String merchant) {
        if (!StringUtils.hasText(merchant)) {
            return null;
        }
        return merchant.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
