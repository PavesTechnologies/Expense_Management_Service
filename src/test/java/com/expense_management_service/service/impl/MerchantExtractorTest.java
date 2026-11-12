package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.util.List;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.lineBlock;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class MerchantExtractorTest {

    private final MerchantExtractor extractor = new MerchantExtractor();

    /**
     * The exact reported bug: Textract returns two VENDOR_NAME fields on the same receipt — the
     * brand/logo line and the legal registration line. The old single-value index kept only
     * whichever came first; this must always prefer the brand name.
     */
    @Test
    void extract_prefersBrandName_overLegalEntityName_whenBothAreVendorNameFields() {
        ExpenseFieldIndex index = indexOf(
                typedField("VENDOR_NAME", "UDANE SONS ENTERPRISES LLP", 90.0f, null),
                typedField("VENDOR_NAME", "German Bakery", 85.0f, null)
        );

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("German Bakery");
    }

    @Test
    void extract_stripsGstinPhoneAndAddressLines_fromMultiLineVendorNameField() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME",
                "German Bakery\nGSTIN: 27ABCDE1234F1Z5\nPh: 9876543210\n12 MG Road, Sector 5", 90.0f, null));

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("German Bakery");
    }

    @Test
    void extract_stripsTrailingLegalSuffix_whenOnlyLegalNamePresentButStillYieldsNonBlankResidual() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Sunrise Traders Pvt Ltd", 90.0f, null));

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("Sunrise Traders");
    }

    @Test
    void extract_neverConcatenatesCandidates_evenWithThreeVendorNameFields() {
        ExpenseFieldIndex index = indexOf(
                typedField("VENDOR_NAME", "UDANE SONS ENTERPRISES LLP", 90.0f, null),
                typedField("VENDOR_NAME", "German Bakery", 85.0f, null),
                typedField("VENDOR_NAME", "GB Foods Pvt Ltd", 80.0f, null)
        );

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).doesNotContain("+").doesNotContain(",").isEqualTo("German Bakery");
    }

    @Test
    void extract_prefersShorterBrandCandidate_whenMultipleNonLegalCandidatesExist() {
        ExpenseFieldIndex index = indexOf(
                typedField("VENDOR_NAME", "German Bakery And Confectionery", 85.0f, null),
                typedField("VENDOR_NAME", "German Bakery", 85.0f, null)
        );

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("German Bakery");
    }

    @Test
    void extract_fallsBackToLabelScan_whenNoVendorNameTypePresent() {
        ExpenseFieldIndex index = indexOf(labeledField("Restaurant Name", "Pizza Hut", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("Pizza Hut");
    }

    /** Task 13: raw OCR fallback when Textract found no structured or label-matched name field at all. */
    @Test
    void extract_fallsBackToTopmostOcrLine_whenNoStructuredOrLabeledCandidateExists() {
        ExpenseFieldIndex index = indexOf(List.<ExpenseField>of(), List.of(
                lineBlock("German Bakery", 0.05f),
                lineBlock("123 MG Road", 0.10f),
                lineBlock("GSTIN: 27ABCDE1234F1Z5", 0.12f)
        ));

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("German Bakery");
    }

    @Test
    void extract_ocrFallback_skipsAddressAndGstinLines_toFindTheActualTopmostUsableLine() {
        ExpenseFieldIndex index = indexOf(List.<ExpenseField>of(), List.of(
                lineBlock("GSTIN: 27ABCDE1234F1Z5", 0.02f), // physically above the brand line but noise
                lineBlock("German Bakery", 0.05f),
                lineBlock("123 MG Road, Sector 9", 0.10f)
        ));

        ExtractionResult<String> result = extractor.extract(index);

        assertThat(result.value()).isEqualTo("German Bakery");
    }

    @Test
    void extract_returnsEmpty_whenNothingFoundAnywhere() {
        ExpenseFieldIndex index = indexOf(List.of(), List.of());

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
