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

/**
 * Ingestion and validation service for transaction batches from {@code transactions.json}.
 *
 * <p>Implements a lenient element-by-element parsing strategy: a single corrupted record
 * within a batch of thousands is skipped and logged to diagnostics rather than aborting the
 * entire compliance evaluation run.
 *
 * <h3>Enforced Data Constraints:</h3>
 * <ul>
 *   <li><b>{@code transaction_id}</b>: Mandatory non-blank string. Duplicates within the same file are rejected.</li>
 *   <li><b>{@code timestamp}</b>: Mandatory ISO-8601 UTC timestamp. Must fall within a sane operational window
 *       ({@code 2000-01-01T00:00:00Z} to {@code now + 1 day}).</li>
 *   <li><b>{@code amount}</b>: Mandatory positive number ({@code > 0}), parsed to {@link java.math.BigDecimal}.</li>
 *   <li><b>{@code currency}</b>: Optional. If present, must conform to ISO-4217 (exactly 3 uppercase letters).</li>
 *   <li><b>{@code country}</b>: Optional. If present, must conform to ISO-3166 alpha-2 (exactly 2 uppercase letters).</li>
 * </ul>
 */
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

    /**
     * Parses transactions from an {@link InputStream}, skipping invalid or duplicate records with warnings.
     *
     * @param in Stream providing the root JSON object containing a {@code "transactions"} array.
     * @return A list of successfully parsed and validated {@link Transaction} instances.
     * @throws IOException If the root JSON is malformed or missing the required array.
     */
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

    /**
     * Validates an entire transactions payload without dropping records, collecting all schema diagnostics.
     *
     * @param in Stream providing the JSON content to inspect.
     * @return A list of human-readable problem descriptions, empty if the input is completely valid.
     */
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
     * Validates one transaction against mandatory fields, format constraints, and domain invariants.
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
