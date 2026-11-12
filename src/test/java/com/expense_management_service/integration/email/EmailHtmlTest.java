package com.expense_management_service.integration.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailHtmlTest {

    @Test
    void asciiTable_becomesARealHtmlTable_withHeaderRow() {
        String plain = String.join("\n",
                "EMPLOYEE DETAILS",
                "+---------------+---------------+",
                "| Field         | Value         |",
                "+---------------+---------------+",
                "| Employee Name | Niharika      |",
                "+---------------+---------------+",
                "",
                "Regards");

        String html = EmailHtml.render(plain);

        assertThat(html).contains("<table")
                .contains("<th style=")
                .contains(">Field</th>")
                .contains(">Employee Name</td>")
                .contains(">Niharika</td>")
                .doesNotContain("+---")
                .doesNotContain("| Field");
        assertThat(html).contains("<div>EMPLOYEE DETAILS</div>").contains("<div>Regards</div>");
    }

    @Test
    void plainText_isEscaped_andKeptAsText() {
        String html = EmailHtml.render("- Amount: INR 1,000.00 <b>bold</b> & more");

        assertThat(html).contains("- Amount: INR 1,000.00 &lt;b&gt;bold&lt;/b&gt; &amp; more")
                .doesNotContain("<table");
    }
}
