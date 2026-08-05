package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.ParsedReceiptData;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.EntityType;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 9 (travel documents via AnalyzeDocument): {@code AnalyzeDocument} returns a graph of
 * {@link Block}s rather than Textract's expense-specific field structure — these tests build
 * that graph by hand (KEY_VALUE_SET blocks linked to WORD blocks via relationships), the same
 * shape Textract itself returns for a bus/flight/train ticket's labeled fields (PNR, fare,
 * departure date/time, operator name).
 */
class TravelDocumentResponseParserTest {

    private final TravelDocumentResponseParser parser = new TravelDocumentResponseParser();

    @Test
    void parse_extractsOperatorTicketNumberDateAndFare_fromKeyValueBlocks() {
        FormFieldGraphBuilder graph = new FormFieldGraphBuilder()
                .field("Operator", "RedBus Travels")
                .field("PNR", "RB123456")
                .field("Journey Date", "05/06/2026")
                .field("Departure Time", "21:09:14")
                .field("Total Fare", "850.00")
                .field("Payment Method", "UPI");

        ParsedReceiptData result = parser.parse(graph.build());

        assertThat(result.merchantName()).isEqualTo("RedBus Travels");
        assertThat(result.invoiceNumber()).isEqualTo("RB123456");
        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(result.totalAmount()).isEqualByComparingTo("850.00");
        assertThat(result.paymentMethod()).isEqualTo("UPI");
    }

    @Test
    void parse_detectsCurrency_fromFareFieldText() {
        FormFieldGraphBuilder graph = new FormFieldGraphBuilder().field("Fare", "INR 550.00");

        ParsedReceiptData result = parser.parse(graph.build());

        assertThat(result.currencyCode()).isEqualTo("INR");
    }

    @Test
    void parse_returnsAllNull_whenNoFormFieldsDetected() {
        AnalyzeDocumentResponse response = AnalyzeDocumentResponse.builder().blocks(List.of()).build();

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isNull();
        assertThat(result.totalAmount()).isNull();
        assertThat(result.confidenceScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void extractFormFields_flattensKeyValueBlocks_intoLabelToTextMap() {
        FormFieldGraphBuilder graph = new FormFieldGraphBuilder().field("Airline", "IndiGo");

        Map<String, String> fields = parser.extractFormFields(graph.build());

        assertThat(fields).containsEntry("Airline", "IndiGo");
    }

    /** Builds a minimal, valid Textract KEY_VALUE_SET block graph: one KEY block + one VALUE block per field, each with WORD children. */
    private static class FormFieldGraphBuilder {
        private final List<Block> blocks = new ArrayList<>();
        private int idCounter = 0;

        FormFieldGraphBuilder field(String label, String value) {
            String keyId = nextId();
            String keyWordId = nextId();
            String valueId = nextId();
            String valueWordId = nextId();

            blocks.add(wordBlock(keyWordId, label));
            blocks.add(wordBlock(valueWordId, value));

            blocks.add(Block.builder()
                    .id(keyId)
                    .blockType(BlockType.KEY_VALUE_SET)
                    .entityTypes(EntityType.KEY)
                    .relationships(
                            Relationship.builder().type(RelationshipType.CHILD).ids(List.of(keyWordId)).build(),
                            Relationship.builder().type(RelationshipType.VALUE).ids(List.of(valueId)).build())
                    .build());

            blocks.add(Block.builder()
                    .id(valueId)
                    .blockType(BlockType.KEY_VALUE_SET)
                    .entityTypes(EntityType.VALUE)
                    .relationships(Relationship.builder().type(RelationshipType.CHILD).ids(List.of(valueWordId)).build())
                    .build());

            return this;
        }

        private Block wordBlock(String id, String text) {
            return Block.builder().id(id).blockType(BlockType.WORD).text(text).build();
        }

        private String nextId() {
            return "block-" + (idCounter++);
        }

        AnalyzeDocumentResponse build() {
            return AnalyzeDocumentResponse.builder().blocks(blocks).build();
        }
    }
}
