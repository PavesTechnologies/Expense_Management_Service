package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.BoundingBox;
import software.amazon.awssdk.services.textract.model.ExpenseCurrency;
import software.amazon.awssdk.services.textract.model.ExpenseDetection;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;
import software.amazon.awssdk.services.textract.model.ExpenseField;
import software.amazon.awssdk.services.textract.model.ExpenseType;
import software.amazon.awssdk.services.textract.model.Geometry;

import java.util.List;

/** Shared {@link ExpenseFieldIndex}/fixture builders for every extractor's unit tests — kept in one place so each test class stays focused on its own extractor's behavior. */
final class TestExpenseFields {

    private TestExpenseFields() {
    }

    static ExpenseFieldIndex indexOf(ExpenseField... fields) {
        return ExpenseFieldIndex.from(ExpenseDocument.builder().summaryFields(List.of(fields)).build());
    }

    static ExpenseFieldIndex indexOf(List<ExpenseField> fields, List<Block> blocks) {
        return ExpenseFieldIndex.from(ExpenseDocument.builder().summaryFields(fields).blocks(blocks).build());
    }

    static ExpenseField typedField(String type, String text, float confidence, String currencyCode) {
        ExpenseField.Builder builder = ExpenseField.builder()
                .type(ExpenseType.builder().text(type).build())
                .valueDetection(ExpenseDetection.builder().text(text).confidence(confidence).build());
        if (currencyCode != null) {
            builder.currency(ExpenseCurrency.builder().code(currencyCode).build());
        }
        return builder.build();
    }

    /** Simulates an OTHER-type Textract field recognized only by its label (e.g. CGST/SGST/payment method — none of which have a standard ExpenseType). */
    static ExpenseField labeledField(String label, String text, float confidence) {
        return ExpenseField.builder()
                .type(ExpenseType.builder().text("OTHER").build())
                .labelDetection(ExpenseDetection.builder().text(label).confidence(confidence).build())
                .valueDetection(ExpenseDetection.builder().text(text).confidence(confidence).build())
                .build();
    }

    /** Raw OCR LINE block — page defaults to 1, top is the vertical position used for "topmost line" selection. */
    static Block lineBlock(String text, float top) {
        return Block.builder()
                .blockType(BlockType.LINE)
                .text(text)
                .page(1)
                .geometry(Geometry.builder().boundingBox(BoundingBox.builder().top(top).build()).build())
                .build();
    }
}
