package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.mapper.TaxCodeMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private GlAccountRepository glAccountRepository;

    private TaxCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaxCodeServiceImpl(taxCodeRepository, expenseCategoryRepository, glAccountRepository, new TaxCodeMapper());
    }

    private static TaxCodeRequest request(String code, TaxType type, String rate, UUID glAccountId) {
        return new TaxCodeRequest(code, "GST " + rate + "%", type, new BigDecimal(rate), true, glAccountId,
                null, LocalDate.of(2017, 7, 1), null, null);
    }

    @Test
    void create_normalizesCode_defaultsActive_andLinksInputTaxGlAccount() {
        UUID glAccountId = UUID.randomUUID();
        GlAccount gl = GlAccount.builder().glAccountId(glAccountId).glAccountCode("1500").glAccountName("Input GST").status("ACTIVE").build();
        when(taxCodeRepository.findByTaxCodeIgnoreCase("gst18")).thenReturn(Optional.empty());
        when(glAccountRepository.findById(glAccountId)).thenReturn(Optional.of(gl));
        when(taxCodeRepository.save(any(TaxCode.class))).thenAnswer(inv -> inv.getArgument(0));

        TaxCodeResponse response = service.create(request(" gst18 ", TaxType.CGST_SGST, "18.00", glAccountId));

        assertThat(response.taxCode()).isEqualTo("GST18");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.itcEligible()).isTrue();
        assertThat(response.inputTaxGlAccountName()).isEqualTo("Input GST");
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
        assertThatThrownBy(() -> service.create(request("EXEMPT", TaxType.EXEMPT, "5.00", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXEMPT");
    }

    @Test
    void update_renamingCode_carriesMappedCategoriesAlong() {
        UUID taxCodeId = UUID.randomUUID();
        TaxCode existing = TaxCode.builder().taxCodeId(taxCodeId).taxCode("GST18").status("ACTIVE").build();
        ExpenseCategory travel = ExpenseCategory.builder().categoryName("Travel").taxCode("GST18").build();
        when(taxCodeRepository.findById(taxCodeId)).thenReturn(Optional.of(existing));
        when(taxCodeRepository.findByTaxCodeIgnoreCase("GST18-STD")).thenReturn(Optional.empty());
        when(taxCodeRepository.save(any(TaxCode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenseCategoryRepository.findByTaxCodeIgnoreCase("GST18")).thenReturn(List.of(travel));

        service.update(taxCodeId, request("GST18-STD", TaxType.CGST_SGST, "18.00", null));

        assertThat(travel.getTaxCode()).isEqualTo("GST18-STD");
        verify(expenseCategoryRepository).saveAll(List.of(travel));
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
