package com.expense_management_service.common;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses and evaluates an {@code ApprovalFlow.criteriaPattern} boolean expression, e.g.
 * {@code "(1 AND 2) OR 3"}, where each integer is an {@code ApprovalFlowCriterion.index}.
 * <p>
 * Grammar: {@code expr := term (OR term)*}, {@code term := factor (AND factor)*},
 * {@code factor := NUMBER | '(' expr ')'}. Used both to validate a pattern at config-save time
 * (every referenced index must exist among the flow's criteria) and to evaluate it at
 * flow-resolution time (given whether each criterion actually matched a report).
 */
public final class CriteriaPatternEvaluator {

    private CriteriaPatternEvaluator() {
    }

    /** Every integer index referenced by the pattern, for config-time validation against the flow's actual criteria. */
    public static Set<Integer> extractIndices(String pattern) {
        Set<Integer> indices = new HashSet<>();
        new Parser(pattern).parseExpr(indices, null);
        return indices;
    }

    /**
     * Evaluates the pattern given whether each referenced index matched. Throws
     * {@code IllegalArgumentException} if the pattern references an index missing from {@code values}
     * - a config-time validation bug if this is ever reached at resolution time, not a user input error.
     */
    public static boolean evaluate(String pattern, Map<Integer, Boolean> values) {
        Set<Integer> ignored = new HashSet<>();
        Parser parser = new Parser(pattern);
        boolean result = parser.parseExpr(ignored, values);
        parser.assertFullyConsumed();
        return result;
    }

    /** Throws {@code IllegalArgumentException} on any syntax error or an index not present in {@code knownIndices}. */
    public static void assertValid(String pattern, Set<Integer> knownIndices) {
        Set<Integer> referenced = extractIndices(pattern);
        for (Integer index : referenced) {
            if (!knownIndices.contains(index)) {
                throw new IllegalArgumentException(
                        "criteriaPattern references index " + index + " which is not defined in this flow's criteria");
            }
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
            this.pos = 0;
        }

        boolean parseExpr(Set<Integer> collectedIndices, Map<Integer, Boolean> values) {
            boolean result = parseTerm(collectedIndices, values);
            skipWhitespace();
            while (matchKeyword("OR")) {
                boolean rhs = parseTerm(collectedIndices, values);
                result = result || rhs;
                skipWhitespace();
            }
            return result;
        }

        boolean parseTerm(Set<Integer> collectedIndices, Map<Integer, Boolean> values) {
            boolean result = parseFactor(collectedIndices, values);
            skipWhitespace();
            while (matchKeyword("AND")) {
                boolean rhs = parseFactor(collectedIndices, values);
                result = result && rhs;
                skipWhitespace();
            }
            return result;
        }

        boolean parseFactor(Set<Integer> collectedIndices, Map<Integer, Boolean> values) {
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '(') {
                pos++;
                boolean result = parseExpr(collectedIndices, values);
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Malformed criteriaPattern: expected ')' at position " + pos + " in \"" + text + "\"");
                }
                pos++;
                return result;
            }
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Malformed criteriaPattern: expected a number or '(' at position " + pos + " in \"" + text + "\"");
            }
            int index = Integer.parseInt(text.substring(start, pos));
            collectedIndices.add(index);
            if (values == null) {
                return false;
            }
            Boolean value = values.get(index);
            if (value == null) {
                throw new IllegalArgumentException("criteriaPattern references index " + index + " with no evaluated value");
            }
            return value;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean matchKeyword(String keyword) {
            skipWhitespace();
            if (text.regionMatches(true, pos, keyword, 0, keyword.length())) {
                int after = pos + keyword.length();
                boolean boundaryOk = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
                if (boundaryOk) {
                    pos = after;
                    return true;
                }
            }
            return false;
        }

        void assertFullyConsumed() {
            skipWhitespace();
            if (pos != text.length()) {
                throw new IllegalArgumentException("Malformed criteriaPattern: unexpected trailing content at position " + pos + " in \"" + text + "\"");
            }
        }
    }
}
