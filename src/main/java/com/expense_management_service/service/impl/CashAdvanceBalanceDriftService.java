package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.repository.CashAdvanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only drift check: compares every advance's stored outstanding balance with the balance its records support.
 * It changes nothing. Repairing a drift is a Finance decision and is not automated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceBalanceDriftService {

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceBalanceCalculator calculator;

    @Transactional(readOnly = true)
    public List<CashAdvanceBalanceCalculator.Drift> findDrift() {
        List<CashAdvance> advances = cashAdvanceRepository.findAll();
        List<CashAdvanceBalanceCalculator.Drift> drift = calculator.findDrift(advances);
        if (!drift.isEmpty()) {
            // Log the advance ids and amounts only. No employee names or bank details.
            log.warn("Cash advance balance drift on {} advance(s): {}", drift.size(),
                    drift.stream().map(d -> d.advanceId() + " stored=" + d.stored() + " computed=" + d.computed()).toList());
        }
        return drift;
    }
}
