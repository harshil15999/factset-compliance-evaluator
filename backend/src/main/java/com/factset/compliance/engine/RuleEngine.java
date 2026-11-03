package com.factset.compliance.engine;

import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core compliance evaluation engine.
 *
 * <p>Evaluates regulatory compliance rules against customer transactions. Acts as the single
 * source of truth for the rule vocabulary and supported field-operator mappings across the CLI,
 * validation routines, and web interface.
 *
 * <h3>Missing Optional Field Semantics:</h3>
 * <p>If a rule tests an optional transaction field that is not present (or {@code null}) on a
 * given transaction (e.g. {@code currency neq USD} on a transaction without currency), the rule
 * evaluates to {@code false} (no breach). A diagnostic warning is logged to diagnostics.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /**
     * The single source of truth for the rule vocabulary.
     * All loaders, validators, schema endpoints, and frontend forms derive from this list.
     */
    public static final List<String> FIELDS = List.of(
            "amount", "currency", "country", "transaction_type", "account_type",
            "customer_id", "transaction_id", "timestamp.hour");

    /** Immutable lookup set of all recognized field names. */
    public static final Set<String> KNOWN_FIELDS = Set.copyOf(FIELDS);

    /** Canonical operator symbols supported by the compliance engine. */
    public static final List<String> OPERATORS = List.of("gt", "lt", "eq", "neq", "in");

    /** Minimum allowable hour component in UTC (inclusive). */
    public static final int HOUR_MIN = 0;

    /** Maximum allowable hour component in UTC (inclusive). */
    public static final int HOUR_MAX = 23;

    /**
     * Resolves the logical data type for a given transaction field.
     *
     * @param field Name of the field to inspect (e.g. {@code "amount"}, {@code "timestamp.hour"}).
     * @return {@code "number"} for numeric amounts, {@code "hour"} for UTC hours, or {@code "string"} for all others.
     */
    public static String fieldType(String field) {
        return switch (field) {
            case "amount" -> "number";
            case "timestamp.hour" -> "hour";
            default -> "string";
        };
    }

    /**
     * Resolves the allowable comparison operators for a given field based on its logical type.
     *
     * <ul>
     *   <li>{@code number}: Threshold and equality comparisons ({@code "gt"}, {@code "lt"}, {@code "eq"}, {@code "neq"}).</li>
     *   <li>{@code hour}: All operators ({@code "gt"}, {@code "lt"}, {@code "eq"}, {@code "neq"}, {@code "in"}).</li>
     *   <li>{@code string}: Text comparisons ({@code "eq"}, {@code "neq"}, {@code "in"}).</li>
     * </ul>
     *
     * @param field Name of the field to inspect.
     * @return Set of allowed lowercase operator symbols.
     */
    public static Set<String> allowedOperators(String field) {
        return switch (fieldType(field)) {
            case "number" -> Set.of("gt", "lt", "eq", "neq");
            case "hour" -> Set.of("gt", "lt", "eq", "neq", "in");
            default -> Set.of("eq", "neq", "in");
        };
    }

    /**
     * Canonicalizes hour strings by stripping leading zeros from integers (e.g. {@code "06"} -&gt; {@code "6"}, {@code "01,02"} -&gt; {@code "1,2"}).
     *
     * @param val Comma-separated or single hour string.
     * @return Canonicalized hour string.
     */
    public static String canonicalizeHourValue(String val) {
        if (val == null || val.isBlank()) {
            return val;
        }
        return Arrays.stream(val.split(",", -1))
                .map(s -> {
                    try {
                        return String.valueOf(Integer.parseInt(s.trim()));
                    } catch (NumberFormatException e) {
                        return s.trim();
                    }
                })
                .collect(Collectors.joining(","));
    }

    /**
     * Evaluates a collection of rules against a transaction, collecting all breached rules.
     *
     * @param t     The transaction to evaluate.
     * @param rules The list of compliance rules to test against.
     * @return A list of rules that were breached by this transaction, in the order they were evaluated.
     */
    public List<Rule> breaches(Transaction t, List<Rule> rules) {
        List<Rule> hits = rules.stream()
                .filter(rule -> isBreached(t, rule))
                .toList();
        if (hits.isEmpty()) {
            log.info("Transaction {} breached no rules", t.transactionId());
        } else {
            log.info("Transaction {} breached {} rule(s): {}", t.transactionId(), hits.size(),
                    hits.stream().map(Rule::ruleId).toList());
        }
        return hits;
    }

    /**
     * Tests whether a single compliance rule condition is breached by a transaction.
     *
     * @param t    The transaction under test.
     * @param rule The rule condition to evaluate.
     * @return {@code true} if the transaction breaches the rule; {@code false} otherwise.
     */
    public boolean isBreached(Transaction t, Rule rule) {
        String val = fieldValue(t, rule.field());
        if (val == null) {
            log.warn("Transaction {}: field '{}' unknown or missing; rule {} skipped",
                    t.transactionId(), rule.field(), rule.ruleId());
            return false;
        }
        try {
            String ruleVal = rule.value();
            if ("timestamp.hour".equals(rule.field())) {
                ruleVal = canonicalizeHourValue(ruleVal);
            }
            if ("number".equals(fieldType(rule.field()))) {
                BigDecimal fieldNum = new BigDecimal(val);
                BigDecimal ruleNum = new BigDecimal(ruleVal.trim());
                return rule.operator().testNumeric(fieldNum, ruleNum);
            }
            return rule.operator().test(val, ruleVal);
        } catch (NumberFormatException e) {
            log.warn("Transaction {}: non-numeric value '{}' for field '{}' on rule {}",
                    t.transactionId(), val, rule.field(), rule.ruleId());
            return false;
        }
    }

    /**
     * Extracts the target field from a transaction as a string formatted for evaluation.
     *
     * <p>Applies {@link String#trim()} to all string fields so accidental leading or trailing
     * whitespace in incoming JSON payloads does not cause false negatives.
     *
     * @param t     The transaction.
     * @param field Name of the field or derived property.
     * @return String representation of the field value, or {@code null} if the field is absent.
     */
    public String fieldValue(Transaction t, String field) {
        return switch (field) {
            case "amount" -> t.amount() != null ? t.amount().toPlainString() : null;
            case "currency" -> t.currency() != null ? t.currency().trim() : null;
            case "country" -> t.country() != null ? t.country().trim() : null;
            case "transaction_type" -> t.transactionType() != null ? t.transactionType().trim() : null;
            case "account_type" -> t.accountType() != null ? t.accountType().trim() : null;
            case "customer_id" -> t.customerId() != null ? t.customerId().trim() : null;
            case "transaction_id" -> t.transactionId() != null ? t.transactionId().trim() : null;
            case "timestamp.hour" -> t.timestamp() != null
                    ? String.valueOf(t.timestamp().atZone(ZoneOffset.UTC).getHour())
                    : null;
            default -> null;
        };
    }
}
