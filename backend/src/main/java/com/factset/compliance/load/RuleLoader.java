package com.factset.compliance.load;

import com.factset.compliance.engine.RuleEngine;
import com.factset.compliance.model.Operator;
import com.factset.compliance.model.Rule;
import com.factset.compliance.model.Severity;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CSV parser and validator for compliance rules loaded from {@code rules.csv}.
 *
 * <p>Enforces strict validation rules:
 * <ul>
 *   <li>The CSV header must include {@code rule_id}, {@code field}, {@code operator}, and {@code severity}.</li>
 *   <li>{@code rule_id} values must be unique and match the canonical {@code ^R\d+$} pattern.</li>
 *   <li>Fields must belong to the approved domain vocabulary in {@link RuleEngine#FIELDS}.</li>
 *   <li>Operators must be compatible with field types (e.g. {@code gt}/{@code lt} only on numeric fields).</li>
 *   <li>Numeric thresholds must parse cleanly as {@link BigDecimal} and cannot be negative.</li>
 *   <li>Hour thresholds must parse as integer values in the range {@code 0..23}.</li>
 * </ul>
 */
@Component
public class RuleLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleLoader.class);

    private static final Pattern RULE_ID = Pattern.compile("^R\\d+$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");  // ISO 3166 alpha-2
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$"); // ISO 4217
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Z0-9_]+$");

    /**
     * Parses rules from a character stream, skipping malformed records with warning diagnostics.
     *
     * @param reader Source reader providing CSV formatted rule data.
     * @return List of successfully parsed {@link Rule} instances.
     * @throws IOException If an I/O error occurs reading from the underlying stream.
     */
    public List<Rule> load(Reader reader) throws IOException {
        List<Rule> rules = new ArrayList<>();
        int skipped = 0;
        try (CSVParser parser = csvFormat().parse(reader)) {
            for (CSVRecord record : parser) {
                try {
                    Rule rule = parse(record);
                    List<String> rowProblems = new ArrayList<>();
                    checkFieldOperatorValue(rule, record.getRecordNumber(), rowProblems);
                    if (!rowProblems.isEmpty()) {
                        skipped++;
                        log.warn("Skipping invalid rule on line {}: {}", record.getRecordNumber(), String.join("; ", rowProblems));
                    } else {
                        rules.add(rule);
                    }
                } catch (IllegalArgumentException e) {
                    skipped++;
                    log.warn("Skipping malformed rule on line {}: {}", record.getRecordNumber(), e.getMessage());
                }
            }
        }
        log.info("Loaded {} rules, skipped {} row(s)", rules.size(), skipped);
        return rules;
    }

    /**
     * Validates an entire rules CSV payload without dropping rows, returning all semantic violations.
     *
     * @param reader Source reader providing CSV formatted rule data to inspect.
     * @return List of error messages; empty if the entire CSV is strictly valid.
     * @throws IOException If an I/O error occurs reading from the stream.
     */
    public List<String> validate(Reader reader) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (CSVParser parser = csvFormat().parse(reader)) {
            Map<String, Integer> headerMap = parser.getHeaderMap();
            if (headerMap == null || headerMap.isEmpty()) {
                problems.add("rules.csv is empty or missing required header: rule_id,rule_name,field,operator,value,severity");
                return problems;
            }
            List<String> requiredHeaders = List.of("rule_id", "field", "operator", "severity");
            for (String h : requiredHeaders) {
                if (!headerMap.containsKey(h)) {
                    problems.add("rules.csv header is missing required column '" + h + "'");
                }
            }
            if (!problems.isEmpty()) {
                return problems;
            }
            for (CSVRecord record : parser) {
                long row = record.getRecordNumber();  // data-row number, header excluded
                try {
                    Rule rule = parse(record);
                    if (!RULE_ID.matcher(rule.ruleId()).matches()) {
                        problems.add("rules row " + row + ": rule_id '" + rule.ruleId() + "' is not in expected format (e.g. R001)");
                    }
                    if (!seenIds.add(rule.ruleId())) {
                        problems.add("rules row " + row + ": duplicate rule_id '" + rule.ruleId() + "'");
                    }
                    checkFieldOperatorValue(rule, row, problems);
                } catch (IllegalArgumentException e) {
                    problems.add("rules row " + row + ": " + e.getMessage());
                }
            }
        }
        return problems;
    }

    private static CSVFormat csvFormat() {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
    }

    private static boolean isNumeric(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isHour(String s) {
        try {
            int h = Integer.parseInt(s.trim());
            return h >= RuleEngine.HOUR_MIN && h <= RuleEngine.HOUR_MAX;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Semantic checks on one rule: the field exists, the operator is valid for that field, the value is
     * present, and the value fits the field's domain (number for amount, hour 0-23 for timestamp.hour,
     * non-empty items for an `in` list). Each failure is added to {@code problems}.
     */
    private static void checkFieldOperatorValue(Rule rule, long row, List<String> problems) {
        String field = rule.field();
        String op = rule.operator().symbol();
        String value = rule.value();
        String at = "rules row " + row + ": ";

        if (!RuleEngine.KNOWN_FIELDS.contains(field)) {
            problems.add(at + "unknown field '" + field + "' (known: " + RuleEngine.KNOWN_FIELDS + ")");
            return;  // can't judge operator/value against an unknown field
        }
        Set<String> allowed = RuleEngine.allowedOperators(field);
        if (!allowed.contains(op)) {
            problems.add(at + "operator '" + op + "' is not valid for field '" + field + "' (allowed: " + allowed + ")");
            return;
        }
        if (value == null || value.isBlank()) {
            problems.add(at + "value is blank; '" + field + " " + op + "' needs a value");
            return;
        }

        List<String> items = op.equals("in")
                ? Arrays.stream(value.split(",", -1)).map(String::trim).toList()
                : List.of(value);
        if (op.equals("in") && (items.isEmpty() || items.stream().anyMatch(String::isEmpty))) {
            problems.add(at + "'in' list has an empty item: '" + value + "'");
            return;
        }
        for (String item : items) {
            switch (RuleEngine.fieldType(field)) {
                case "number" -> {
                    if (!isNumeric(item)) {
                        problems.add(at + "value '" + item + "' is not a number for field '" + field + "'");
                    } else {
                        BigDecimal num = new BigDecimal(item);
                        if (num.compareTo(BigDecimal.ZERO) < 0) {
                            problems.add(at + "amount threshold cannot be negative (was " + item + ")");
                        } else if (op.equals("lt") && num.compareTo(BigDecimal.ZERO) == 0) {
                            problems.add(at + "'lt 0' on amount can never match (transaction amounts are positive)");
                        }
                    }
                }
                case "hour" -> {
                    if (!isHour(item)) {
                        problems.add(at + "value '" + item + "' is not an hour "
                                + RuleEngine.HOUR_MIN + "-" + RuleEngine.HOUR_MAX + " for field '" + field + "'");
                    } else {
                        int h = Integer.parseInt(item.trim());
                        // In-range but vacuous: gt MAX and lt MIN can never match.
                        if (op.equals("gt") && h == RuleEngine.HOUR_MAX) {
                            problems.add(at + "'gt " + h + "' on " + field + " can never match ("
                                     + RuleEngine.HOUR_MAX + " is the max hour)");
                        } else if (op.equals("lt") && h == RuleEngine.HOUR_MIN) {
                            problems.add(at + "'lt " + h + "' on " + field + " can never match ("
                                    + RuleEngine.HOUR_MIN + " is the min hour)");
                        }
                    }
                }
                default -> {
                    if (field.equals("country") && !COUNTRY.matcher(item).matches()) {
                        problems.add(at + "value '" + item + "' is not a 2-letter ISO country code for field 'country'");
                    } else if (field.equals("currency") && !CURRENCY.matcher(item).matches()) {
                        problems.add(at + "value '" + item + "' is not a 3-letter ISO currency code for field 'currency'");
                    } else if ((field.equals("account_type") || field.equals("transaction_type")) && !IDENTIFIER.matcher(item).matches()) {
                        problems.add(at + "value '" + item + "' should be an uppercase identifier for field '" + field + "'");
                    }
                }
            }
        }
    }

    private Rule parse(CSVRecord r) {
        String field = require(r, "field");
        String val = r.isSet("value") ? r.get("value") : "";
        if ("timestamp.hour".equals(field) && !val.isBlank()) {
            val = canonicalizeHourValue(val);
        }
        return new Rule(
                require(r, "rule_id"),
                r.isSet("rule_name") ? r.get("rule_name") : "",
                field,
                Operator.fromSymbol(require(r, "operator")),
                val,
                severity(require(r, "severity")));
    }

    private static String canonicalizeHourValue(String val) {
        return RuleEngine.canonicalizeHourValue(val);
    }

    private static String require(CSVRecord r, String column) {
        String value = r.isSet(column) ? r.get(column) : null;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required column '" + column + "'");
        }
        return value;
    }

    private static Severity severity(String raw) {
        try {
            return Severity.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid severity '" + raw + "'");
        }
    }
}
