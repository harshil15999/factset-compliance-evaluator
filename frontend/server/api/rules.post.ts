import { promises as fs, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { JAR, RULES, ROOT_RULES, runJava } from '../utils/backend'

// Quote a cell only when needed (comma, quote, newline).
const cell = (v: unknown) => {
  const s = String(v ?? '').trim()
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

// Next rule_id = max existing R<n> + 1, zero-padded to 3. rule_id is the first column and never
// quoted, so a plain split is safe. The jar's duplicate-rule_id check is the real guard against collisions.
function nextRuleId(csv: string): string {
  let max = 0
  for (const line of csv.split(/\r?\n/).slice(1)) {
    const m = /^R(\d+)/.exec(line.split(',')[0]?.trim() ?? '')
    if (m) max = Math.max(max, parseInt(m[1], 10))
  }
  return 'R' + String(max + 1).padStart(3, '0')
}

export default defineEventHandler(async (event) => {
  const b = await readBody(event)

  try {
    await fs.access(JAR)
  } catch {
    throw createError({ statusCode: 500, statusMessage: `Jar not found: ${JAR}. Run: mvn -o -q package -DskipTests`, message: `Jar not found: ${JAR}` })
  }

  const existing = await fs.readFile(RULES, 'utf8')
  const ruleId = nextRuleId(existing) // system-generated, not user input
  const row = [ruleId, b?.rule_name, b?.field, b?.operator, b?.value, b?.severity].map(cell).join(',')
  const candidate = existing + (existing.endsWith('\n') || existing === '' ? '' : '\n') + row + '\n'
  const dir = await fs.mkdtemp(join(tmpdir(), 'rules-'))
  const candPath = join(dir, 'rules.csv')
  try {
    await fs.writeFile(candPath, candidate)
    let res
    try {
      res = await runJava(['-jar', JAR, 'validate-rules', candPath])
    } catch (e: any) {
      throw createError({ statusCode: 500, statusMessage: e.message, message: e.message })
    }
    if (res.code !== 0) {
      const lines = (res.stdout + '\n' + res.stderr)
        .split('\n')
        .map((l) => l.trim())
        .map((l) => l.replace(/^(?:\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\s+)?(?:[A-Z]+\s+\S+\s+-\s+)/, ''))
        .filter((l) => {
          if (!l) return false
          if (/problem\(s\)\s+found/i.test(l)) return false
          if (/refusing to run/i.test(l)) return false
          if (/^OK:/i.test(l)) return false
          return true
        })
      const problems = Array.from(new Set(lines))
      return { ok: false, problems: problems.length ? problems : ['Rule validation failed'] }
    }
    // Atomic replace: stage next to the real file so rename stays on one filesystem.
    const staged = RULES + '.tmp'
    await fs.writeFile(staged, candidate)
    await fs.rename(staged, RULES)
    if (ROOT_RULES !== RULES && existsSync(ROOT_RULES)) {
      await fs.writeFile(ROOT_RULES, candidate, 'utf8')
    }
    return { ok: true, rule_id: ruleId }
  } finally {
    await fs.rm(dir, { recursive: true, force: true })
  }
})
