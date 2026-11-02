package com.factset.compliance.model;

/** One row of rules.csv. */
public record Rule(
        String ruleId,
        String ruleName,
        String field,
        Operator operator,
        String value,
        Severity severity) {
}
