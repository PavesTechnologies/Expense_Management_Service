package com.expense_management_service.integration.email;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the plain-text email bodies into HTML, so the tables show as real tables in mail clients. The plain text
 * stays as the fallback. A table is any run of lines that starts with "+" (a border) or "|" (a row), as the templates
 * draw them. Everything else is escaped and kept as text.
 */
public final class EmailHtml {

    private EmailHtml() {
    }

    public static String render(String plain) {
        StringBuilder html = new StringBuilder("<html><body style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#1f2937\">");
        List<String> block = new ArrayList<>();
        for (String line : plain.split("\n", -1)) {
            if (line.startsWith("+") || line.startsWith("|")) {
                block.add(line);
                continue;
            }
            flush(block, html);
            html.append(line.isEmpty() ? "<br/>" : "<div>" + escape(line) + "</div>");
        }
        flush(block, html);
        return html.append("</body></html>").toString();
    }

    private static void flush(List<String> block, StringBuilder html) {
        if (block.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : block) {
            if (line.startsWith("|")) {
                List<String> cells = new ArrayList<>();
                for (String cell : line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length()).split("\\|", -1)) {
                    cells.add(cell.trim());
                }
                rows.add(cells);
            }
        }
        if (!rows.isEmpty()) {
            html.append("<table style=\"border-collapse:collapse;margin:6px 0 10px 0\">");
            for (int i = 0; i < rows.size(); i++) {
                String tag = i == 0 ? "th" : "td";
                String style = i == 0
                        ? "background:#eef2ff;font-weight:bold;text-align:left;border:1px solid #c7d2fe;padding:6px 12px"
                        : "border:1px solid #e5e7eb;padding:6px 12px";
                html.append("<tr>");
                for (String cell : rows.get(i)) {
                    html.append("<").append(tag).append(" style=\"").append(style).append("\">")
                            .append(escape(cell)).append("</").append(tag).append(">");
                }
                html.append("</tr>");
            }
            html.append("</table>");
        }
        block.clear();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
