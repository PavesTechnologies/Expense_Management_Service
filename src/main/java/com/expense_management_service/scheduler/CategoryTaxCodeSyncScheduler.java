package com.expense_management_service.scheduler;

import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code expense_category.tax_code} pointing at the mapping in effect today, so a scheduled
 * mapping (e.g. GST20 from 01-Apr) shows up on the category the day it starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryTaxCodeSyncScheduler {

    private final ExpenseCategoryTaxMappingService mappingService;

    @Scheduled(cron = "${tax.category-sync.cron:0 5 0 * * *}")
    public void syncCategoryTaxCodes() {
        int changed = mappingService.syncCategoryTaxCodes();
        if (changed > 0) {
            log.info("Category tax code sync: {} categories now show the mapping in effect today", changed);
        }
    }
}
