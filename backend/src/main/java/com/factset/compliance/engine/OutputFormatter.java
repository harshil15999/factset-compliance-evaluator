package com.factset.compliance.engine;

import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Renders the report: a header, then one line per transaction, breached rules or "none". */
@Component
public class OutputFormatter {

    private static final Comparator<Rule> RULE_ID_ORDER = Comparator.comparing(Rule::ruleId);
    private static final Comparator<Rule> SEVERITY_ORDER = Comparator.comparing(Rule::severity)
            .reversed()
            .thenComparing(Rule::ruleId);

    // Column separator (tab default, per the brief).
    private final String separator;
    private final Comparator<Rule> ruleComparator;

    @org.springframework.beans.factory.annotation.Autowired
    public OutputFormatter(@Value("${app.output.separator:\t}") String separator,
                           @Value("${app.output.sort:rule_id}") String sort) {
        this.separator = separator;
        this.ruleComparator = "severity".equalsIgnoreCase(sort) ? SEVERITY_ORDER : RULE_ID_ORDER;
    }

    public OutputFormatter(String separator) {
        this(separator, "rule_id");
    }

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
