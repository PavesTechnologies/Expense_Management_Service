package com.expense_management_service.service;

/**
 * Thrown when Textract itself rejects a document as the wrong shape for the API that was
 * called — e.g. {@code AnalyzeExpense} against a document with no expense-shaped structure.
 * A subtype of {@link TextractIntegrationException} so every existing catch of the parent type
 * keeps working unchanged; the OCR document-type strategy chain uses this specific subtype to
 * tell "this strategy doesn't apply, try the next one" apart from a genuine failure (throttling,
 * a corrupted file, a network error) that should stop the chain and surface as a real error
 * instead of being silently swallowed.
 */
public class TextractNotApplicableException extends TextractIntegrationException {

    public TextractNotApplicableException(String message, Throwable cause) {
        super(message, cause);
    }
}
