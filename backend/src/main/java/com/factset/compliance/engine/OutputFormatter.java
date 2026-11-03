package com.factset.compliance.engine;

import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Transaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Report renderer producing spec-compliant output for compliance evaluations.
 *
 * <p>Produces tab-delimited output matching the exact layout mandated by {@code candidate-brief.md}:
 * <pre>
 * Transaction\tBreached Rules
 * TXN001\tR001 (HIGH), R003 (MEDIUM)
 * TXN003\tnone
 * </pre>
 *
 * <p>Breached rules for each transaction are sorted in ascending {@code rule_id} order
 * (or optionally by regulatory severity if configured).
 */
@Component
public class OutputFormatter {

    private static final Comparator<Rule> RULE_ID_ORDER = Comparator.comparing(Rule::ruleId);
    private static final Comparator<Rule> SEVERITY_ORDER = Comparator.comparing(Rule::severity)
            .reversed()
            .thenComparing(Rule::ruleId);

    private final String separator;
    private final Comparator<Rule> ruleComparator;

    /**
     * Constructs a formatter with configurable column delimiter and rule sorting strategy.
     *
     * @param separator Column delimiter to use between columns (defaults to {@code \t}).
     * @param sort      Sorting strategy for breached rules; {@code "rule_id"} (ascending) or {@code "severity"}.
     */
    @Autowired
    public OutputFormatter(@Value("${app.output.separator:\t}") String separator,
                           @Value("${app.output.sort:rule_id}") String sort) {
        this.separator = separator;
        this.ruleComparator = "severity".equalsIgnoreCase(sort) ? SEVERITY_ORDER : RULE_ID_ORDER;
    }

    /**
     * Constructs a formatter with the specified column separator and default ascending {@code rule_id} ordering.
     *
     * @param separator Column delimiter to use between columns.
     */
    public OutputFormatter(String separator) {
        this(separator, "rule_id");
    }

    /**
     * Formats evaluated transactions into the standard compliance report string.
     *
     * @param transactions Ordered list of transactions to include in the report.
     * @param rules        List of compliance rules to evaluate against each transaction.
     * @param engine       The rule engine instance used to compute rule breaches.
     * @return Formatted multi-line report string with headers, newline-terminated.
     */
    public String format(List<Transaction> transactions, List<Rule> rules, RuleEngine engine) {
        StringBuilder sb = new StringBuilder("Transaction").append(separator).append("Breached Rules").append('\n');
        for (Transaction t : transactions) {
            String rendered = engine.breaches(t, rules).stream()
                    .sorted(ruleComparator)
                    .map(r -> r.ruleId() + " (" + r.severity() + ")")
                    .collect(Collectors.joining(", "));
            sb.append(t.transactionId()).append(separator)
                    .append(rendered.isEmpty() ? "none" : rendered).append('\n');
        }
        return sb.toString();
    }
}
