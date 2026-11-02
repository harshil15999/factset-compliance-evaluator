package com.factset.compliance.model;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The comparison operators from the brief. Keeping the comparison on the enum keeps the rule set
 * data-driven: a new rule is a row in rules.csv, not code. gt/lt compare as numbers; eq/neq/in are
 * case-sensitive string comparisons.
 */
public enum Operator {
    GT("gt"), LT("lt"), EQ("eq"), NEQ("neq"), IN("in");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    private static final Map<String, Operator> BY_SYMBOL = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Operator::symbol, Function.identity()));

    /** @throws IllegalArgumentException for an unknown symbol (used to skip bad rules). */
    public static Operator fromSymbol(String symbol) {
        Operator op = BY_SYMBOL.get(symbol);
        if (op == null) {
            throw new IllegalArgumentException("unknown operator '" + symbol + "'");
        }
        return op;
    }

    /** @throws NumberFormatException for gt/lt when a side is not numeric (engine treats as not breached). */
    public boolean test(String fieldValue, String ruleValue) {
        return switch (this) {
            case GT -> new BigDecimal(fieldValue).compareTo(new BigDecimal(ruleValue)) > 0;
            case LT -> new BigDecimal(fieldValue).compareTo(new BigDecimal(ruleValue)) < 0;
            case EQ -> fieldValue.equals(ruleValue);
            case NEQ -> !fieldValue.equals(ruleValue);
            case IN -> Arrays.stream(ruleValue.split(",", -1)).map(String::trim).anyMatch(fieldValue::equals);
        };
    }
}
