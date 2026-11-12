package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.dto.request.TaxCodeComponentRequest;
import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeComponentResponse;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.ExpenseCategory;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxCodeServiceImplTest {

    @Mock
    private TaxCodeRepository taxCodeRepository;
    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock
    private ExpenseCategoryTaxMappingRepository mappingRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private GlAccountRepository glAccountRepository;
    @Mock
    private TaxAuditService taxAuditService;

    private TaxCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaxCodeServiceImpl(taxCodeRepository, expenseCategoryRepository, mappingRepository,
                expenseLineItemRepository, glAccountRepository, new TaxCodeMapper(), taxAuditService);
        lenient().when(taxCodeRepository.save(any(TaxCode.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TaxCodeRequest request(String code, TaxType type, String rate, UUID glAccountId) {
        return request(code, type, rate, glAccountId, null, null, null, null);
    }

    private static TaxCodeRequest request(String code, TaxType type, String rate, UUID glAccountId, String status,
                                          BigDecimal itcPercent, List<TaxCodeComponentRequest> components, String reason) {
        return new TaxCodeRequest(code, "GST " + rate + "%", type, rate == null ? null : new BigDecimal(rate), true, glAccountId,
                null, LocalDate.of(2017, 7, 1), null, status, null, null, itcPercent, components, reason);
    }

    /** A saved GST18 code (CGST 9 + SGST 9), as V23 left it. */
    private static TaxCode gst18(UUID id, String status) {
        TaxCode code = TaxCode.builder().taxCodeId(id).taxCode("GST18").taxName("GST 18%").taxType(TaxType.CGST_SGST)
                .ratePercent(new BigDecimal("18.00")).itcEligible(false).itcRecoverablePercent(new BigDecimal("0.00"))
                .effectiveFrom(LocalDate.of(2017, 7, 1)).status(status).components(new ArrayList<>()).build();
        code.getComponents().add(TaxCodeComponent.builder().taxCode(code).componentCode(TaxComponentCode.CGST)
                .label("CGST 9%").ratePercent(new BigDecimal("9.00")).sequence(1).build());
        code.getComponents().add(TaxCodeComponent.builder().taxCode(code).componentCode(TaxComponentCode.SGST)
                .label("SGST 9%").ratePercent(new BigDecimal("9.00")).sequence(2).build());
        return code;
    }

    @Test
    void create_normalizesCode_defaultsActive_andLinksInputTaxGlAccount() {
        UUID glAccountId = UUID.randomUUID();
        GlAccount gl = GlAccount.builder().glAccountId(glAccountId).glAccountCode("1500").glAccountName("Input GST").status("ACTIVE").build();
        when(taxCodeRepository.findByTaxCodeIgnoreCase("gst18")).thenReturn(Optional.empty());
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.of(gl));

        TaxCodeResponse response = service.create(request(" gst18 ", TaxType.CGST_SGST, "18.00", glAccountId));

        assertThat(response.taxCode()).isEqualTo("GST18");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.countryCode()).isEqualTo("IN");
        assertThat(response.itcEligible()).isTrue();
        assertThat(response.itcRecoverablePercent()).isEqualByComparingTo("100");
        assertThat(response.inputTaxGlAccountName()).isEqualTo("Input GST");
        verify(taxAuditService).record(eq("TaxCode"), any(), eq("TAX_CODE_CREATED"), eq(null), any(), eq(AuditSource.ADMIN), eq(null));
    }

    @Test
    void create_cgstSgst_withoutComponents_generatesEqualHalves() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST5")).thenReturn(Optional.empty());

        TaxCodeResponse response = service.create(request("GST5", TaxType.CGST_SGST, "5.00", null));

        assertThat(response.components()).extracting(TaxCodeComponentResponse::componentCode).containsExactly("CGST", "SGST");
        assertThat(response.components()).extracting(TaxCodeComponentResponse::label).containsExactly("CGST 2.5%", "SGST 2.5%");
        assertThat(response.components()).allSatisfy(c -> assertThat(c.ratePercent()).isEqualByComparingTo("2.50"));
        assertThat(response.ratePercent()).isEqualByComparingTo("5.00");
        assertThat(response.locked()).isFalse();
    }

    @Test
    void create_withComponents_derivesRate_forGstPlusCess() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST28_CESS12")).thenReturn(Optional.empty());
        List<TaxCodeComponentRequest> components = List.of(
                new TaxCodeComponentRequest(TaxComponentCode.CGST, null, new BigDecimal("14"), null),
                new TaxCodeComponentRequest(TaxComponentCode.SGST, null, new BigDecimal("14"), null),
                new TaxCodeComponentRequest(TaxComponentCode.CESS, "Compensation cess 12%", new BigDecimal("12"), null));

        TaxCodeResponse response = service.create(request("GST28_CESS12", TaxType.OTHER, null, null, null, BigDecimal.ZERO, components, null));

        assertThat(response.ratePercent()).isEqualByComparingTo("40.00");
        assertThat(response.itcEligible()).isFalse();
        assertThat(response.components()).extracting(TaxCodeComponentResponse::sequence).containsExactly(1, 2, 3);
    }

    @Test
    void create_rejectsRateThatDiffersFromTheComponentSum() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(Optional.empty());
        List<TaxCodeComponentRequest> components = List.of(
                new TaxCodeComponentRequest(TaxComponentCode.CGST, null, new BigDecimal("9"), null),
                new TaxCodeComponentRequest(TaxComponentCode.SGST, null, new BigDecimal("9"), null));

        assertThatThrownBy(() -> service.create(request("GST18", TaxType.CGST_SGST, "12.00", null, null, null, components, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal the sum");
    }

    @Test
    void create_rejectsUnequalCgstAndSgst() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GSTX")).thenReturn(Optional.empty());
        List<TaxCodeComponentRequest> components = List.of(
                new TaxCodeComponentRequest(TaxComponentCode.CGST, null, new BigDecimal("9"), null),
                new TaxCodeComponentRequest(TaxComponentCode.SGST, null, new BigDecimal("6"), null));

        assertThatThrownBy(() -> service.create(request("GSTX", TaxType.CGST_SGST, null, null, null, null, components, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be equal");
    }

    @Test
    void create_rejectsVat_whileOnlyIndiaGstIsOffered() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("VAT20")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("VAT20", TaxType.VAT, "20.00", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available yet");
    }

    @Test
    void create_rejectsDuplicateCode() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18"))
                .thenReturn(Optional.of(TaxCode.builder().taxCodeId(UUID.randomUUID()).taxCode("GST18").build()));

        assertThatThrownBy(() -> service.create(request("GST18", TaxType.CGST_SGST, "18.00", null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(taxCodeRepository, never()).save(any());
    }

    @Test
    void create_rejectsExemptCodeWithNonZeroRate() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("EXEMPT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("EXEMPT", TaxType.EXEMPT, "5.00", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXEMPT");
    }

    @Test
    void create_rejectsItcOnAnExemptCode() {
        when(taxCodeRepository.findByTaxCodeIgnoreCase("EXEMPT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request("EXEMPT", TaxType.EXEMPT, "0", null, null, new BigDecimal("50"), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ITC");
    }

    @Test
    void update_renamingCode_carriesMappedCategoriesAlong() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = gst18(taxCodeId, "ACTIVE");
        ExpenseCategory travel = ExpenseCategory.builder().categoryName("Travel").taxCode("GST18").build();
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18-STD")).thenReturn(Optional.empty());
        when(expenseCategoryRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(List.of(travel));

        service.update(taxCodeId, request("GST18-STD", TaxType.CGST_SGST, "18.00", null));

        assertThat(travel.getTaxCode()).isEqualTo("GST18-STD");
        verify(expenseCategoryRepository).saveAll(List.of(travel));
    }

    @Test
    void update_withoutStatus_keepsADeactivatedCodeInactive() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = gst18(taxCodeId, "INACTIVE");
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(Optional.of(existing));

        service.update(taxCodeId, request("GST18", TaxType.CGST_SGST, "18.00", null));

        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void update_keepsComponentRowsInPlace_whenOnlyTheRateIsUnchanged() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = gst18(taxCodeId, "ACTIVE");
        TaxCodeComponent cgst = existing.getComponents().get(0);
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(Optional.of(existing));

        service.update(taxCodeId, request("GST18", TaxType.CGST_SGST, "18.00", null));

        assertThat(existing.getComponents()).hasSize(2).first().isSameAs(cgst);
    }

    @Test
    void update_deactivating_requiresAReason_andIsAuditedWithIt() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = gst18(taxCodeId, "ACTIVE");
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(taxCodeId, request("GST18", TaxType.CGST_SGST, "18.00", null, "INACTIVE", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        existing.setStatus("ACTIVE");
        service.update(taxCodeId, request("GST18", TaxType.CGST_SGST, "18.00", null, "INACTIVE", null, null, "Slab withdrawn"));

        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
        verify(taxAuditService).record(eq("TaxCode"), eq(taxCodeId), eq("TAX_CODE_DEACTIVATED"), any(), any(),
                eq(AuditSource.ADMIN), eq("Slab withdrawn"));
    }

    @Test
    void update_lockedCode_rejectsARateChange_butAllowsARename() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = gst18(taxCodeId, "ACTIVE");
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase(anyString())).thenReturn(Optional.of(existing));
        when(expenseLineItemRepository.countByTaxCodeId(taxCodeId)).thenReturn(3L);

        assertThatThrownBy(() -> service.update(taxCodeId, request("GST18", TaxType.CGST_SGST, "12.00", null, null, BigDecimal.ZERO, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked");

        TaxCodeResponse renamed = service.update(taxCodeId,
                new TaxCodeRequest("GST18", "GST 18% (standard)", TaxType.CGST_SGST, new BigDecimal("18.00"), false, null,
                        "Most services", LocalDate.of(2017, 7, 1), null, null, null, null, BigDecimal.ZERO, null, null));

        assertThat(renamed.taxName()).isEqualTo("GST 18% (standard)");
        assertThat(renamed.locked()).isTrue();
        assertThat(renamed.usageCount()).isEqualTo(3L);
    }

    @Test
    void delete_isBlocked_whileCategoriesStillUseTheCode() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = TaxCode.builder().taxCodeId(taxCodeId).taxCode("GST18").build();
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findByTaxCodeIgnoreCase("GST18"))
                .thenReturn(List.of(ExpenseCategory.builder().categoryName("Travel").build()));

        assertThatThrownBy(() -> service.delete(taxCodeId))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("Travel");
        verify(taxCodeRepository, never()).delete(any());
    }

    @Test
    void delete_isBlocked_onceExpenseLinesHaveUsedTheCode() {
        UUID taxCodeId = UUID.randomUUID();
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(TaxCode.builder().taxCodeId(taxCodeId).taxCode("GST18").build()));
        when(expenseLineItemRepository.countByTaxCodeId(taxCodeId)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(taxCodeId))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("Deactivate");
        verify(taxCodeRepository, never()).delete(any());
    }

    @Test
    void isApplicableOn_respectsStatusAndEffectiveWindow() {
        TaxCode code = TaxCode.builder().status("ACTIVE")
                .effectiveFrom(LocalDate.of(2026, 1, 1)).effectiveTo(LocalDate.of(2026, 12, 31)).build();

        assertThat(code.isApplicableOn(LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(code.isApplicableOn(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(code.isApplicableOn(LocalDate.of(2027, 1, 1))).isFalse();
        code.setStatus("INACTIVE");
        assertThat(code.isApplicableOn(LocalDate.of(2026, 6, 1))).isFalse();
    }
}
