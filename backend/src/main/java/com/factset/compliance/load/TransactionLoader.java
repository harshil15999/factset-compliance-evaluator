package com.factset.compliance.load;

import com.factset.compliance.model.Transaction;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads transactions.json. Parses element by element so one bad transaction is skipped, not fatal. */
@Component
public class TransactionLoader {

    private static final Logger log = LoggerFactory.getLogger(TransactionLoader.class);

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");  // ISO 4217 shape
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");    // ISO 3166 alpha-2 shape
    private static final Instant MIN_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z");

    // snake_case -> record components; floats as BigDecimal; ISO timestamp -> Instant.
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .build();

    public List<Transaction> load(InputStream in) throws IOException {
        JsonNode array = mapper.readTree(in).path("transactions");
        if (!array.isArray()) {
            throw new IOException("Expected a JSON object with a 'transactions' array");
        }
        List<Transaction> transactions = new ArrayList<>();
        int index = 0;
        int skipped = 0;
        Set<String> seenIds = new HashSet<>();
        for (JsonNode node : array) {
            index++;
            if (node == null || node.isNull() || !node.isObject()) {
                skipped++;
                log.warn("Skipping non-object transaction element at position {}", index);
                continue;
            }
            try {
                Transaction t = mapper.treeToValue(node, Transaction.class);
                List<String> problems = new ArrayList<>();
                validateRecord(t, index, problems);
                if (!problems.isEmpty()) {
                    skipped++;
                    log.warn("Skipping malformed transaction at position {}: {}", index, String.join("; ", problems));
                } else if (t.transactionId() != null && !seenIds.add(t.transactionId())) {
                    skipped++;
                    log.warn("Skipping duplicate transaction_id '{}' at position {}", t.transactionId(), index);
                } else {
                    transactions.add(t);
                }
            } catch (Exception e) {
                skipped++;
                log.warn("Skipping malformed transaction at position {}: {}", index, e.getMessage());
            }
        }
        log.info("Loaded {} transactions, skipped {} element(s)", transactions.size(), skipped);
        return transactions;
    }

    public List<String> validate(InputStream in) {
        List<String> problems = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(in);
        } catch (Exception e) {
            problems.add("transactions: malformed JSON syntax: " + e.getMessage());
            return problems;
        }
        if (root == null || !root.isObject()) {
            problems.add("transactions: expected a JSON root object");
            return problems;
        }
        JsonNode array = root.path("transactions");
        if (!array.isArray()) {
            problems.add("transactions: expected a JSON object with a 'transactions' array");
            return problems;
        }
        int index = 0;
        Set<String> seenIds = new HashSet<>();
        for (JsonNode node : array) {
            index++;
            if (node == null || node.isNull() || !node.isObject()) {
                problems.add("transactions[" + index + "]: expected a JSON object element");
                continue;
            }
            try {
                Transaction t = mapper.treeToValue(node, Transaction.class);
                validateRecord(t, index, problems);  // all per-record checks
                String id = t.transactionId();
                if (id != null && !id.isBlank() && !seenIds.add(id)) {
                    problems.add("transactions[" + index + "]: duplicate transaction_id '" + id + "'");
                }
            } catch (Exception e) {
                problems.add("transactions[" + index + "]: " + e.getMessage());
            }
        }
        return problems;
    }

    /**
     * Validates one transaction against the required rules:
     * - transaction_id: required
     * - timestamp: required; within 2000-01-01Z .. now + 1 day
     * - amount: required; must be > 0
     * - currency: if present, must match ^[A-Z]{3}$
     * - country: if present, must match ^[A-Z]{2}$
     * - customer_id, account_type, transaction_type: free text (no check)
     */
    private static void validateRecord(Transaction t, int index, List<String> problems) {
        String prefix = "transactions[" + index + "]: ";

        if (t.transactionId() == null || t.transactionId().isBlank()) {
            problems.add(prefix + "missing transaction_id");
        }
        if (t.timestamp() == null) {
            problems.add(prefix + "missing or invalid timestamp");
        } else if (t.timestamp().isBefore(MIN_TIMESTAMP)
                || t.timestamp().isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) {
            problems.add(prefix + "timestamp " + t.timestamp() + " is out of the expected range");
        }
        if (t.amount() == null) {
            problems.add(prefix + "missing or invalid amount");
        } else if (t.amount().signum() <= 0) {
            problems.add(prefix + "amount must be positive (was " + t.amount().toPlainString() + ")");
        }

        if (t.currency() != null && !CURRENCY.matcher(t.currency()).matches()) {
            problems.add(prefix + "currency '" + t.currency() + "' is not a 3-letter ISO code");
        }
        if (t.country() != null && !COUNTRY.matcher(t.country()).matches()) {
            problems.add(prefix + "country '" + t.country() + "' is not a 2-letter ISO code");
        }
    }
}
