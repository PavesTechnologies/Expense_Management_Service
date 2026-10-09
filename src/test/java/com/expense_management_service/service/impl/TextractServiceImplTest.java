package com.expense_management_service.service.impl;

import com.expense_management_service.config.S3Properties;
import com.expense_management_service.service.TextractIntegrationException;
import com.expense_management_service.service.TextractNotApplicableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.BadDocumentException;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.DocumentTooLargeException;
import software.amazon.awssdk.services.textract.model.ThrottlingException;
import software.amazon.awssdk.services.textract.model.UnsupportedDocumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TextractServiceImplTest {

    @Mock
    private TextractClient textractClient;

    private TextractServiceImpl textractService;

    @BeforeEach
    void setUp() {
        textractService = new TextractServiceImpl(textractClient, new S3Properties("xms-receipts-bucket"));
    }

    @Test
    void analyzeExpense_returnsRawTextractResponse() {
        AnalyzeExpenseResponse expected = AnalyzeExpenseResponse.builder().build();
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenReturn(expected);

        AnalyzeExpenseResponse result = textractService.analyzeExpense("key");

        assertThat(result).isSameAs(expected);
    }

    /**
     * Issue 7/8 fix: the document must be referenced by its S3 location (bucket + key) rather
     * than downloaded and sent as raw bytes — this is what removes the 5MB inline-payload limit
     * and is AWS's documented approach for larger documents.
     */
    @Test
    void analyzeExpense_referencesS3ObjectRatherThanSendingRawBytes() {
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenReturn(AnalyzeExpenseResponse.builder().build());
        ArgumentCaptor<AnalyzeExpenseRequest> captor = ArgumentCaptor.forClass(AnalyzeExpenseRequest.class);

        textractService.analyzeExpense("receipts/emp-1/report-1/file.pdf");

        org.mockito.Mockito.verify(textractClient).analyzeExpense(captor.capture());
        Document document = captor.getValue().document();
        assertThat(document.bytes()).isNull();
        assertThat(document.s3Object().bucket()).isEqualTo("xms-receipts-bucket");
        assertThat(document.s3Object().name()).isEqualTo("receipts/emp-1/report-1/file.pdf");
    }

    @Test
    void analyzeDocument_returnsRawTextractResponse_andReferencesS3Object() {
        AnalyzeDocumentResponse expected = AnalyzeDocumentResponse.builder().build();
        ArgumentCaptor<AnalyzeDocumentRequest> captor = ArgumentCaptor.forClass(AnalyzeDocumentRequest.class);
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenReturn(expected);

        AnalyzeDocumentResponse result = textractService.analyzeDocument("key");

        assertThat(result).isSameAs(expected);
        org.mockito.Mockito.verify(textractClient).analyzeDocument(captor.capture());
        assertThat(captor.getValue().document().s3Object().name()).isEqualTo("key");
    }

    @Test
    void detectDocumentText_returnsRawTextractResponse_andReferencesS3Object() {
        DetectDocumentTextResponse expected = DetectDocumentTextResponse.builder().build();
        ArgumentCaptor<DetectDocumentTextRequest> captor = ArgumentCaptor.forClass(DetectDocumentTextRequest.class);
        when(textractClient.detectDocumentText(any(DetectDocumentTextRequest.class))).thenReturn(expected);

        DetectDocumentTextResponse result = textractService.detectDocumentText("key");

        assertThat(result).isSameAs(expected);
        org.mockito.Mockito.verify(textractClient).detectDocumentText(captor.capture());
        assertThat(captor.getValue().document().s3Object().name()).isEqualTo("key");
    }

    @Test
    void analyzeExpense_throwsTextractIntegrationException_whenTextractFails() {
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class)))
                .thenThrow(SdkException.create("boom", null));

        assertThatThrownBy(() -> textractService.analyzeExpense("key"))
                .isInstanceOf(TextractIntegrationException.class);
    }

    /**
     * Issue 9 (Strategy Pattern) depends on this: UnsupportedDocumentException specifically must
     * surface as {@link TextractNotApplicableException} (a subtype), not the generic exception —
     * that is the signal the OCR document-type strategy chain uses to fall through to the next
     * strategy instead of failing outright.
     */
    @Test
    void analyzeExpense_throwsNotApplicableSubtype_whenDocumentUnsupported() {
        UnsupportedDocumentException awsException = (UnsupportedDocumentException) UnsupportedDocumentException.builder()
                .message("Request has unsupported document format")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("UnsupportedDocumentException")
                        .errorMessage("Request has unsupported document format")
                        .build())
                .build();
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenThrow(awsException);

        assertThatThrownBy(() -> textractService.analyzeExpense("key"))
                .isInstanceOf(TextractNotApplicableException.class)
                .isInstanceOf(TextractIntegrationException.class)
                .hasMessageContaining("UnsupportedDocumentException")
                .hasMessageContaining("Request has unsupported document format")
                .hasCause(awsException);
    }

    @Test
    void analyzeExpense_throwsCategorizedException_whenDocumentCorruptOrEncrypted() {
        BadDocumentException awsException = (BadDocumentException) BadDocumentException.builder()
                .message("Document is corrupted")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("BadDocumentException")
                        .errorMessage("Document is corrupted")
                        .build())
                .build();
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenThrow(awsException);

        assertThatThrownBy(() -> textractService.analyzeExpense("key"))
                .isInstanceOf(TextractIntegrationException.class)
                .isNotInstanceOf(TextractNotApplicableException.class)
                .hasMessageContaining("Invalid or corrupted document")
                .hasMessageContaining("BadDocumentException");
    }

    @Test
    void analyzeExpense_throwsCategorizedException_whenDocumentTooLarge() {
        DocumentTooLargeException awsException = (DocumentTooLargeException) DocumentTooLargeException.builder()
                .message("Document is too large")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("DocumentTooLargeException")
                        .errorMessage("Document is too large")
                        .build())
                .build();
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenThrow(awsException);

        assertThatThrownBy(() -> textractService.analyzeExpense("key"))
                .isInstanceOf(TextractIntegrationException.class)
                .hasMessageContaining("exceeds AWS Textract's size or page limits");
    }

    @Test
    void analyzeExpense_throwsCategorizedException_whenThrottled() {
        ThrottlingException awsException = (ThrottlingException) ThrottlingException.builder()
                .message("Rate exceeded")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("ThrottlingException")
                        .errorMessage("Rate exceeded")
                        .build())
                .build();
        when(textractClient.analyzeExpense(any(AnalyzeExpenseRequest.class))).thenThrow(awsException);

        assertThatThrownBy(() -> textractService.analyzeExpense("key"))
                .isInstanceOf(TextractIntegrationException.class)
                .hasMessageContaining("temporarily rate-limited");
    }

    @Test
    void analyzeDocument_throwsNotApplicableSubtype_whenDocumentUnsupported() {
        UnsupportedDocumentException awsException = (UnsupportedDocumentException) UnsupportedDocumentException.builder()
                .message("unsupported")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnsupportedDocumentException").errorMessage("unsupported").build())
                .build();
        when(textractClient.analyzeDocument(any(AnalyzeDocumentRequest.class))).thenThrow(awsException);

        assertThatThrownBy(() -> textractService.analyzeDocument("key"))
                .isInstanceOf(TextractNotApplicableException.class);
    }
}
