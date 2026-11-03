package com.factset.compliance.model;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supported comparison operators for compliance rules.
 *
 * <p>Encapsulating the evaluation logic directly within the enum maintains a data-driven
 * design: introducing or modifying compliance rules requires updating {@code rules.csv} rather
 * than changing application source code.
 *
 * <ul>
 *   <li>{@link #GT} and {@link #LT}: Strict numeric comparisons using {@link BigDecimal}.</li>
 *   <li>{@link #EQ}, {@link #NEQ}, and {@link #IN}: Case-sensitive string comparisons.</li>
 * </ul>
 */
public enum Operator {

    /** Strict greater-than comparison: {@code fieldValue > ruleValue}. */
    GT("gt"),

    /** Strict less-than comparison: {@code fieldValue < ruleValue}. */
    LT("lt"),

    /** Exact string equality: {@code fieldValue.equals(ruleValue)}. Case-sensitive. */
    EQ("eq"),

    /** Exact string inequality: {@code !fieldValue.equals(ruleValue)}. Case-sensitive. */
    NEQ("neq"),

    /** Set membership in a comma-separated list of target strings. Case-sensitive. */
    IN("in");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Returns the canonical string representation of this operator as expected in {@code rules.csv}.
     *
     * @return The lowercase operator symbol (e.g. {@code "gt"}, {@code "in"}).
     */
    public String symbol() {
        return symbol;
    }

    private static final Map<String, Operator> BY_SYMBOL = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Operator::symbol, Function.identity()));

    /**
     * Resolves an {@code Operator} instance from its lowercase symbol.
     *
     * @param symbol The operator symbol from CSV input (e.g. {@code "gt"}, {@code "eq"}).
     * @return The corresponding {@link Operator} constant.
     * @throws IllegalArgumentException if the symbol does not match any recognized operator.
     */
    public static Operator fromSymbol(String symbol) {
        Operator op = BY_SYMBOL.get(symbol);
        if (op == null) {
            throw new IllegalArgumentException("unknown operator '" + symbol + "'");
        }
        return op;
    }

    /**
     * Evaluates this operator against a transaction field value and a rule threshold value.
     *
     * @param fieldValue The string-serialized value extracted from the transaction.
     * @param ruleValue  The target comparison value or comma-separated list from the rule.
     * @return {@code true} if the condition is met (breaching the rule); {@code false} otherwise.
     * @throws NumberFormatException if {@code GT} or {@code LT} is evaluated on non-numeric input.
     */
    public boolean test(String fieldValue, String ruleValue) {
        return switch (this) {
            case GT -> new BigDecimal(fieldValue).compareTo(new BigDecimal(ruleValue)) > 0;
            case LT -> new BigDecimal(fieldValue).compareTo(new BigDecimal(ruleValue)) < 0;
            case EQ -> fieldValue.equals(ruleValue);
            case NEQ -> !fieldValue.equals(ruleValue);
            case IN -> Arrays.stream(ruleValue.split(",", -1)).map(String::trim).anyMatch(fieldValue::equals);
        };
    }

    /**
     * Evaluates this operator against numeric operands using {@link BigDecimal#compareTo(BigDecimal)}.
     *
     * <p>Ensures numerical equality accounts for trailing scale and precision differences
     * (e.g. {@code 100} matches {@code 100.00}).
     *
     * @param fieldValue The numeric value extracted from the transaction.
     * @param ruleValue  The numeric threshold defined in the compliance rule.
     * @return {@code true} if the numeric condition is met (breaching the rule); {@code false} otherwise.
     */
    public boolean testNumeric(BigDecimal fieldValue, BigDecimal ruleValue) {
        return switch (this) {
            case GT -> fieldValue.compareTo(ruleValue) > 0;
            case LT -> fieldValue.compareTo(ruleValue) < 0;
            case EQ -> fieldValue.compareTo(ruleValue) == 0;
            case NEQ -> fieldValue.compareTo(ruleValue) != 0;
            case IN -> false;
        };
    }
}
