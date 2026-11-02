import { promises as fs } from 'node:fs'
import { JAR, runJava } from '../utils/backend'

// Returns the backend's rule vocabulary (fields, operators, hour range, severities) verbatim, so the
// form is built from the backend's own definitions. No field/operator list lives in the frontend.
export default defineEventHandler(async () => {
  try {
    await fs.access(JAR)
  } catch {
    throw createError({ statusCode: 500, message: `Jar not found: ${JAR}. Run: mvn -o -q package -DskipTests` })
  }
  const res = await runJava(['-jar', JAR, 'schema'])
  if (res.code !== 0) {
    throw createError({ statusCode: 500, message: 'backend schema command failed' })
  }
  return JSON.parse(res.stdout)
})
