package com.expense_management_service.service.impl;

import com.expense_management_service.config.S3Properties;
import com.expense_management_service.service.TextractIntegrationException;
import com.expense_management_service.service.TextractNotApplicableException;
import com.expense_management_service.service.TextractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseRequest;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.BadDocumentException;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.DocumentTooLargeException;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.InvalidS3ObjectException;
import software.amazon.awssdk.services.textract.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.TextractException;
import software.amazon.awssdk.services.textract.model.ThrottlingException;
import software.amazon.awssdk.services.textract.model.UnsupportedDocumentException;

import java.util.function.Function;

/**
 * {@link TextractService} implementation backed by AWS Textract (AWS SDK v2).
 * <p>
 * Every operation references the receipt by its S3 location ({@code bucket}/{@code objectKey})
 * rather than downloading it and sending raw bytes inline. This is a deliberate, narrow
 * exception to the rule that AWS-facing code goes through {@code StorageService} rather than
 * touching S3 directly: Textract reads the object itself, server-side, using XMS's own IAM
 * permissions on the bucket — there is no byte stream for this class to hand to
 * {@code StorageService} in the first place. Referencing by S3 location also avoids the 5MB
 * inline-payload limit inline {@code Document.Bytes} is subject to, and is AWS's documented
 * approach for larger or multi-page documents (see EP03-S6/S7 PDF investigation).
 * <p>
 * Does not parse the response, persist anything, or update OCR status — that belongs to
 * {@code TextractResponseParser} and {@code OCRService} respectively. Every AWS-side rejection
 * is logged with its real error code/message before being translated into a categorized,
 * human-readable {@link TextractIntegrationException} — never collapsed into one generic string.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TextractServiceImpl implements TextractService {

    private final TextractClient textractClient;
    private final S3Properties s3Properties;

    @Override
    public AnalyzeExpenseResponse analyzeExpense(String objectKey) {
        return execute(objectKey, document ->
                textractClient.analyzeExpense(AnalyzeExpenseRequest.builder().document(document).build()));
    }

    @Override
    public AnalyzeDocumentResponse analyzeDocument(String objectKey) {
        return execute(objectKey, document -> textractClient.analyzeDocument(AnalyzeDocumentRequest.builder()
                .document(document)
                .featureTypes(FeatureType.FORMS, FeatureType.TABLES)
                .build()));
    }

    @Override
    public DetectDocumentTextResponse detectDocumentText(String objectKey) {
        return execute(objectKey, document ->
                textractClient.detectDocumentText(DetectDocumentTextRequest.builder().document(document).build()));
    }

    private Document s3Document(String objectKey) {
        return Document.builder()
                .s3Object(S3Object.builder().bucket(s3Properties.bucketName()).name(objectKey).build())
                .build();
    }

    private <T> T execute(String objectKey, Function<Document, T> textractCall) {
        try {
            return textractCall.apply(s3Document(objectKey));
        } catch (UnsupportedDocumentException e) {
            logTextractException(e);
            // A TextractNotApplicableException, not a generic one: this specific rejection means
            // "this API doesn't apply to this document" (e.g. AnalyzeExpense against a document
            // with no expense-shaped structure), which is exactly the signal the OCR document-type
            // strategy chain uses to fall through to the next strategy instead of failing outright.
            throw new TextractNotApplicableException(describeFailure(
                    "This Textract operation does not apply to this document — its structure or format "
                            + "is not one this operation supports", e), e);
        } catch (BadDocumentException e) {
            logTextractException(e);
            throw new TextractIntegrationException(describeFailure(
                    "Invalid or corrupted document — the file may be encrypted, password-protected, or damaged", e), e);
        } catch (DocumentTooLargeException e) {
            logTextractException(e);
            throw new TextractIntegrationException(describeFailure(
                    "Document exceeds AWS Textract's size or page limits", e), e);
        } catch (InvalidS3ObjectException e) {
            logTextractException(e);
            throw new TextractIntegrationException(describeFailure(
                    "AWS Textract could not read the document from storage", e), e);
        } catch (ProvisionedThroughputExceededException | ThrottlingException e) {
            logTextractException(e);
            throw new TextractIntegrationException(describeFailure(
                    "AWS Textract is temporarily rate-limited — please retry shortly", e), e);
        } catch (TextractException e) {
            // Any other Textract-specific rejection not distinguished above (e.g.
            // InvalidParameterException, AccessDeniedException, InternalServerError) — still
            // logged and categorized with its real error code, never masked.
            logTextractException(e);
            throw new TextractIntegrationException(describeFailure("AWS Textract rejected the request", e), e);
        } catch (SdkException e) {
            // Non-service-side failures: network timeout, credential/signing errors, etc. — no
            // awsErrorDetails() to log here, just the SDK exception itself, in full.
            log.error("[OCR] AWS SDK error calling Textract (network/client-side, not a service rejection)", e);
            throw new TextractIntegrationException("AWS Textract call failed (network or client error): " + e.getMessage(), e);
        }
    }

    private void logTextractException(TextractException e) {
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getClass().getSimpleName();
        String errorMessage = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
        log.error("[OCR] Textract Error Code : {}", errorCode);
        log.error("[OCR] Textract Error Message : {}", errorMessage);
        log.error("[OCR] Textract status code : {}", e.statusCode());
        log.error("[OCR] Complete Textract exception", e);
    }

    /** Human-readable category, with the real AWS error code/message preserved alongside it — never just one or the other. */
    private String describeFailure(String humanReadableCategory, TextractException e) {
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getClass().getSimpleName();
        String errorMessage = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
        return humanReadableCategory + " [" + errorCode + ": " + errorMessage + "]";
    }
}
