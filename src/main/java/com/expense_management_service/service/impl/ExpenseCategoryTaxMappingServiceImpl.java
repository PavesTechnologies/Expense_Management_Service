package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseCategoryTaxMappingRequest;
import com.expense_management_service.dto.response.ExpenseCategoryTaxMappingResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseCategoryTaxMapping;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseCategoryTaxMappingRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import com.expense_management_service.service.TaxAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseCategoryTaxMappingServiceImpl implements ExpenseCategoryTaxMappingService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String ENTITY = "ExpenseCategoryTaxMapping";

    private final ExpenseCategoryTaxMappingRepository mappingRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final TaxAuditService taxAuditService;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryTaxMappingResponse> list(UUID categoryId) {
        findCategory(categoryId);
        LocalDate today = today();
        return mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(categoryId).stream()
                .map(m -> toResponse(m, today))
                .toList();
    }

    @Override
    public ExpenseCategoryTaxMappingResponse create(UUID categoryId, ExpenseCategoryTaxMappingRequest request) {
        ExpenseCategory category = findCategory(categoryId);
        TaxCode taxCode = findActiveTaxCode(request.taxCodeId());
        assertWindowValid(request.effectiveFrom(), request.effectiveTo(), taxCode);
        assertNoOverlap(categoryId, null, request.effectiveFrom(), request.effectiveTo());

        ExpenseCategoryTaxMapping saved = mappingRepository.save(ExpenseCategoryTaxMapping.builder()
                .category(category)
                .taxCode(taxCode)
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .createdBy(actor())
                .build());
        taxAuditService.record(ENTITY, saved.getMappingId(), "TAX_MAPPING_ADDED", null, snapshot(saved), AuditSource.ADMIN, null);
        syncCategoryTaxCode(category);
        return toResponse(saved, today());
    }

    @Override
    public ExpenseCategoryTaxMappingResponse update(UUID categoryId, UUID mappingId, ExpenseCategoryTaxMappingRequest request) {
        ExpenseCategoryTaxMapping mapping = findMapping(categoryId, mappingId);
        LocalDate today = today();
        Map<String, Object> before = snapshot(mapping);

        TaxCode taxCode;
        if (!mapping.getEffectiveFrom().isAfter(today)) {
            // Already in effect: expense dates in its window may have resolved it, so only the end moves.
            if (!mapping.getTaxCode().getTaxCodeId().equals(request.taxCodeId())
                    || !mapping.getEffectiveFrom().equals(request.effectiveFrom())) {
                throw new IllegalArgumentException(
                        "This mapping took effect on " + mapping.getEffectiveFrom() + ", so only its end date can change. "
                                + "To use a different tax code from a date, end this mapping and add a new one.");
            }
            if (request.effectiveTo() != null && request.effectiveTo().isBefore(today.minusDays(1))) {
                throw new IllegalArgumentException(
                        "A mapping in effect can be ended yesterday at the earliest - earlier dates have already used it.");
            }
            taxCode = mapping.getTaxCode();
        } else {
            taxCode = mapping.getTaxCode().getTaxCodeId().equals(request.taxCodeId())
                    ? mapping.getTaxCode()
                    : findActiveTaxCode(request.taxCodeId());
            if (request.effectiveFrom().isBefore(today)) {
                throw new IllegalArgumentException("A scheduled mapping cannot be moved into the past.");
            }
        }
        assertWindowValid(request.effectiveFrom(), request.effectiveTo(), taxCode);
        assertNoOverlap(categoryId, mappingId, request.effectiveFrom(), request.effectiveTo());

        mapping.setTaxCode(taxCode);
        mapping.setEffectiveFrom(request.effectiveFrom());
        mapping.setEffectiveTo(request.effectiveTo());
        ExpenseCategoryTaxMapping saved = mappingRepository.save(mapping);
        taxAuditService.record(ENTITY, mappingId,
                request.effectiveTo() != null && !Objects.equals(before.get("effectiveTo"), String.valueOf(request.effectiveTo()))
                        ? "TAX_MAPPING_ENDED" : "TAX_MAPPING_UPDATED",
                before, snapshot(saved), AuditSource.ADMIN, null);
        syncCategoryTaxCode(saved.getCategory());
        return toResponse(saved, today);
    }

    @Override
    public void delete(UUID categoryId, UUID mappingId) {
        ExpenseCategoryTaxMapping mapping = findMapping(categoryId, mappingId);
        if (mapping.getEffectiveFrom().isBefore(today())) {
            throw new IllegalArgumentException(
                    "This mapping has been in effect since " + mapping.getEffectiveFrom() + " and cannot be removed - end it instead.");
        }
        removeMapping(mapping);
        syncCategoryTaxCode(mapping.getCategory());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaxCode> resolveTaxCode(UUID categoryId, LocalDate date) {
        return mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(categoryId).stream()
                .filter(m -> m.coversDate(date))
                .map(ExpenseCategoryTaxMapping::getTaxCode)
                .filter(code -> code.isApplicableOn(date))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TaxCode> mappedCodesOn(LocalDate date) {
        return mappingRepository.findAllCoveringDate(date).stream()
                .collect(Collectors.toMap(m -> m.getCategory().getCategoryId(), ExpenseCategoryTaxMapping::getTaxCode, (a, b) -> a));
    }

    @Override
    public void replaceCurrentMapping(ExpenseCategory category, TaxCode newTaxCode, LocalDate from) {
        LocalDate today = today();
        List<ExpenseCategoryTaxMapping> mappings =
                mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(category.getCategoryId());
        Optional<ExpenseCategoryTaxMapping> current = mappings.stream().filter(m -> m.coversDate(from)).findFirst();
        if (current.isPresent() && newTaxCode != null
                && current.get().getTaxCode().getTaxCodeId().equals(newTaxCode.getTaxCodeId())) {
            return;
        }

        current.ifPresent(m -> {
            if (!m.getEffectiveFrom().isBefore(from) || !m.getEffectiveFrom().isBefore(today)) {
                removeMapping(m);
            } else {
                Map<String, Object> before = snapshot(m);
                m.setEffectiveTo(from.minusDays(1));
                mappingRepository.save(m);
                taxAuditService.record(ENTITY, m.getMappingId(), "TAX_MAPPING_ENDED", before, snapshot(m), AuditSource.ADMIN, null);
            }
        });

        if (newTaxCode != null) {
            LocalDate to = mappings.stream()
                    .map(ExpenseCategoryTaxMapping::getEffectiveFrom)
                    .filter(start -> start.isAfter(from))
                    .min(Comparator.naturalOrder())
                    .map(next -> next.minusDays(1))
                    .orElse(null);
            assertWindowValid(from, to, newTaxCode);
            ExpenseCategoryTaxMapping saved = mappingRepository.save(ExpenseCategoryTaxMapping.builder()
                    .category(category)
                    .taxCode(newTaxCode)
                    .effectiveFrom(from)
                    .effectiveTo(to)
                    .createdBy(actor())
                    .build());
            taxAuditService.record(ENTITY, saved.getMappingId(), "TAX_MAPPING_ADDED", null, snapshot(saved), AuditSource.ADMIN, null);
        }
        syncCategoryTaxCode(category);
    }

    @Override
    public void deleteAllForCategory(UUID categoryId) {
        mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(categoryId).forEach(this::removeMapping);
    }

    @Override
    public int syncCategoryTaxCodes() {
        Map<UUID, TaxCode> today = mappedCodesOn(today());
        int changed = 0;
        for (ExpenseCategory category : expenseCategoryRepository.findAll()) {
            TaxCode mapped = today.get(category.getCategoryId());
            // Categories never mapped keep whatever legacy string they have.
            if (mapped == null && category.getTaxCode() != null
                    && mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(category.getCategoryId()).isEmpty()) {
                continue;
            }
            String expected = mapped == null ? null : mapped.getTaxCode();
            if (!Objects.equals(expected, category.getTaxCode())) {
                category.setTaxCode(expected);
                expenseCategoryRepository.save(category);
                changed++;
            }
        }
        return changed;
    }

    private void syncCategoryTaxCode(ExpenseCategory category) {
        String expected = mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(category.getCategoryId()).stream()
                .filter(m -> m.coversDate(today()))
                .map(m -> m.getTaxCode().getTaxCode())
                .findFirst()
                .orElse(null);
        if (!Objects.equals(expected, category.getTaxCode())) {
            category.setTaxCode(expected);
            expenseCategoryRepository.save(category);
        }
    }

    private void removeMapping(ExpenseCategoryTaxMapping mapping) {
        taxAuditService.record(ENTITY, mapping.getMappingId(), "TAX_MAPPING_REMOVED", snapshot(mapping), null, AuditSource.ADMIN, null);
        mappingRepository.delete(mapping);
    }

    /** Inside the code's own validity window, and to on/after from. */
    private void assertWindowValid(LocalDate from, LocalDate to, TaxCode taxCode) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("effectiveTo cannot be before effectiveFrom");
        }
        if (from.isBefore(taxCode.getEffectiveFrom())) {
            throw new IllegalArgumentException("Tax code " + taxCode.getTaxCode() + " only takes effect on "
                    + taxCode.getEffectiveFrom() + " - the mapping cannot start before that.");
        }
        if (taxCode.getEffectiveTo() != null && (to == null || to.isAfter(taxCode.getEffectiveTo()))) {
            throw new IllegalArgumentException("Tax code " + taxCode.getTaxCode() + " ends on " + taxCode.getEffectiveTo()
                    + " - the mapping must end on or before that.");
        }
    }

    /** VR-TAX-06: only one mapping per category per date. */
    private void assertNoOverlap(UUID categoryId, UUID excludeMappingId, LocalDate from, LocalDate to) {
        mappingRepository.findByCategory_CategoryIdOrderByEffectiveFromDesc(categoryId).stream()
                .filter(m -> !m.getMappingId().equals(excludeMappingId))
                .filter(m -> m.overlaps(from, to))
                .findFirst()
                .ifPresent(m -> {
                    throw new IllegalArgumentException("This category is already mapped to " + m.getTaxCode().getTaxCode()
                            + " from " + m.getEffectiveFrom() + (m.getEffectiveTo() == null ? " (open-ended)" : " to " + m.getEffectiveTo())
                            + ". Only one tax code can apply on any date - end that mapping first.");
                });
    }

    private TaxCode findActiveTaxCode(UUID taxCodeId) {
        TaxCode taxCode = taxCodeRepository.findById(taxCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxCode not found with id: " + taxCodeId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(taxCode.getStatus())) {
            throw new IllegalArgumentException("Tax code " + taxCode.getTaxCode() + " is not Active and cannot be mapped to a category");
        }
        return taxCode;
    }

    private ExpenseCategory findCategory(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }

    private ExpenseCategoryTaxMapping findMapping(UUID categoryId, UUID mappingId) {
        ExpenseCategoryTaxMapping mapping = mappingRepository.findById(mappingId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax mapping not found with id: " + mappingId));
        if (!mapping.getCategory().getCategoryId().equals(categoryId)) {
            throw new ResourceNotFoundException("Tax mapping " + mappingId + " does not belong to category " + categoryId);
        }
        return mapping;
    }

    private String actor() {
        try {
            return currentUserService.getEmployeeId();
        } catch (RuntimeException ex) {
            return AuditSource.SYSTEM.name();
        }
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private static Map<String, Object> snapshot(ExpenseCategoryTaxMapping m) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("categoryId", String.valueOf(m.getCategory().getCategoryId()));
        values.put("taxCode", m.getTaxCode().getTaxCode());
        values.put("effectiveFrom", String.valueOf(m.getEffectiveFrom()));
        values.put("effectiveTo", String.valueOf(m.getEffectiveTo()));
        return values;
    }

    private static ExpenseCategoryTaxMappingResponse toResponse(ExpenseCategoryTaxMapping m, LocalDate today) {
        String state = m.getEffectiveFrom().isAfter(today) ? "SCHEDULED"
                : (m.getEffectiveTo() != null && m.getEffectiveTo().isBefore(today) ? "PAST" : "CURRENT");
        TaxCode code = m.getTaxCode();
        return new ExpenseCategoryTaxMappingResponse(m.getMappingId(), m.getCategory().getCategoryId(), code.getTaxCodeId(),
                code.getTaxCode(), code.getTaxName(), code.getRatePercent(), code.getStatus(),
                m.getEffectiveFrom(), m.getEffectiveTo(), state, m.getCreatedBy(), m.getCreatedAt());
    }
}
