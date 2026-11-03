package com.factset.compliance.model;

/**
 * Immutable domain representation of a regulatory compliance rule parsed from {@code rules.csv}.
 *
 * <p>Each rule represents an independent condition evaluated against transactions. If a transaction
 * satisfies the condition defined by {@code field}, {@code operator}, and {@code value},
 * that transaction is deemed to have breached the rule.
 *
 * @param ruleId   Unique identifier for the rule, e.g. {@code "R001"} (mandatory).
 * @param ruleName Human-readable descriptive name or label for the rule.
 * @param field    Transaction property to evaluate (e.g. {@code "amount"}, {@code "country"},
 *                 or the derived {@code "timestamp.hour"}).
 * @param operator Relational comparison operator to apply (see {@link Operator}).
 * @param value    Target threshold or set of values to evaluate against (comma-separated for {@code in}).
 * @param severity Regulatory severity rating assigned upon breach (see {@link Severity}).
 */
public record Rule(
        String ruleId,
        String ruleName,
        String field,
        Operator operator,
        String value,
        Severity severity) {
}
