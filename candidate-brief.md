# Transaction Compliance Evaluator — Take-Home Brief

## The task

A customer has sent us two files exported from their systems: `transactions.json` and `rules.csv`.
Write a small program that reads them and prints which rules each transaction breaches, if any.

No domain knowledge is required. Everything you need is in the two files and this brief.

This is customer-supplied data; document how you handle anything unexpected.

## Expectations

- Use whichever language you are most comfortable with.
- Prioritize correct output and clear, well-structured code.
- Include unit tests. We will look at what you chose to test.
- In a follow-up session we will ask you to walk through your solution and extend it.

## Input files

Both files are included alongside this brief. The formats are described below; open the files for the
actual data.

### transactions.json

A JSON object with a `transactions` array. Each element is one transaction:

```json
{
  "transaction_id": "TXN001",
  "timestamp": "2024-01-15T23:30:00Z",
  "amount": 15000.00,
  "currency": "USD",
  "country": "US",
  "customer_id": "CUST001",
  "account_type": "PERSONAL",
  "transaction_type": "WIRE_TRANSFER"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `transaction_id` | string | Unique identifier |
| `timestamp` | string | ISO 8601 date and time |
| `amount` | number | Transaction amount |
| `currency` | string | ISO 4217 currency code, e.g. `USD` |
| `country` | string | ISO 3166 two-letter country code, e.g. `US` |
| `customer_id` | string | Customer identifier |
| `account_type` | string | e.g. `PERSONAL`, `BUSINESS` |
| `transaction_type` | string | e.g. `WIRE_TRANSFER`, `PAYMENT` |

### rules.csv

A CSV file with a header row. Each subsequent row is one rule:

```csv
rule_id,rule_name,field,operator,value,severity
R001,large_transaction,amount,gt,10000,HIGH
R002,high_risk_country,country,in,"IR,KP,SY",HIGH
```

| Column | Notes |
|--------|-------|
| `rule_id` | Unique identifier, e.g. `R001` |
| `rule_name` | Human-readable name |
| `field` | The transaction field to test, or `timestamp.hour` |
| `operator` | One of the operators in the [rules reference](#rules-reference) |
| `value` | The value to compare against. For `in`, a comma-separated list, quoted as a CSV field |
| `severity` | One of `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |

## Rules reference

Each row of `rules.csv` is one condition: the transaction's `field` is compared to `value` using
`operator`.

| Operator | Meaning |
|----------|---------|
| `gt` | Greater than (strict) |
| `lt` | Less than (strict) |
| `eq` | Equal to |
| `neq` | Not equal to |
| `in` | Value is in the comma-separated list |

- `timestamp.hour` is the hour component (0–23) of the transaction timestamp, **in UTC**.
- `amount` is numeric. Numeric operators compare as numbers.
- `eq`, `neq` and `in` are **case-sensitive** string comparisons.
- Each rule is independent. A transaction breaches a rule when the rule's condition is true for it.

## Output

Print one line per transaction, in the order the transactions appear in the file. List each
transaction's breached rules in ascending `rule_id` order, with the rule's severity in parentheses:

```
Transaction    Breached Rules
<transaction_id>    <rule_id> (<severity>), <rule_id> (<severity>), ...
```

A transaction that breaches no rules is listed with `none` in place of the rule list.

We will compare your output to a reference.

## Submission checklist

Send us your source code and a README that covers:

- [ ] How to run the program.
- [ ] How to run the tests.
- [ ] Assumptions you made, and how your program handles unexpected or malformed data.
- [ ] What you would do next with more time.
- [ ] Any tools you used, including AI assistants.
- [ ] How much time did it take to complete the test.
