import { execFile } from 'node:child_process'
import { join, resolve } from 'node:path'
import { existsSync } from 'node:fs'

// Paths to the backend module (frontend runs from exam/frontend; backend is exam/backend).
const ROOT = existsSync(join(process.cwd(), 'backend')) ? process.cwd() : resolve(process.cwd(), '..')
export const BACKEND = join(ROOT, 'backend')
export const JAR = join(BACKEND, 'target/transaction-compliance-evaluator-0.1.0.jar')
export const DATA_DIR = join(ROOT, 'data')
export const TX = join(DATA_DIR, 'transactions.json')
export const RULES = join(DATA_DIR, 'rules.csv')
export const ROOT_TX = TX
export const ROOT_RULES = RULES

// Run the backend jar; resolves with its exit code, stdout, and stderr.
export function runJava(args: string[]): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((ok, fail) => {
    execFile('java', args, { cwd: ROOT }, (err: any, stdout, stderr) => {
      if (err && err.code === 'ENOENT') return fail(new Error('java not found on PATH'))
      ok({
        code: err ? (typeof err.code === 'number' ? err.code : 1) : 0,
        stdout: String(stdout || ''),
        stderr: String(stderr || '')
      })
    })
  })
}
