import { promises as fs, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { JAR, TX, RULES, ROOT_TX, runJava } from '../utils/backend'

export default defineEventHandler(async (event) => {
  const b = await readBody(event)

  try {
    await fs.access(JAR)
  } catch {
    throw createError({ statusCode: 500, message: `Jar not found: ${JAR}. Run: mvn -o -q package -DskipTests` })
  }

  let candidateObj: { transactions: any[] } = { transactions: [] }

  if (b?.rawJson && typeof b.rawJson === 'string') {
    // Mode A: Raw JSON string uploaded/pasted
    try {
      const parsed = JSON.parse(b.rawJson)
      if (!parsed || !Array.isArray(parsed.transactions)) {
        return { ok: false, problems: ['JSON must contain a root "transactions" array'] }
      }
      candidateObj = parsed
    } catch (e: any) {
      return { ok: false, problems: ['Invalid JSON syntax: ' + e.message] }
    }
  } else if (Array.isArray(b?.transactions)) {
    // Mode B: Array of transactions
    candidateObj = { transactions: b.transactions }
  } else {
    // Mode C: Single transaction append
    let existingList: any[] = []
    try {
      const existingStr = await fs.readFile(TX, 'utf8')
      const parsed = JSON.parse(existingStr)
      if (Array.isArray(parsed.transactions)) {
        existingList = parsed.transactions
      }
    } catch (e) {
      existingList = []
    }

    const newTx: Record<string, any> = {
      transaction_id: b.transaction_id ? String(b.transaction_id).trim() : '',
      timestamp: b.timestamp ? String(b.timestamp).trim() : '',
      amount: b.amount !== undefined && b.amount !== '' ? Number(b.amount) : undefined
    }

    if (b.currency && String(b.currency).trim()) newTx.currency = String(b.currency).trim().toUpperCase()
    if (b.country && String(b.country).trim()) newTx.country = String(b.country).trim().toUpperCase()
    if (b.customer_id) newTx.customer_id = String(b.customer_id).trim()
    if (b.account_type) newTx.account_type = String(b.account_type).trim()
    if (b.transaction_type) newTx.transaction_type = String(b.transaction_type).trim()

    candidateObj = { transactions: [...existingList, newTx] }
  }

  const candidateContent = JSON.stringify(candidateObj, null, 2)
  const dir = await fs.mkdtemp(join(tmpdir(), 'tx-'))
  const candPath = join(dir, 'transactions.json')

  try {
    await fs.writeFile(candPath, candidateContent, 'utf8')
    let res
    try {
      res = await runJava(['-jar', JAR, candPath, RULES])
    } catch (e: any) {
      throw createError({ statusCode: 500, message: e.message })
    }

    if (res.code !== 0) {
      const problems = (res.stderr + '\n' + res.stdout)
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && (l.includes('ERROR') || l.includes('problem') || l.includes('Exception')))
      return {
        ok: false,
        problems: problems.length ? problems : ['Invalid transaction data']
      }
    }

    // Atomic replace
    const staged = TX + '.tmp'
    await fs.writeFile(staged, candidateContent, 'utf8')
    await fs.rename(staged, TX)
    if (ROOT_TX !== TX && existsSync(ROOT_TX)) {
      await fs.writeFile(ROOT_TX, candidateContent, 'utf8')
    }

    return {
      ok: true,
      transaction_id: b?.transaction_id || undefined,
      totalCount: candidateObj.transactions.length
    }
  } finally {
    await fs.rm(dir, { recursive: true, force: true })
  }
})

