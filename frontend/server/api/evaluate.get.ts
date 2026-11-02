import { promises as fs } from 'node:fs'
import { JAR, TX, RULES, runJava } from '../utils/backend'

interface BreachedRule {
  ruleId: string
  severity: string
  ruleName?: string
  field?: string
  operator?: string
  value?: string
}

interface EvaluationRow {
  transactionId: string
  timestamp?: string
  amount?: number
  currency?: string
  country?: string
  accountType?: string
  transactionType?: string
  customerId?: string
  breached: boolean
  rules: BreachedRule[]
  rawBreaches: string
  rawTransaction?: Record<string, any>
}

export default defineEventHandler(async () => {
  try {
    await fs.access(JAR)
  } catch {
    throw createError({ statusCode: 500, message: `Jar not found: ${JAR}. Run: mvn -o -q package -DskipTests` })
  }

  // Load raw transaction data to enrich table display with amount, currency, country, etc.
  let txMap: Record<string, any> = {}
  try {
    const rawTxJson = await fs.readFile(TX, 'utf8')
    const parsed = JSON.parse(rawTxJson)
    if (Array.isArray(parsed?.transactions)) {
      for (const t of parsed.transactions) {
        if (t?.transaction_id) {
          txMap[t.transaction_id] = t
        }
      }
    }
  } catch (e) {
    // If transactions.json cannot be read, still run the jar
  }

  // Load rules definitions to enrich breach explanations
  const rulesMap: Record<string, { ruleName: string; field: string; operator: string; value: string; severity: string }> = {}
  try {
    const rulesCsv = await fs.readFile(RULES, 'utf8')
    const ruleLines = rulesCsv.split(/\r?\n/).slice(1).filter(Boolean)
    for (const rLine of ruleLines) {
      // Basic CSV split for simple cells; values with quotes
      const cells: string[] = []
      let curr = ''
      let inQuotes = false
      for (let c = 0; c < rLine.length; c++) {
        const char = rLine[c]
        if (char === '"') {
          inQuotes = !inQuotes
        } else if (char === ',' && !inQuotes) {
          cells.push(curr.trim())
          curr = ''
        } else {
          curr += char
        }
      }
      cells.push(curr.trim())

      const rId = cells[0]
      if (rId) {
        rulesMap[rId] = {
          ruleName: cells[1] || '',
          field: cells[2] || '',
          operator: cells[3] || '',
          value: cells[4] || '',
          severity: cells[5] || ''
        }
      }
    }
  } catch (e) {
    // optional enrichment
  }

  const res = await runJava(['-jar', JAR, TX, RULES])
  if (res.code !== 0 && !res.stdout) {
    throw createError({ statusCode: 500, message: 'Compliance evaluation run failed' })
  }

  const lines = res.stdout.split(/\r?\n/).filter(Boolean)
  const rows: EvaluationRow[] = []
  let totalBreaches = 0
  let breachedCount = 0

  // Line 0 is the header: Transaction\tBreached Rules
  for (let i = 1; i < lines.length; i++) {
    const line = lines[i]
    const parts = line.split('\t')
    const txnId = parts[0]?.trim()
    const breachStr = parts.slice(1).join('\t').trim()
    if (!txnId) continue

    const hasBreaches = breachStr !== '' && breachStr.toLowerCase() !== 'none'
    const breachedRules: BreachedRule[] = []

    if (hasBreaches) {
      breachedCount++
      const items = breachStr.split(',').map((s) => s.trim()).filter(Boolean)
      for (const item of items) {
        const m = /^([^\s(]+)\s*\(([^)]+)\)/.exec(item)
        const rId = m ? m[1] : item
        const sev = m ? m[2] : 'UNKNOWN'
        const rDef = rulesMap[rId]

        breachedRules.push({
          ruleId: rId,
          severity: sev,
          ruleName: rDef?.ruleName,
          field: rDef?.field,
          operator: rDef?.operator,
          value: rDef?.value
        })
        totalBreaches++
      }
    }

    const tInfo = txMap[txnId] || {}
    rows.push({
      transactionId: txnId,
      timestamp: tInfo.timestamp,
      amount: tInfo.amount,
      currency: tInfo.currency,
      country: tInfo.country,
      accountType: tInfo.account_type,
      transactionType: tInfo.transaction_type,
      customerId: tInfo.customer_id,
      breached: hasBreaches,
      rules: breachedRules,
      rawBreaches: hasBreaches ? breachStr : 'none',
      rawTransaction: tInfo
    })
  }

  return {
    ok: true,
    totalTransactions: rows.length,
    breachedTransactions: breachedCount,
    compliantTransactions: rows.length - breachedCount,
    totalBreaches,
    rows,
    rawTsv: res.stdout
  }
})
