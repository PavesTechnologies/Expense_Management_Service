package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.LineTaxResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.TaxValidationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseLineItemMapperTaxTest {

    private final ExpenseLineItemMapper mapper = new ExpenseLineItemMapper();

    @Test
    void lineTax_splitsRecoverableFromNonRecoverable_andCarriesItcStatusAndGstin() {
        ExpenseLineItem line = ExpenseLineItem.builder()
                .taxAmount(new BigDecimal("18.00")).recoverableTaxAmount(new BigDecimal("9.00"))
                .baseTaxAmount(new BigDecimal("1498.50")).baseRecoverableTaxAmount(new BigDecimal("749.25"))
                .itcRecoverablePercent(new BigDecimal("50.00"))
                .taxValidationStatus(TaxValidationStatus.FINANCE_VERIFIED).supplierGstin("29ABCDE1234F1Z5").build();

        LineTaxResponse tax = mapper.toTaxResponse(line);

        assertThat(tax.nonRecoverableTaxAmount()).isEqualByComparingTo("9.00");
        assertThat(tax.baseNonRecoverableTaxAmount()).isEqualByComparingTo("749.25");
        assertThat(tax.itcStatus()).isEqualTo("VERIFIED");
        assertThat(tax.supplierGstin()).isEqualTo("29ABCDE1234F1Z5");
    }

    @Test
    void lineTax_withoutRecoverableTax_isAllNonRecoverable_andNotRecoverable() {
        ExpenseLineItem line = ExpenseLineItem.builder()
                .taxAmount(new BigDecimal("18.00")).baseTaxAmount(new BigDecimal("18.00"))
                .taxValidationStatus(TaxValidationStatus.LEGACY).build();

        LineTaxResponse tax = mapper.toTaxResponse(line);

        assertThat(tax.nonRecoverableTaxAmount()).isEqualByComparingTo("18.00");
        assertThat(tax.itcStatus()).isEqualTo("NOT_RECOVERABLE");
        assertThat(tax.supplierGstin()).isNull();
    }
}
