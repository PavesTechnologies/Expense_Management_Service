package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.TaxCodeComponentRequest;
import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseCategoryTaxMapping;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.entity.TaxCodeComponent;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.mapper.TaxCodeMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseCategoryTaxMappingRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.TaxAuditService;
import com.expense_management_service.service.TaxCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tax Configuration master. A code's rate is the sum of its components; CGST_SGST and IGST codes
 * get their components generated from the rate when none are sent. Once any expense line has
 * snapshotted a code, its type, rate, components and ITC % are locked - a rate change is a new
 * code plus a dated category mapping. Every change is written to the audit log.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TaxCodeServiceImpl implements TaxCodeService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String ENTITY = "TaxCode";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Phase 1: India GST only; other countries are added as configuration later. */
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of("IN");
    private static final Set<TaxType> TYPES_OFFERED = EnumSet.of(TaxType.CGST_SGST, TaxType.IGST, TaxType.EXEMPT, TaxType.OTHER);
    private static final Set<TaxComponentCode> INDIA_COMPONENTS =
            EnumSet.of(TaxComponentCode.CGST, TaxComponentCode.SGST, TaxComponentCode.UTGST, TaxComponentCode.IGST,
                    TaxComponentCode.CESS, TaxComponentCode.OTHER);

    private final TaxCodeRepository taxCodeRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final ExpenseCategoryTaxMappingRepository mappingRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final GlAccountRepository glAccountRepository;
    private final TaxCodeMapper taxCodeMapper;
    private final TaxAuditService taxAuditService;

    @Override
    public TaxCodeResponse create(TaxCodeRequest request) {
        assertCodeNotDuplicate(request.taxCode(), null);
        TaxCode entity = taxCodeMapper.toEntity(request);
        entity.setCountryCode(resolveCountry(request.countryCode(), null));
        assertTypeOffered(request.taxType());
        entity.setInputTaxGlAccount(resolveGlAccount(request.inputTaxGlAccountId()));
        defaultStatus(entity);

        List<TaxCodeComponent> components = buildComponents(request, entity);
        applyComponentsAndRate(entity, components);
        applyItc(entity, request, null);
        assertDatesValid(entity);

        TaxCode saved = taxCodeRepository.save(entity);
        taxAuditService.record(ENTITY, saved.getTaxCodeId(), "TAX_CODE_CREATED", null, snapshot(saved), AuditSource.ADMIN, request.changeReason());
        return toResponse(saved);
    }

    @Override
    public TaxCodeResponse update(UUID taxCodeId, TaxCodeRequest request) {
        TaxCode entity = findEntity(taxCodeId);
        assertCodeNotDuplicate(request.taxCode(), taxCodeId);
        Map<String, Object> before = snapshot(entity);
        String previousCode = entity.getTaxCode();
        String previousStatus = entity.getStatus();
        TaxType previousType = entity.getTaxType();
        String previousComponents = componentSignature(entity.getComponents());
        BigDecimal previousItc = entity.getItcRecoverablePercent();

        taxCodeMapper.updateEntity(entity, request);
        entity.setCountryCode(resolveCountry(request.countryCode(), entity.getCountryCode()));
        if (request.taxType() != previousType) {
            assertTypeOffered(request.taxType());
        }
        entity.setInputTaxGlAccount(resolveGlAccount(request.inputTaxGlAccountId()));
        defaultStatus(entity);

        List<TaxCodeComponent> components = buildComponents(request, entity);
        applyItc(entity, request, previousItc);
        assertDatesValid(entity);

        long usage = expenseLineItemRepository.countByTaxCodeId(taxCodeId);
        if (usage > 0) {
            // VR-TAX-13: what already-snapshotted lines relied on cannot move under them.
            boolean treatmentChanged = request.taxType() != previousType
                    || !componentSignature(components).equals(previousComponents)
                    || entity.getItcRecoverablePercent().compareTo(previousItc) != 0;
            if (treatmentChanged) {
                throw new IllegalArgumentException("Tax code " + previousCode + " is used by " + usage
                        + " expense line(s), so its type, rate, components and ITC % are locked. "
                        + "Create a new tax code and remap the categories from the date the change applies.");
            }
            relabelComponents(entity, components);
        } else {
            applyComponentsAndRate(entity, components);
        }

        boolean deactivating = STATUS_ACTIVE.equalsIgnoreCase(previousStatus) && STATUS_INACTIVE.equalsIgnoreCase(entity.getStatus());
        if (deactivating && !StringUtils.hasText(request.changeReason())) {
            throw new IllegalArgumentException("A reason is required to deactivate a tax code.");
        }

        TaxCode saved = taxCodeRepository.save(entity);

        // Categories reference the code by value (a mirror of today's mapping), so a rename carries them along.
        if (!previousCode.equalsIgnoreCase(saved.getTaxCode())) {
            List<ExpenseCategory> mapped = expenseCategoryRepository.findByTaxCodeIgnoreCase(previousCode);
            mapped.forEach(category -> category.setTaxCode(saved.getTaxCode()));
            expenseCategoryRepository.saveAll(mapped);
        }

        taxAuditService.record(ENTITY, taxCodeId, deactivating ? "TAX_CODE_DEACTIVATED" : "TAX_CODE_UPDATED",
                before, snapshot(saved), AuditSource.ADMIN, request.changeReason());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TaxCodeResponse getById(UUID taxCodeId) {
        return toResponse(findEntity(taxCodeId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxCodeResponse> getAll() {
        return taxCodeRepository.findAllByOrderByTaxCodeAsc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxCodeResponse> getActive() {
        return taxCodeRepository.findByStatusIgnoreCaseOrderByRatePercentAsc(STATUS_ACTIVE).stream().map(this::toResponse).toList();
    }

    @Override
    public void delete(UUID taxCodeId) {
        TaxCode entity = findEntity(taxCodeId);

        long usage = expenseLineItemRepository.countByTaxCodeId(taxCodeId);
        if (usage > 0) {
            throw new ResourceInUseException("Tax code cannot be deleted because " + usage
                    + " expense line(s) have used it. Deactivate it instead.");
        }
        Set<String> categoryNames = mappingRepository.findByTaxCode_TaxCodeId(taxCodeId).stream()
                .map(ExpenseCategoryTaxMapping::getCategory)
                .map(ExpenseCategory::getCategoryName)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        expenseCategoryRepository.findByTaxCodeIgnoreCase(entity.getTaxCode()).stream()
                .map(ExpenseCategory::getCategoryName)
                .forEach(categoryNames::add);
        if (!categoryNames.isEmpty()) {
            throw new ResourceInUseException(
                    "Tax code cannot be deleted because it is mapped to the following Expense Categories: "
                            + String.join(", ", categoryNames)
                            + ". Deactivate it instead, or remap those categories first.");
        }

        taxAuditService.record(ENTITY, taxCodeId, "TAX_CODE_DELETED", snapshot(entity), null, AuditSource.ADMIN, null);
        taxCodeRepository.delete(entity);
    }

    // ------------------------------------------------------------------ components

    /**
     * The request's components, or the type's template when none are sent. Validated against the
     * type: VR-TAX-03 (rates, sum), VR-TAX-04 (CGST = SGST/UTGST).
     */
    private List<TaxCodeComponent> buildComponents(TaxCodeRequest request, TaxCode owner) {
        TaxType type = request.taxType();
        List<TaxCodeComponentRequest> requested = request.components() == null ? List.of() : request.components();
        List<TaxCodeComponent> components = requested.isEmpty()
                ? template(type, request.ratePercent(), owner)
                : requested.stream().map(c -> component(owner, c.componentCode(), c.label(), c.ratePercent(), c.glAccountId())).toList();

        Set<TaxComponentCode> seen = new HashSet<>();
        for (TaxCodeComponent c : components) {
            if (!seen.add(c.getComponentCode())) {
                throw new IllegalArgumentException("Component " + c.getComponentCode() + " appears more than once.");
            }
            if (!INDIA_COMPONENTS.contains(c.getComponentCode()) && "IN".equals(owner.getCountryCode())) {
                throw new IllegalArgumentException("Component " + c.getComponentCode() + " is not used for India GST.");
            }
        }
        BigDecimal total = components.stream().map(TaxCodeComponent::getRatePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("Component rates add up to " + plain(total) + "%, which is more than 100%.");
        }
        if (request.ratePercent() != null && request.ratePercent().compareTo(total) != 0) {
            throw new IllegalArgumentException("The rate (" + plain(request.ratePercent()) + "%) must equal the sum of its components ("
                    + plain(total) + "%).");
        }

        switch (type) {
            case EXEMPT -> {
                if (!components.isEmpty()) {
                    throw new IllegalArgumentException("An EXEMPT tax code has no components and a rate of 0.");
                }
            }
            case CGST_SGST -> {
                BigDecimal cgst = rateOf(components, TaxComponentCode.CGST);
                BigDecimal state = rateOf(components, TaxComponentCode.SGST) != null
                        ? rateOf(components, TaxComponentCode.SGST) : rateOf(components, TaxComponentCode.UTGST);
                boolean bothStateCodes = rateOf(components, TaxComponentCode.SGST) != null && rateOf(components, TaxComponentCode.UTGST) != null;
                if (components.size() != 2 || cgst == null || state == null || bothStateCodes) {
                    throw new IllegalArgumentException("A CGST_SGST tax code has exactly two components: CGST and SGST (or UTGST).");
                }
                if (cgst.compareTo(state) != 0) {
                    throw new IllegalArgumentException("CGST and SGST/UTGST rates must be equal (got " + plain(cgst) + "% and " + plain(state) + "%).");
                }
            }
            case IGST -> {
                if (components.size() != 1 || rateOf(components, TaxComponentCode.IGST) == null) {
                    throw new IllegalArgumentException("An IGST tax code has exactly one component: IGST.");
                }
            }
            case OTHER -> {
                if (components.isEmpty()) {
                    throw new IllegalArgumentException("Add at least one component for an OTHER tax code (e.g. CGST, SGST and CESS).");
                }
                BigDecimal cgst = rateOf(components, TaxComponentCode.CGST);
                BigDecimal sgst = rateOf(components, TaxComponentCode.SGST) != null
                        ? rateOf(components, TaxComponentCode.SGST) : rateOf(components, TaxComponentCode.UTGST);
                if ((cgst == null) != (sgst == null) || (cgst != null && cgst.compareTo(sgst) != 0)) {
                    throw new IllegalArgumentException("CGST must be paired with an equal SGST or UTGST component.");
                }
            }
            default -> {
                if (components.isEmpty()) {
                    throw new IllegalArgumentException("Add at least one component for a " + type + " tax code.");
                }
            }
        }
        for (int i = 0; i < components.size(); i++) {
            components.get(i).setSequence(i + 1);
        }
        return components;
    }

    private List<TaxCodeComponent> template(TaxType type, BigDecimal rate, TaxCode owner) {
        if (type == TaxType.EXEMPT) {
            if (rate != null && rate.signum() != 0) {
                throw new IllegalArgumentException("An EXEMPT tax code must have a rate of 0");
            }
            return new ArrayList<>();
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Enter the rate, or the components, of the tax code.");
        }
        return switch (type) {
            case CGST_SGST -> {
                BigDecimal half = rate.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP); // odd splits (0.25 -> 0.13 + 0.12) fail the CGST = SGST check below
                yield new ArrayList<>(List.of(
                        component(owner, TaxComponentCode.CGST, null, half, null),
                        component(owner, TaxComponentCode.SGST, null, rate.subtract(half), null)));
            }
            case IGST -> new ArrayList<>(List.of(component(owner, TaxComponentCode.IGST, null, rate, null)));
            case VAT -> new ArrayList<>(List.of(component(owner, TaxComponentCode.VAT, null, rate, null)));
            default -> throw new IllegalArgumentException("Add the components of the " + type + " tax code.");
        };
    }

    private TaxCodeComponent component(TaxCode owner, TaxComponentCode code, String label, BigDecimal rate, UUID glAccountId) {
        return TaxCodeComponent.builder()
                .taxCode(owner)
                .componentCode(code)
                .label(StringUtils.hasText(label) ? label.trim() : code.name() + " " + plain(rate) + "%")
                .ratePercent(rate.setScale(2, RoundingMode.HALF_UP))
                .glAccount(glAccountId == null ? null : resolveGlAccount(glAccountId))
                .build();
    }

    /** Replaces the code's components (in place, so ids of unchanged codes survive) and derives its rate. */
    private void applyComponentsAndRate(TaxCode entity, List<TaxCodeComponent> components) {
        Map<TaxComponentCode, TaxCodeComponent> existing = entity.getComponents().stream()
                .collect(Collectors.toMap(TaxCodeComponent::getComponentCode, c -> c, (a, b) -> a, LinkedHashMap::new));
        List<TaxCodeComponent> result = new ArrayList<>();
        for (TaxCodeComponent incoming : components) {
            TaxCodeComponent target = existing.remove(incoming.getComponentCode());
            if (target == null) {
                target = incoming;
            } else {
                target.setLabel(incoming.getLabel());
                target.setRatePercent(incoming.getRatePercent());
                target.setSequence(incoming.getSequence());
                target.setGlAccount(incoming.getGlAccount());
            }
            result.add(target);
        }
        entity.getComponents().clear();
        entity.getComponents().addAll(result);
        entity.setRatePercent(components.stream().map(TaxCodeComponent::getRatePercent)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
    }

    /** Locked code: only labels and GL accounts of the (unchanged) components may change. */
    private void relabelComponents(TaxCode entity, List<TaxCodeComponent> components) {
        Map<TaxComponentCode, TaxCodeComponent> incoming = components.stream()
                .collect(Collectors.toMap(TaxCodeComponent::getComponentCode, c -> c));
        for (TaxCodeComponent c : entity.getComponents()) {
            TaxCodeComponent update = incoming.get(c.getComponentCode());
            c.setLabel(update.getLabel());
            c.setGlAccount(update.getGlAccount());
        }
    }

    private static String componentSignature(List<TaxCodeComponent> components) {
        return components.stream()
                .map(c -> c.getComponentCode() + ":" + c.getRatePercent().stripTrailingZeros().toPlainString())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static BigDecimal rateOf(List<TaxCodeComponent> components, TaxComponentCode code) {
        return components.stream().filter(c -> c.getComponentCode() == code).map(TaxCodeComponent::getRatePercent).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ other fields

    /** VR-TAX-03a: 0-100, and 0 for EXEMPT. itcEligible is derived from it. */
    private void applyItc(TaxCode entity, TaxCodeRequest request, BigDecimal previous) {
        BigDecimal percent;
        if (request.itcRecoverablePercent() != null) {
            percent = request.itcRecoverablePercent();
        } else if (request.itcEligible() != null) {
            // Legacy flag only: keep an existing partial percentage when it still agrees with the flag.
            boolean previousEligible = previous != null && previous.signum() > 0;
            percent = request.itcEligible() == previousEligible && previous != null ? previous
                    : (request.itcEligible() ? HUNDRED : BigDecimal.ZERO);
        } else {
            percent = previous != null ? previous : BigDecimal.ZERO;
        }
        percent = percent.setScale(2, RoundingMode.HALF_UP);
        if (entity.getTaxType() == TaxType.EXEMPT && percent.signum() != 0) {
            throw new IllegalArgumentException("An EXEMPT tax code has no recoverable tax (ITC % must be 0).");
        }
        entity.setItcRecoverablePercent(percent);
        entity.setItcEligible(percent.signum() > 0);
    }

    private String resolveCountry(String requested, String previous) {
        String country = StringUtils.hasText(requested) ? requested.trim().toUpperCase() : (previous != null ? previous : "IN");
        if (!SUPPORTED_COUNTRIES.contains(country)) {
            throw new IllegalArgumentException("Only India (IN) tax codes are supported for now.");
        }
        return country;
    }

    private void assertTypeOffered(TaxType type) {
        if (!TYPES_OFFERED.contains(type)) {
            throw new IllegalArgumentException(type + " tax codes are not available yet - India GST (CGST_SGST, IGST, EXEMPT, OTHER) only.");
        }
    }

    private void assertDatesValid(TaxCode entity) {
        if (entity.getEffectiveTo() != null && entity.getEffectiveTo().isBefore(entity.getEffectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo cannot be before effectiveFrom");
        }
    }

    private void assertCodeNotDuplicate(String taxCode, UUID currentTaxCodeId) {
        taxCodeRepository.findByTaxCodeIgnoreCase(taxCode.trim()).ifPresent(existing -> {
            if (!existing.getTaxCodeId().equals(currentTaxCodeId)) {
                throw new DuplicateResourceException("Tax code already exists: " + taxCode);
            }
        });
    }

    private GlAccount resolveGlAccount(UUID glAccountId) {
        if (glAccountId == null) {
            return null;
        }
        GlAccount glAccount = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(glAccount.getStatus())) {
            throw new IllegalArgumentException(
                    "GL Account " + glAccount.getGlAccountCode() + " is not Active and cannot be used for input tax");
        }
        return glAccount;
    }

    private void defaultStatus(TaxCode entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
    }

    private TaxCodeResponse toResponse(TaxCode entity) {
        long mapped = mappingRepository.countCategoriesMappedOnOrAfter(entity.getTaxCodeId(), LocalDate.now());
        long usage = entity.getTaxCodeId() == null ? 0 : expenseLineItemRepository.countByTaxCodeId(entity.getTaxCodeId());
        return taxCodeMapper.toResponse(entity, mapped, usage);
    }

    private TaxCode findEntity(UUID taxCodeId) {
        return taxCodeRepository.findById(taxCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxCode not found with id: " + taxCodeId));
    }

    private static Map<String, Object> snapshot(TaxCode t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taxCode", t.getTaxCode());
        values.put("taxName", t.getTaxName());
        values.put("taxType", String.valueOf(t.getTaxType()));
        values.put("ratePercent", plain(t.getRatePercent()));
        values.put("components", componentSignature(t.getComponents()));
        values.put("itcRecoverablePercent", plain(t.getItcRecoverablePercent()));
        values.put("countryCode", t.getCountryCode());
        values.put("effectiveFrom", String.valueOf(t.getEffectiveFrom()));
        values.put("effectiveTo", String.valueOf(t.getEffectiveTo()));
        values.put("status", t.getStatus());
        values.put("inputTaxGlAccountId", t.getInputTaxGlAccount() == null ? null : String.valueOf(t.getInputTaxGlAccount().getGlAccountId()));
        return values;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : Objects.requireNonNull(value).stripTrailingZeros().toPlainString();
    }
}
