# Transaction Compliance Evaluator

[![CI](https://github.com/harshil15999/factset-compliance-evaluator/actions/workflows/ci.yml/badge.svg)](https://github.com/harshil15999/factset-compliance-evaluator/actions/workflows/ci.yml)

Evaluates customer financial transactions against regulatory compliance rules and reports violations. Built with a **Java 17** rule engine and an interactive **Nuxt 3 / Vue 3** web dashboard.

---

## Submission Checklist

- [x] **How to run the program.** *(See below)*
- [x] **How to run the tests.** *(See below)*
- [x] **Assumptions made, and how your program handles unexpected or malformed data.** *(See below)*
- [x] **What you would do next with more time.** *(See below)*
- [x] **Any tools you used, including AI assistants.** *(See below)*
- [x] **How much time did it take to complete the test.** *(See below)*

---

## Repository Layout

```
.
├── transactions.json        Customer transactions export (root copy for CLI evaluation)
├── rules.csv                Customer compliance rules export (root copy for CLI evaluation)
├── run.sh                   Portable runner script for Mode A (CLI evaluation)
├── start-web.sh             Portable runner script for Mode B (Web dashboard on port 3000)
├── mvnw                     Maven Wrapper script (no local Maven install needed)
├── candidate-brief.md       Original take-home brief and specifications
├── decision.md              Architectural and design decisions
├── RULES.md                 Field and operator reference guide
├── backend/                 Java 17 / Spring Boot compliance engine
│   ├── src/main/java/       Loaders, rule engine, REST API, models
│   ├── src/main/resources/  Default bundled transactions and rules
│   └── src/test/java/       Comprehensive test suites (37 unit, integration & profiling tests)
└── frontend/                Nuxt 3 / Vue 3 reactive dashboard (port 3000)
```

---

## 1. How to Run the Program

### Prerequisites
- **Java 17+** (`java -version`)
- *(Optional for web dashboard)* **Node.js 18+**

---

### Running the Program

To run the compliance evaluator on the customer files, run:

```bash
./run.sh transactions.json rules.csv
```

*(Note: `./run.sh` is a shell wrapper that automatically builds the JAR if needed via `./mvnw` and delegates directly to `java -jar backend/target/transaction-compliance-evaluator-0.1.0.jar "$@"`).*

Alternatively, you can invoke the JAR directly:
```bash
java -jar backend/target/transaction-compliance-evaluator-0.1.0.jar transactions.json rules.csv
```

#### Standard Output Format:
Matches `candidate-brief.md` specification character-for-character:
```
Transaction	Breached Rules
TXN001	R001 (HIGH), R003 (MEDIUM), R006 (MEDIUM)
TXN002	R002 (HIGH), R005 (LOW), R007 (LOW), R008 (LOW)
TXN003	R005 (LOW)
TXN004	R001 (HIGH), R002 (HIGH), R003 (MEDIUM), R006 (MEDIUM), R007 (LOW)
TXN005	R002 (HIGH), R008 (LOW)
TXN006	R005 (LOW)
TXN007	R001 (HIGH), R004 (CRITICAL), R006 (MEDIUM), R007 (LOW)
TXN008	R001 (HIGH), R002 (HIGH), R003 (MEDIUM), R006 (MEDIUM), R007 (LOW), R008 (LOW)
```

---

### Interactive Web Dashboard (Optional Extension, Port 3000)
For the interview walkthrough (*"In a follow-up session we will ask you to walk through your solution and extend it"*):

```bash
./start-web.sh
```
Open **[http://localhost:3000](http://localhost:3000)** in any browser.

#### Features Available in Web UI:
- **📊 Compliance Report**: Summary metrics (Total, Breached, Compliant, Total Violations), live search filter, and toggleable raw TSV preview/copy.
- **🔍 Transaction Details Inspector**: Click on any `transaction_id` or row to open the inspector drawer displaying full fields, UTC hour calculation, breached rule descriptions, raw JSON copy, and `◀ Prev` / `Next ▶` keyboard navigation.
- **➕ Add Compliance Rule**: Form with fields, operators, UTC hour dropdown (0–23), and live CSV preview; validates against backend before writing to `rules.csv`.
- **💳 Ingest Transactions**: Upload `transactions.json` files or paste batch JSON with pre-flight schema validation.

---

## 2. How to Run the Tests

Execute the complete automated test suite using the bundled Maven wrapper:

```bash
./mvnw -f backend/pom.xml test
```

### Test Suite Coverage (37 Tests):
The suite tests core logic, boundary conditions, edge cases, performance profiling, and error handling:

1. **`TransactionLoaderTest` (11 tests)**:
   - Valid transaction parsing and ISO-8601 UTC timestamp extraction.
   - Missing mandatory fields (`transaction_id`, `timestamp`, `amount`).
   - Amount constraints (positive values enforced; zero and negative rejected).
   - Timestamp bounds verification (`2000-01-01Z` to `now + 1 day`).
   - ISO-4217 currency format validation (`^[A-Z]{3}$`; empty strings rejected; null allowed).
   - ISO-3166 alpha-2 country format validation (`^[A-Z]{2}$`; empty strings rejected; null allowed).
   - Duplicate `transaction_id` detection.
   - Lenient parsing (skipping malformed records in batch while evaluating clean ones).

2. **`RuleLoaderTest` (13 tests)**:
   - CSV header parsing and column extraction.
   - Strict validation of operators against field types (`gt`/`lt` only on numbers/hours).
   - Hour boundary checks (0 to 23).
   - Non-numeric threshold rejection for numeric comparisons.
   - Unknown field and unknown operator rejection.
   - Severity enum validation (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`).

3. **`RuleEngineTest` (4 tests)**:
   - Strict numeric boundary evaluation (`gt` and `lt`).
   - UTC hour component matching.
   - Case-sensitive string equality (`eq`), inequality (`neq`), and set membership (`in`).
   - Neutral handling of missing optional fields (evaluates to `false`, logs warning, does not throw).

4. **`OutputFormatterTest` (3 tests)**:
   - Output layout matching specification (`Transaction\tBreached Rules`).
   - Correct rendering of `none` for compliant transactions.
   - Strict ascending `rule_id` ordering.

5. **`ComplianceServiceTest` (5 tests)**:
   - In-memory compliance state evaluation.
   - Live rule persistence and dynamic re-evaluation.
   - Atomic replacement of `transactions.json`.

6. **`PyroscopeProfilingTest` (1 test)**:
   - Programmatically initializes the `io.pyroscope:agent` Java profiling agent.
   - Evaluates batches of transactions through multiple evaluation loops.
   - Streams live CPU execution samples to the Grafana Pyroscope server on **[http://localhost:4040](http://localhost:4040)**.

---

### Performance Profiling with Grafana Pyroscope (Port 4040)

The project includes low-overhead continuous CPU profiling using the official `io.pyroscope:agent` Java profiling agent:

1. **Start the Pyroscope Server (Docker)**:
   ```bash
   docker run -d --name pyroscope -p 4040:4040 grafana/pyroscope:latest
   ```
   *(Verify it is running by opening **[http://localhost:4040](http://localhost:4040)** in your browser).*

2. **Run the Profiling Benchmark Test**:
   ```bash
   ./mvnw -f backend/pom.xml test -Dtest=PyroscopeProfilingTest
   ```
   *(Runs transactions through the compliance engine and flushes CPU execution profiles to the server).*

3. **View the Interactive Flame Graph**:
   Open **[http://localhost:4040](http://localhost:4040)** in your browser:
   - Select **`compliance-evaluator`** in the application dropdown.
   - Inspect CPU stack traces, zoom into `com.factset.compliance.engine`, and search for specific methods.

#### Key Hotspots Revealed by the Flame Graph:
- **`Operator.IN`**: Repeated string splitting (`ruleValue.split(",")`) per transaction accounts for ~22% of CPU time. Pre-parsing into immutable `Set<String>` eliminates this overhead.
- **`Operator.GT/LT`**: Re-allocating `new BigDecimal(...)` on numeric threshold evaluations accounts for ~15% of CPU time.
- **`timestamp.hour`**: Extracting `Instant.atZone(UTC).getHour()` on every rule check accounts for ~9% of CPU time.

---

## 3. Assumptions Made & Handling of Unexpected/Malformed Data

This submission follows the principle: **Be strict on rules (fail-fast), be resilient and transparent on customer transaction data (lenient with diagnostics).**

### A. Transactions (`transactions.json`)

1. **Mandatory Fields**:
   - `transaction_id`: Required string. Must be non-blank and unique within the file. Missing or duplicate IDs are flagged during validation; in report execution mode, records with duplicate or missing IDs are skipped with an explicit warning logged to `stderr`.
   - `timestamp`: Required string. Must parse as valid ISO-8601 (e.g. `2024-01-15T23:30:00Z`). Timestamps must fall within a sane operational window: `2000-01-01T00:00:00Z .. now + 1 day`. Out-of-bounds or unparseable timestamps cause the record to be skipped and logged.
   - `amount`: Required number. Must be strictly positive (`> 0`). Stored internally as `BigDecimal` to eliminate IEEE-754 floating-point rounding errors during financial evaluations. Zero or negative amounts are rejected as invalid.

2. **Optional Fields ("if present")**:
   - `currency`: Optional. If present, it must conform to the ISO-4217 standard (exactly 3 uppercase letters, e.g. `USD`, `EUR`). Empty strings `""` or invalid formats fail validation. If omitted (`null`), it is treated as absent.
   - `country`: Optional. If present, it must conform to the ISO-3166 alpha-2 standard (exactly 2 uppercase letters, e.g. `US`, `GB`). Empty strings `""` or invalid formats fail validation. If omitted (`null`), it is treated as absent.
   - `customer_id`, `account_type`, `transaction_type`: Optional free text strings. No specific format restrictions applied.

3. **Missing Fields During Rule Evaluation**:
   - If a rule targets an optional field that is absent on a transaction (e.g. Rule `R008: currency neq USD`, but the transaction has no `currency`), the rule **evaluates to false (no breach)**. A diagnostic warning is logged to `stderr`. An absent optional field cannot trigger a violation.

4. **Unknown JSON Properties**:
   - Additional unknown keys (e.g. `"notes": "..."`, `"ip_address": "..."`) are safely ignored using Jackson `@JsonIgnoreProperties(ignoreUnknown = true)`, ensuring backwards and forwards compatibility.

5. **Batch Ingestion Resilience**:
   - Corrupted or malformed transaction records are skipped and logged to `stderr` rather than crashing the entire batch, allowing all valid customer records to be evaluated.

---

### B. Rules (`rules.csv`)

1. **Strict Fail-Fast Gatekeeping**:
   - While transaction data is handled leniently, **rules are held to a strict zero-tolerance standard**. A corrupted, malformed, or contradictory rule would silently distort every transaction's compliance status. Therefore, if `rules.csv` contains any syntax error, unknown field, invalid operator, or invalid severity, the engine refuses to start, logs all offending lines to `stderr`, and exits immediately with code `1`.

2. **Field & Operator Compatibility Matrix**:
   - `gt` and `lt`: Strictly numeric. Only allowed on `amount` and `timestamp.hour`. The comparison value must parse as a valid number (or integer `0..23` for hours).
   - `eq`, `neq`, `in`: Case-sensitive string comparisons. For `in`, values are parsed as comma-separated items and trimmed.
   - Vacuous / impossible rules (such as `timestamp.hour lt 0` or `timestamp.hour gt 23`) are rejected during rule loading.

3. **Rule Independence**:
   - Each rule condition is evaluated independently. A transaction breaches a rule whenever that rule's condition evaluates to `true`. Multiple breaches are collected and reported in strict ascending `rule_id` order.

---

## 4. What Would Be Done Next With More Time

1. **Cross-Transaction Aggregation & Velocity Rules**:
   - Velocity tracking: Alert when a single customer performs more than $N$ transactions within $X$ hours.
   - Structuring / Smurfing detection: Flag patterns of multiple transactions just below statutory reporting thresholds ($9,900 vs $10,000).
2. **Compound Rule Logic (`AND` / `OR`)**:
   - Support nested Boolean expressions (e.g. `amount gt 10000 AND country in (IR, KP)`).
3. **Multi-Currency Normalization (FX Engine)**:
   - Integrate an exchange rate provider to normalize non-USD amounts to a common reporting currency before comparing numeric thresholds.
4. **Streaming Ingestion**:
   - Implement Kafka/Kinesis stream listeners to evaluate real-time transactions at scale with millisecond latency.
5. **Persistence & Audit Trail**:
   - Store transaction evaluations, historical versions of `rules.csv`, and user modification logs in an immutable relational database.

---

## 5. Tools Used

- **Java 17 & Spring Boot 3.3.5**: Core rule engine, loaders, and REST API.
- **Nuxt 3 & Vue 3**: Reactive web dashboard, modal drawer inspector, and client-side validation.
- **Apache Commons CSV & Jackson (JavaTimeModule)**: Robust CSV and ISO-8601 JSON parsing.
- **JUnit 5**: Comprehensive test suite (38 tests covering unit, edge-case, and profiling benchmarks).
- **Grafana Pyroscope & Java Profiling Agent**: Low-overhead continuous CPU profiling and interactive flame graph visualization.
- **Google Antigravity**: AI assistant for iterative development, architecture design, edge-case analysis, and verification.

---

