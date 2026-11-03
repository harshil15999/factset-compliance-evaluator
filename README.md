# Transaction Compliance Evaluator

[![CI](https://github.com/harshil15999/factset-compliance-evaluator/actions/workflows/ci.yml/badge.svg)](https://github.com/harshil15999/factset-compliance-evaluator/actions/workflows/ci.yml)

Evaluates customer financial transactions against regulatory compliance rules and reports violations. Built with a **Java 17** rule engine and an interactive **Nuxt 3 / Vue 3** web dashboard.

---

## Submission Checklist

- [x] **How to run the program.** *(Section 1)*
- [x] **How to run the tests.** *(Section 2)*
- [x] **Assumptions made, and how your program handles unexpected or malformed data.** *(Section 3 — includes [Complete Field & Operator Compatibility Matrix](#compatibility-matrix-rules-rulescsv--transactions-transactionsjson))*
- [x] **What you would do next.** *(Section 4)*
- [x] **Any tools you used, including AI assistants.** *(Section 5)*

---

## Repository Layout

```
.
├── data/                    Canonical data store (single source of truth)
│   ├── rules.csv            Customer compliance rules dataset
│   └── transactions.json    Customer transactions dataset
├── Dockerfile               Multi-stage container build (JDK 17 + Maven + Node 20)
├── .dockerignore           Excludes host build artifacts from container context
├── docker-compose.yml       Multi-service orchestration (CLI & Web dashboard)
├── docker-entrypoint.sh     Container entrypoint router for CLI & Web service
├── run.sh                   Portable runner script for CLI evaluation (Mode A)
├── start-web.sh             Portable runner script for Web dashboard on port 3000 (Mode B)
├── mvnw                     Maven Wrapper script (no local Maven install needed)
├── candidate-brief.md       Original take-home brief and specifications
├── decision.md              Architectural and design decisions
├── RULES.md                 Field and operator reference guide
├── backend/                 Java 17 / Spring Boot compliance engine
│   ├── pom.xml              Maven configuration with Pyroscope Java Agent
│   ├── src/main/java/       Loaders, rule engine, CLI runner, models
│   ├── src/main/resources/  Application properties and logback configuration
│   └── src/test/java/       Comprehensive test suites (46 unit, integration & profiling tests)
└── frontend/                Nuxt 3 / Vue 3 reactive dashboard (port 3000)
```

---

## Architecture & Request Processing Flow

The system decouples data ingestion, validation, core evaluation, and presentation into clean, deterministic stages:

```mermaid
flowchart TD
    subgraph Inputs ["Inputs"]
        TXN["data/transactions.json"]
        RULES["data/rules.csv"]
    end

    subgraph Entrypoints ["Entrypoints"]
        DOCKER["Docker Container<br/>(docker compose)"]
        CLI["Native CLI<br/>(./run.sh)"]
        WEB["Web Dashboard<br/>(Port 3000)"]
    end

    subgraph Pipeline ["Processing Pipeline"]
        RL["RuleLoader<br/>(Strict Fail-Closed Validation)"]
        TL["TransactionLoader<br/>(Jackson BigDecimal Streaming)"]
        RE["RuleEngine<br/>(Core Evaluation & Ceiling Rounding)"]
        OF["OutputFormatter<br/>(Ascending rule_id Sorting)"]

        RL --> RE
        TL --> RE
        RE --> OF
    end

    subgraph Outputs ["Outputs"]
        STDOUT["Exact TSV Report<br/>(stdout)"]
        DASH["Reactive UI Dashboard<br/>(Port 3000)"]
    end

    TXN --> DOCKER
    RULES --> DOCKER
    TXN --> CLI
    RULES --> CLI
    TXN --> WEB
    RULES --> WEB

    DOCKER --> RL
    DOCKER --> TL
    CLI --> RL
    CLI --> TL
    WEB --> RL
    WEB --> TL

    OF --> STDOUT
    RE --> DASH
```

### Request Processing Flow

1. **Unified Entrypoints**:
   - **Docker Container**: `docker compose run --rm cli` evaluates canonical datasets in a zero-setup containerized runtime.
   - **Native CLI**: `./run.sh [transactions.json] [rules.csv]` (defaults to `data/transactions.json` and `data/rules.csv`) boots Spring Boot with `WebApplicationType.NONE` to eliminate web overhead and port conflicts.
   - **Web Dashboard**: `./start-web.sh` or `docker compose up` provides an interactive Nuxt 3 web interface on port 3000 for rule management and batch inspection.

2. **Strict Rule Validation (`RuleLoader`)**:
   - Reads CSV rules via Apache Commons CSV and validates required schema headers (`rule_id`, `field`, `operator`, `severity`).
   - Verifies all conditions against the engine vocabulary and operator compatibility matrix (`gt`/`lt` for numbers/hours, `in` for text only, hours bounded to `0..23`).
   - **Fail-Closed Gatekeeping**: Any invalid operator, malformed value, or unrecognized field immediately halts execution with exit code `1` and descriptive errors on `stderr`.

3. **Resilient Transaction Parsing (`TransactionLoader`)**:
   - Streams JSON transactions element-by-element using Jackson to maintain constant memory overhead.
   - Deserializes monetary values directly into `BigDecimal` to prevent IEEE-754 floating-point inaccuracies.
   - Parses timestamps into standard ISO-8601 UTC `Instant`.
   - **Safe Error Isolation**: Corrupted records (missing required IDs, negative amounts, invalid dates) are logged to `stderr` and skipped without aborting evaluation of valid transactions.

4. **Core Evaluation & Rounding (`RuleEngine`)**:
   - Evaluates each transaction against all active rules independently:
     - **Ceiling Rounding**: Monetary amounts with three or more decimal places round off to the highest two decimal places (`RoundingMode.CEILING`, e.g. `100.121` $\to$ `100.13`) before relational comparisons.
     - **Relational & Temporal Matching**: Numeric comparisons (`gt`, `lt`, `eq`, `neq`) use `BigDecimal.compareTo`; temporal rules extract the UTC hour (`0..23`); text operators (`eq`, `neq`, `in`) use exact string matching.
     - **Safe Missing-Field Handling**: Missing optional fields (`null`) safely evaluate to `false` without throwing null-pointer exceptions or creating false-positive breaches.

5. **Deterministic Formatting (`OutputFormatter`)**:
   - Formats detected violations into `rule_id (severity)`, sorted in strict ascending `rule_id` order (e.g. `R001 (HIGH), R003 (MEDIUM)`).
   - Fully compliant transactions with zero violations output `none`.
   - Delivers the final TSV report directly to `stdout` with exit code `0`.

---

## 1. How to Run the Program

### Docker Containerized Execution (Recommended for Evaluation — Zero Host Setup)

Reviewers and evaluators need **NO local Java, Node, or Maven installed**—only Docker and Docker Compose. The entire solution is fully containerized and zero-setup:

- **Run CLI evaluation** (prints exact TSV report):
  ```bash
  docker compose run --rm cli
  ```

- **Run with custom files**:
  ```bash
  docker compose run --rm cli ./run.sh path/to/tx.json path/to/rules.csv
  ```

- **Run pre-flight validation**:
  ```bash
  docker compose run --rm cli ./run.sh validate
  ```

- **Run schema introspection**:
  ```bash
  docker compose run --rm cli ./run.sh schema
  ```

- **Run automated test suite**:
  ```bash
  docker compose run --rm cli ./mvnw -f backend/pom.xml test
  # or convenience shortcut:
  docker compose run --rm cli test
  ```

- **Launch Web Dashboard on port 3000**:
  ```bash
  docker compose up --build
  ```
  *(or `docker compose up web`), then browse **[http://localhost:3000](http://localhost:3000)**).*

---

### Native Local Execution (Alternative)

If you prefer running natively on your host machine:

#### Prerequisites
- **Java 17+** (`java -version`)
- **Node.js 18+** (for web dashboard)

#### Command-Line Evaluation (Mode A)

To evaluate transactions against compliance rules:

```bash
# Evaluate default customer datasets (located in data/)
./run.sh

# Or evaluate explicitly provided transaction and rule files
./run.sh path/to/transactions.json path/to/rules.csv

# Pre-flight input validation (checks schema & invariants without generating report)
./run.sh validate [path/to/transactions.json path/to/rules.csv]

# Fast standalone rule validation without transactions
./run.sh validate-rules [path/to/rules.csv]

# Schema vocabulary introspection (outputs JSON schema of allowed fields, types, and operators)
./run.sh schema
```

#### Standard Output Format:
Matches the `candidate-brief.md` specification character-for-character:
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
*(Note: In this customer dataset, all 8 transactions contain at least one breach. Transactions that breach no rules output `none` in place of the rule list, as specified in `candidate-brief.md` and verified in unit tests).*

---

#### Interactive Web Dashboard (Mode B — Port 3000)

To start the interactive UI dashboard for the extension walkthrough:

```bash
./start-web.sh
```

Open **[http://localhost:3000](http://localhost:3000)** in any modern browser.

#### Features Available in the Dashboard:
- **📊 Compliance Summary & Filter**: Real-time metrics (Total, Breached, Compliant, Total Violations), instant search, status filtering, and raw TSV preview/copy.
- **🔍 Transaction Details Inspector**: Click any row or transaction ID to open a drawer displaying all fields, calculated UTC hour, breached rule details, and keyboard navigation (`◀ Prev` / `Next ▶`).
- **➕ Add Compliance Rule**: Form with live CSV row preview, field/operator compatibility checks, and UTC hour dropdown (0–23); validates rules before updating `rules.csv`.
- **💳 Ingest Transactions**: Upload `transactions.json` files or paste batch JSON with pre-flight schema validation.

---

## 2. How to Run the Tests

Execute the automated test suite either via Docker (recommended for zero setup) or natively using the Maven wrapper:

- **Via Docker (Recommended)**:
  ```bash
  docker compose run --rm cli ./mvnw -f backend/pom.xml test
  # or convenience shortcut:
  docker compose run --rm cli test
  ```
  *(The unified container image includes Temurin JDK 17, Maven 3.9, and pre-cached Maven local dependencies in `/home/node/.m2`, allowing all 46 test cases across 5 test suites to compile and run completely offline in ~2 seconds).*

- **Natively on Host**:
  ```bash
  ./mvnw -f backend/pom.xml test
  ```

### Test Suite Coverage (46 Tests, 75+ Discrete Scenarios):
The automated suite thoroughly exercises schema validation, domain invariant enforcement, boundary conditions, edge cases, error diagnostics, and performance profiling across 5 test classes:

1. **`TransactionLoaderTest` (18 tests)**:
   - Happy path parsing and ISO-8601 UTC timestamp extraction.
   - Independent presence of optional fields (`currency` without `country`, `country` without `currency`, neither present).
   - Unconstrained free-text fields (`customer_id`, `account_type`, `transaction_type`) allowing punctuation and arbitrary text.
   - 10 boundary and value constraints tested individually via `onlyProblem()` (negative/zero amount, bad currency formats, bad country formats, out-of-range timestamps < 2000 and > now + 1 day).
   - Mandatory field verification (`transaction_id`, `timestamp`, `amount`).
   - 1-based positional error reporting (`transactions[N]: ...`) for missing/blank IDs, malformed timestamps, negative/non-numeric amounts, and duplicate IDs.
   - **Identity Precedence**: When a record has both `transaction_id: null` and an unparseable timestamp (e.g. hour `25:30`), `missing transaction_id` takes precedence over low-level Jackson deserialization errors.
   - Logging diagnostics verification: Logback `ListAppender<ILoggingEvent>` confirms all invalid transactions emit at `Level.ERROR`.
   - First-seen deduplication: Drops duplicate IDs while retaining the first occurrence.
   - Array structure verification: Non-object elements (`null`, numeric literals) and corrupted JSON syntax.

2. **`RuleLoaderTest` (15 tests)**:
   - Happy path CSV parsing across all supported fields, operators, and severities.
   - Unique identifier enforcement: Duplicate `rule_id` rejection across rows.
   - Regex validation: Rule IDs must match canonical pattern `^R\d+$`.
   - Header validation: Enforces presence of mandatory columns (`rule_id`, `field`, `operator`, `severity`).
   - Operator compatibility matrix: Strict rejection of invalid operators (e.g. `in` on numbers, `gt`/`lt` on strings).
   - Numeric domain constraints: Non-numeric strings, negative amounts, and vacuous `amount lt 0` rejected.
   - Hour domain constraints: Non-integer text, out-of-range hours (`> 23`), and vacuous rules (`gt 23`, `lt 0`) rejected.
   - ISO code validation: 2-letter country code (`ISO 3166-1 alpha-2`) and 3-letter currency code (`ISO 4217`) enforced, including inside comma-separated `in` lists.
   - Comma-separated list validation: Empty list items and trailing commas rejected.
   - Blank values and illegal severity enum strings (`EXTREME`) flagged.
   - Hour canonicalization: Normalizes padded hour strings (`"05"`, `"01,02"`) to unpadded integers (`"5"`, `"1,2"`).
   - Row-attributed diagnostics: Exact 1-based line numbering (`rules row N: ...`).
   - Empty CSV and missing header detection.

3. **`RuleEngineTest` (9 tests)**:
   - Strict numeric boundary evaluation (`gt` and `lt` are strictly non-inclusive).
   - UTC hour component matching (e.g. `timestamp.hour gt 22` matches 23:00Z, but not 22:30Z or 00:15Z).
   - Case sensitivity: Exact case-sensitive matching for string equality (`eq`, `neq`) and set membership (`in`).
   - Safe missing-field semantics: Absent optional fields evaluate to `false` without throwing false positives.
   - Numerical equality: Compares via `BigDecimal.compareTo`, matching across scale differences (`100` equals `100.00`).
   - Transaction field sanitization: Whitespace-trimmed property extraction prevents false negatives (`" US "` matches `US`).
   - Evaluation-time canonicalization: Handles un-canonicalized hour strings created programmatically.
   - Precision standard: Amounts with 3+ decimal places round off to the highest two decimal places (`RoundingMode.CEILING`).
   - Integer amount formatting: Normalizes integer amounts to two decimal places (`15000` -> `"15000.00"`).

4. **`OutputFormatterTest` (3 tests)**:
   - Default specification layout: Tab-delimited `Transaction\tBreached Rules` format.
   - Compliant fallback: Outputs `none` for transactions with zero breaches.
   - Ascending sort: Breaches strictly sorted in ascending `rule_id` order.
   - Delimiter flexibility: Configurable column separators (e.g. 4 spaces).
   - Regulatory severity ordering: Optional sorting mode prioritizing severity (`CRITICAL` -> `HIGH` -> `MEDIUM` -> `LOW`).

5. **`PyroscopeProfilingTest` (1 test)**:
   - Programmatically initializes the `io.pyroscope:agent` Java profiling agent.
   - Evaluates 360,000 rule iterations (15,000 transactions * 8 rules * 3 loops).
   - Streams live CPU execution samples to Grafana Pyroscope on **[http://localhost:4040](http://localhost:4040)**.
   - Safely skips when Pyroscope server is inactive (`Assumptions.assumeTrue`).

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

### Generating API Documentation (Javadoc)

To generate the complete HTML Javadoc API documentation site:

```bash
./mvnw -f backend/pom.xml javadoc:javadoc
```

The generated HTML documentation is located at:
```
backend/target/reports/apidocs/index.html
```
Open this file in any browser to inspect class hierarchies, record schemas, and method contracts.

---

## 3. Assumptions Made & Handling of Unexpected/Malformed Data

This submission follows the principle: **Be strict on rules (fail-fast), be resilient and transparent on customer transaction data (lenient with diagnostics).**

### A. Transactions (`transactions.json`)

1. **Mandatory Fields & Identity Precedence**:
   - `transaction_id`: Required string. Must be non-blank and unique within the file. Missing, empty (`""`), whitespace-only (`"   "`), or explicitly `null` (`"transaction_id": null`) IDs are strictly flagged.
   - **Identity Precedence Over Deserialization Exceptions**: `transaction_id` is the primary identity of each transaction. If `transaction_id` is null or missing, the loader diagnoses `transactions[index]: missing transaction_id` **even if downstream fields trigger low-level parser exceptions** (e.g. invalid timestamp strings such as `"2024-01-15T25:30:00Z"`). This prevents internal Jackson parsing errors from masking fundamental missing-identity violations.
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

5. **Batch Ingestion Resilience & Error-Level Logging**:
   - Corrupted or malformed transaction records are skipped and logged to `stderr` and `logs/app.log` at **`ERROR`** level with 1-based positional indicators (`transactions[N]: ...`) rather than crashing the entire batch, allowing all valid customer records to be evaluated.

---

### B. Rules (`rules.csv`)

1. **Strict Fail-Fast Gatekeeping**:
   - While transaction data is handled leniently, **rules are held to a strict zero-tolerance standard**. A corrupted, malformed, or contradictory rule would silently distort every transaction's compliance status. Therefore, if `rules.csv` contains any syntax error, unknown field, invalid operator, or invalid severity, the engine refuses to start, logs all offending lines to `stderr`, and exits immediately with code `1`.

### Compatibility Matrix: Rules (`rules.csv`) & Transactions (`transactions.json`)

> [!NOTE]
> **Monetary Precision Standard**: Monetary amounts strictly follow the two-decimal-place standard representation (`"amount": 15000.00`) mandated by `candidate-brief.md#L32`. For amounts with three or more decimal places, values are rounded off to the **highest two decimal places** (`RoundingMode.CEILING`).

| Transaction Field | Logical Type | Allowed Operators | Rejected Operators | Evaluation Behavior & Edge Cases |
|---|---|---|---|---|
| **`amount`** | Numeric (`number`) | `gt`, `lt`, `eq`, `neq` | `in` | **Works:** Evaluated via `BigDecimal.compareTo`. Amounts strictly use the standard two-decimal-place format (`15000.00`). If 3 or more decimal places are present, they round off to the highest two decimal places (`RoundingMode.CEILING`, e.g. `100.121` $\to$ `100.13`, `100.120` $\to$ `100.12`). Numeric equality matches seamlessly across representations (`15000` equals `15000.00`). Negative thresholds (`< 0`) and vacuous `lt 0` are rejected at load time.<br>**Doesn't Work:** `in` is strictly disallowed (`operator 'in' is not valid for field 'amount'`). |
| **`timestamp.hour`** | Integer (`hour`, 0–23 UTC) | `gt`, `lt`, `eq`, `neq`, `in` | *None* | **Works:** Compared as integer hour in UTC. Hour values are canonicalized at evaluation time, stripping leading zeros (`06` and `6` evaluate identically). Supports comma-separated sets via `in "01, 02, 06"`.<br>**Doesn't Work:** Vacuous conditions (`gt 23` and `lt 0`) are rejected at load time. |
| **`currency`** | String (ISO 4217) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Case-sensitive exact match. Transaction values are automatically trimmed (e.g. `" USD "` $\to$ `"USD"`). Missing currency on a transaction evaluates to `false` (no breach).<br>**Doesn't Work:** `gt`/`lt` are disallowed. Non-3-letter codes (`DOLLARS`, `US`) rejected at load time. |
| **`country`** | String (ISO 3166-1 alpha-2) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Case-sensitive 2-letter ISO match after trimming (e.g. `US`, `IR`, `KP`). Comma-separated watchlist matching via `in "IR, KP, SY"`. Missing country evaluates to `false`.<br>**Doesn't Work:** `gt`/`lt` are disallowed. Non-2-letter codes (`USA`, `INR`) are strictly rejected at load time per candidate brief. |
| **`customer_id`** | String (Identifier) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Exact string matching after trimming. Supports blacklists/whitelists via `in`. String IDs preserve verbatim text (e.g. `"010"` does not match `"10"`).<br>**Doesn't Work:** `gt`/`lt` are disallowed. |
| **`account_type`** | String (Identifier) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Case-sensitive identifier match after trimming (e.g. `BUSINESS`, `PERSONAL`).<br>**Doesn't Work:** `gt`/`lt` are disallowed. |
| **`transaction_type`** | String (Identifier) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Case-sensitive identifier match after trimming (e.g. `WIRE_TRANSFER`, `ACH`).<br>**Doesn't Work:** `gt`/`lt` are disallowed. |
| **`transaction_id`** | String (Identifier) | `eq`, `neq`, `in` | `gt`, `lt` | **Works:** Exact string match after trimming.<br>**Doesn't Work:** `gt`/`lt` are disallowed. |

#### Summary of Operational Rules & Boundaries:
- **What Works:**
  - **Two-Decimal-Place Format & Ceiling Rounding**: Monetary amounts strictly adhere to the two-decimal-place representation (e.g. `15000.00`). If $\ge 3$ decimal places are present, they round up to the next cent (`RoundingMode.CEILING`, e.g. `100.121` $\to$ `100.13`), ensuring accurate compliance triggers without sub-cent precision false negatives. Numerical comparisons via `BigDecimal.compareTo` ensure seamless matching regardless of format (e.g. `15000` breaches `eq 15000.00`).
  - **Whitespace Resilience**: All string fields in incoming customer transactions are trimmed (`.trim()`), preventing whitespace-induced false negatives (`"country": " US "` matches `US`).
  - **Hour Canonicalization**: UTC hours are parsed as integer components. Rules specifying `06` or `6` evaluate identically.
  - **Safe Optional Fields**: If a transaction omits an optional field (e.g. missing `country` or `currency`), rules testing that field evaluate to `false` (no breach) and log a warning to `stderr`, preventing false positive breaches.
- **What Doesn't Work:**
  - **Non-2-Letter Country Codes**: Strictly restricted to ISO 3166-1 alpha-2 (2 letters). Codes such as `USA` or `INR` are rejected fail-fast during rule loading and transaction validation.
  - **Case Invariance**: Text matches are strictly case-sensitive (`usd` will NOT match `USD`).
  - **Set Membership on Money**: `amount in "100, 200"` is rejected at load time; amounts must use relational thresholds (`gt`, `lt`) or equality (`eq`, `neq`).
  - **Relational Comparisons on Strings**: `country gt US` or `currency lt EUR` is rejected at load time.
  - **Impossible / Vacuous Rules**: Rules like `amount lt 0`, `timestamp.hour gt 23`, or `timestamp.hour lt 0` are rejected fail-fast during rule loading.
  - **Empty 'in' Items**: Lists with empty elements (e.g. `"IR,,SY"` or `"IR,"`) are rejected at load time.

3. **Rule Independence**:
   - Each rule condition is evaluated independently. A transaction breaches a rule whenever that rule's condition evaluates to `true`. Multiple breaches are collected and reported in strict ascending `rule_id` order.

---

## 4. What Would Be Done Next

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
6. **Decoupled Two-Tier Architecture & Independent Horizontal Scaling**:
   - *Current Implementation*: A single synchronous flow where Nuxt handles data presentation and triggers the local batch CLI to execute the evaluation loop.
   - *Target Production Architecture (Separation of Ingestion from Matching)*:
     - **Tier 1: Transaction & Rule Management Application (I/O-Bound)**:
       - Dedicated to user-facing operations, rule authoring, schema validation, manual reviews, and transaction CRUD.
       - Primarily I/O-bound (handling HTTP, form inputs, database queries); runs stably on 1–2 lightweight instances.
     - **Tier 2: Dedicated Compliance Matching Engine Fleet (CPU-Bound)**:
       - Completely isolated computation workers dedicated solely to high-throughput rule evaluation, temporal hour calculations, and numeric matching.
       - **Independent Horizontal Scalability**: When transaction volume surges, the worker fleet can be scaled dynamically to $N$ nodes (e.g. via Kubernetes HPA based on queue depth) without touching or redeploying the management tier.
     - **Fault Isolation & Blast Radius Reduction**:
       - If a matching worker crashes under high load or runs out of memory, **the management application remains 100% online and accessible**. Users can still browse reports, draft rules, and submit transactions.
       - Decoupled via an asynchronous durable message broker (e.g. Apache Kafka, RabbitMQ, or Amazon SQS); in-flight transactions buffer safely in the queue without data loss while worker pods self-heal and drain the backlog.
     - **Shared Validation & Domain Library (`compliance-core`) to Prevent Semantic Drift**:
       - *The Risk of Rule Drift*: Scaling the evaluation engine horizontally across $N$ independent worker nodes while decoupling the management application introduces the risk that the Management App accepts a rule that running worker nodes cannot parse or evaluate differently due to version mismatches (e.g. divergent ceiling rounding, missing-field null handling, or timestamp timezone parsing).
       - *Architectural Solution (`compliance-core.jar`)*: Extract the core rule validation engine, operator compatibility matrix, domain models, and evaluation logic (`RuleLoader`, `RuleEngine`, `Operator`, `Severity`, `Transaction`, `Rule`, and canonical field vocabulary) into a **single, shared, versioned library (`compliance-core.jar`)**. Both the Management App (for pre-flight validation) and all $N$ Worker nodes (for matching) import this identical artifact, guaranteeing 100% logic and schema parity across the distributed system.
       - *Contract Enforcement*: Enforced through Semantic Versioning (`MAJOR.MINOR.PATCH`), Consumer-Driven Contract testing (Pact / Spring Cloud Contract), and Schema Registry validation on all broker events.

7. **How the Two Apps Will Better Communicate With Each Other**:
   - **Current Implementation**: Nuxt 3 invokes the Java engine on demand via Node.js `child_process.execFile`. This provides pure process isolation and zero TCP port collisions, but incurs JVM startup overhead on each invocation.
   - **Target Communication Architectures**:
     - **Unix Domain Sockets (UDS / AF_UNIX)**: Run the Java engine as a warm background daemon communicating over a filesystem socket (`.compliance.sock`). Yields sub-millisecond responses and full HotSpot C2 JIT optimization with zero TCP port binding.
     - **gRPC / Protocol Buffers**: Strongly-typed schema contract (`compliance.proto`) with binary streaming for high-throughput batch evaluation and native backpressure.
     - **Event-Driven Broker (Kafka / RabbitMQ)**: Decouple the UI from matching workers via a durable message queue for elastic horizontal auto-scaling and resilient asynchronous processing.

---

## 5. Tools Used

- **Java 17 & Spring Boot 3.3.5**: Core rule engine, loaders, and headless CLI runner.
- **Nuxt 3 & Vue 3**: Reactive web dashboard, modal drawer inspector, and client-side validation.
- **Apache Commons CSV & Jackson (JavaTimeModule)**: Robust CSV and ISO-8601 JSON parsing.
- **JUnit 5**: Automated test suite (46 tests covering 75+ unit, edge-case, and profiling benchmark scenarios).
- **Grafana Pyroscope & Java Profiling Agent (`io.pyroscope:agent`)**: Low-overhead continuous CPU profiling and interactive flame graph visualization.
- **Google Antigravity**: AI assistant for iterative development, architecture design, edge-case analysis, and verification.
