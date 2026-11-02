# Adding a rule

Written for anyone who edits `rules.csv` and is not a developer. A new rule is a new row in the CSV.
No code change, no rebuild of the rules logic.

Start from `rules-template.csv` (same header, two valid example rows). Copy a row into
`backend/src/main/resources/rules.csv` and edit it.

## Columns

Header, exactly: `rule_id,rule_name,field,operator,value,severity`

| Column | Required | Meaning |
|---|---|---|
| `rule_id` | yes | Unique id, e.g. `R009`. Breaches are listed in ascending `rule_id` by default. |
| `rule_name` | no | Short label, e.g. `large_transaction`. Blank is allowed, but fill it in. |
| `field` | yes | Which transaction field to test (see below). |
| `operator` | yes | How to compare (see below). |
| `value` | no* | What to compare against. *Blank is accepted by the loader, but every real rule needs one. |
| `severity` | yes | How serious a breach is (see below). |

Spaces around cells are trimmed. A row missing a required column is skipped with a warning when the
report runs, so always validate first (see below).

## Allowed values

Values are case-sensitive. Copy them exactly.

### `field`

`amount`, `currency`, `country`, `transaction_type`, `account_type`, `customer_id`,
`transaction_id`, `timestamp.hour`

`timestamp.hour` is the UTC hour of the transaction timestamp as a number, 0 to 23.

### `operator`

| Operator | Meaning |
|---|---|
| `gt` | field is greater than value. **Numeric only.** |
| `lt` | field is less than value. **Numeric only.** |
| `eq` | field equals value exactly (text, case-sensitive). |
| `neq` | field does not equal value (text, case-sensitive). |
| `in` | field equals any item in a comma-separated list. Put the list in quotes. |

For `in`, write the value as `"IR,KP,SY"`. Spaces around each item are ignored.

### `severity`

`LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (upper case).

## Worked examples

Flag amount over 10000:

```csv
R001,large_transaction,amount,gt,10000,HIGH
```

Flag transactions from a watchlist country:

```csv
R002,high_risk_country,country,in,"IR,KP,SY",HIGH
```

Flag activity in the 0-5 UTC hour. Hours run 0 to 23, so "less than 6" covers exactly 0 to 5:

```csv
R009,early_hours_transaction,timestamp.hour,lt,6,MEDIUM
```

## Check your edits before running

```
java -jar backend/target/transaction-compliance-evaluator-0.1.0.jar validate
```

or, with your own files (transactions first, then rules):

```
java -jar backend/target/transaction-compliance-evaluator-0.1.0.jar validate transactions.json rules.csv
```

With no file arguments it checks the bundled copies. Validate lists **every** problem it finds (unknown
field, bad operator or severity, missing required column, non-numeric `gt`/`lt` value, malformed
transaction), not just the first, and exits **non-zero** if there are any. It prints
`OK: inputs are valid` and exits 0 when clean.

## What you can't do without a developer

- **Invent a new field name.** `field` must be one of the list above. Those names are wired to the
  transaction data in code (`RuleEngine`).
- **Invent a new operator.** Only `gt`, `lt`, `eq`, `neq`, `in` exist (`Operator`).
- **Add a new severity.** Only the four above exist (`Severity`).
- **Do numeric comparison on text fields**, or text comparison with ranges. `gt`/`lt` on a non-numeric
  field never breaches (a warning is logged).
