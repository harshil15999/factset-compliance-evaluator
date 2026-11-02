package com.factset.compliance.engine;

import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/** Evaluates rules against a transaction, returning the breached rules (ordering is the formatter's job). */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /**
     * The single source of truth for the rule vocabulary. The validator, the {@code schema} command,
     * and — via that command — the frontend all derive from these; nothing is re-declared elsewhere.
     * Keep fieldValue below in sync with FIELDS (the one unavoidable in-backend pairing).
     */
    public static final List<String> FIELDS = List.of(
            "amount", "currency", "country", "transaction_type", "account_type",
            "customer_id", "transaction_id", "timestamp.hour");
    public static final Set<String> KNOWN_FIELDS = Set.copyOf(FIELDS);
    public static final List<String> OPERATORS = List.of("gt", "lt", "eq", "neq", "in");
    public static final int HOUR_MIN = 0;
    public static final int HOUR_MAX = 23;

    /** A field's value type: number (amount), hour (timestamp.hour, 0-23), or string (everything else). */
    public static String fieldType(String field) {
        return switch (field) {
            case "amount" -> "number";
            case "timestamp.hour" -> "hour";
            default -> "string";
        };
    }

    /**
     * Operators a field accepts, derived from its type: number is a threshold (gt/lt only — string
     * equality on decimals is unreliable); hour is an integer (any operator); string is eq/neq/in.
     */
    public static Set<String> allowedOperators(String field) {
        return switch (fieldType(field)) {
            case "number" -> Set.of("gt", "lt");
            case "hour" -> Set.of("gt", "lt", "eq", "neq", "in");
            default -> Set.of("eq", "neq", "in");
        };
    }

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

    private boolean isBreached(Transaction t, Rule rule) {
        String value = fieldValue(t, rule.field());
        if (value == null) {
            log.warn("Transaction {}: field '{}' unknown or missing; rule {} skipped",
                    t.transactionId(), rule.field(), rule.ruleId());
            return false;
        }
        if (rule.value() == null) {
            log.warn("Transaction {}: rule {} ({} {}) has null value; skipped",
                    t.transactionId(), rule.ruleId(), rule.field(), rule.operator().symbol());
            return false;
        }
        try {
            return rule.operator().test(value, rule.value());
        } catch (NumberFormatException e) {
            // gt/lt against a non-numeric field/value: cannot compare as numbers, so not a breach.
            log.warn("Transaction {}: rule {} ({} {} {}) is not a numeric comparison; not breached",
                    t.transactionId(), rule.ruleId(), rule.field(), rule.operator().symbol(), rule.value());
            return false;
        } catch (Exception e) {
            log.warn("Transaction {}: rule {} evaluation failed: {}; not breached",
                    t.transactionId(), rule.ruleId(), e.getMessage());
            return false;
        }
    }

    /** The transaction field as a string, or null if unknown/absent. timestamp.hour is the UTC hour. */
    private static String fieldValue(Transaction t, String field) {
        return switch (field) {
            case "amount" -> t.amount() == null ? null : t.amount().toPlainString();
            case "currency" -> t.currency();
            case "country" -> t.country();
            case "transaction_type" -> t.transactionType();
            case "account_type" -> t.accountType();
            case "customer_id" -> t.customerId();
            case "transaction_id" -> t.transactionId();
            case "timestamp.hour" -> t.timestamp() == null ? null
                    : String.valueOf(t.timestamp().atZone(ZoneOffset.UTC).getHour());
            default -> null;
        };
    }
}
