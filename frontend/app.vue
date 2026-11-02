<script setup lang="ts">
// Types
interface FieldSpec { name: string; type: string; operators: string[]; min?: number; max?: number }
interface Schema { fields: FieldSpec[]; severities: string[] }

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

interface EvaluationResult {
  ok: boolean
  totalTransactions: number
  breachedTransactions: number
  compliantTransactions: number
  totalBreaches: number
  rows: EvaluationRow[]
  rawTsv: string
}

// Navigation Tabs
const activeTab = ref<'report' | 'addRule' | 'addTx'>('report')

// 1. Schema & Rule Vocabulary (from backend jar)
const { data: schema, error: schemaError } = await useFetch<Schema>('/api/schema')
const fields = computed(() => schema.value?.fields ?? [])
const severities = computed(() => schema.value?.severities ?? [])

// 2. Evaluation Report State
const evalData = ref<EvaluationResult | null>(null)
const evalBusy = ref(false)
const evalError = ref('')
const filterMode = ref<'all' | 'breached' | 'compliant'>('all')
const searchQuery = ref('')
const showRawTsv = ref(false)
const tsvCopied = ref(false)

// Transaction Details Modal State
const selectedTx = ref<EvaluationRow | null>(null)
const jsonCopied = ref(false)

const currentTxIndex = computed(() => {
  if (!selectedTx.value || !filteredRows.value.length) return -1
  return filteredRows.value.findIndex((r) => r.transactionId === selectedTx.value?.transactionId)
})

function prevTx() {
  if (currentTxIndex.value > 0) {
    selectedTx.value = filteredRows.value[currentTxIndex.value - 1]
  }
}

function nextTx() {
  if (currentTxIndex.value >= 0 && currentTxIndex.value < filteredRows.value.length - 1) {
    selectedTx.value = filteredRows.value[currentTxIndex.value + 1]
  }
}

async function copyTxJson() {
  if (!selectedTx.value) return
  const data = selectedTx.value.rawTransaction || {
    transaction_id: selectedTx.value.transactionId,
    timestamp: selectedTx.value.timestamp,
    amount: selectedTx.value.amount,
    currency: selectedTx.value.currency,
    country: selectedTx.value.country,
    customer_id: selectedTx.value.customerId,
    account_type: selectedTx.value.accountType,
    transaction_type: selectedTx.value.transactionType
  }
  await navigator.clipboard.writeText(JSON.stringify(data, null, 2))
  jsonCopied.value = true
  setTimeout(() => { jsonCopied.value = false }, 2000)
}

function formatUtcHour(ts?: string) {
  if (!ts) return ''
  try {
    const d = new Date(ts)
    return `Hour ${d.getUTCHours()} UTC`
  } catch {
    return ''
  }
}

async function fetchReport() {
  evalBusy.value = true
  evalError.value = ''
  try {
    const data = await $fetch<EvaluationResult>('/api/evaluate')
    evalData.value = data
    // If selectedTx is still open, keep it in sync
    if (selectedTx.value) {
      const refreshed = data.rows.find((r) => r.transactionId === selectedTx.value?.transactionId)
      if (refreshed) selectedTx.value = refreshed
    }
  } catch (e: any) {
    evalError.value = e?.data?.message || e?.message || 'Failed to evaluate compliance report'
  } finally {
    evalBusy.value = false
  }
}

// Filtered rows for the report table
const filteredRows = computed(() => {
  if (!evalData.value?.rows) return []
  let list = evalData.value.rows

  if (filterMode.value === 'breached') {
    list = list.filter((r) => r.breached)
  } else if (filterMode.value === 'compliant') {
    list = list.filter((r) => !r.breached)
  }

  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    list = list.filter((r) =>
      r.transactionId.toLowerCase().includes(q) ||
      (r.country && r.country.toLowerCase().includes(q)) ||
      (r.currency && r.currency.toLowerCase().includes(q)) ||
      (r.customerId && r.customerId.toLowerCase().includes(q)) ||
      r.rules.some((rule) => rule.ruleId.toLowerCase().includes(q) || rule.severity.toLowerCase().includes(q))
    )
  }
  return list
})

async function copyRawTsv() {
  if (!evalData.value?.rawTsv) return
  await navigator.clipboard.writeText(evalData.value.rawTsv)
  tsvCopied.value = true
  setTimeout(() => { tsvCopied.value = false }, 2000)
}

// 3. Add Rule Form State
const ruleForm = reactive({ rule_name: '', field: '', operator: '', value: '', severity: '' })
const ruleSpec = computed(() => fields.value.find((f) => f.name === ruleForm.field))
const ruleOps = computed(() => ruleSpec.value?.operators ?? [])
const useHourSelect = computed(() => ruleSpec.value?.type === 'hour' && ruleForm.operator !== 'in')
const hourOptions = computed(() => {
  const s = ruleSpec.value
  if (!s || s.type !== 'hour') return []
  const min = s.min ?? 0, max = s.max ?? 23
  const hrs: number[] = []
  for (let h = min; h <= max; h++) hrs.push(h)
  if (ruleForm.operator === 'gt') return hrs.filter((h) => h < max)
  if (ruleForm.operator === 'lt') return hrs.filter((h) => h > min)
  return hrs
})

function defaultRuleValue() { return useHourSelect.value ? String(hourOptions.value[0]) : '' }

// Seed rule form once schema loads
if (fields.value.length) {
  ruleForm.field = fields.value[0].name
  ruleForm.operator = ruleOps.value[0] ?? ''
  ruleForm.severity = severities.value[0] ?? ''
  ruleForm.value = defaultRuleValue()
}

watch(() => ruleForm.field, () => {
  if (!ruleOps.value.includes(ruleForm.operator)) ruleForm.operator = ruleOps.value[0] ?? ''
  ruleForm.value = defaultRuleValue()
})
watch(() => ruleForm.operator, () => { ruleForm.value = defaultRuleValue() })

const ruleBusy = ref(false)
const addedRuleId = ref('')
const ruleProblems = ref<string[]>([])

async function submitRule() {
  ruleBusy.value = true
  addedRuleId.value = ''
  ruleProblems.value = []
  try {
    const r = await $fetch<{ ok: boolean; rule_id?: string; problems?: string[] }>('/api/rules', {
      method: 'POST',
      body: ruleForm
    })
    if (r.ok) {
      addedRuleId.value = r.rule_id ?? 'added'
      await fetchReport()
    } else {
      ruleProblems.value = r.problems ?? ['Rule rejected']
    }
  } catch (e: any) {
    ruleProblems.value = [e?.data?.message || e?.message || 'Rule submission failed']
  } finally {
    ruleBusy.value = false
  }
}

// 4. Ingest Transactions State (Batch Upload / JSON Paste)
const batchJson = ref('')
const txBusy = ref(false)
const addedTxId = ref('')
const txProblems = ref<string[]>([])

async function submitBatchTx() {
  txBusy.value = true
  addedTxId.value = ''
  txProblems.value = []
  try {
    const r = await $fetch<{ ok: boolean; totalCount?: number; problems?: string[] }>('/api/transactions', {
      method: 'POST',
      body: { rawJson: batchJson.value }
    })
    if (r.ok) {
      addedTxId.value = `Batch of ${r.totalCount} transactions`
      batchJson.value = ''
      await fetchReport()
    } else {
      txProblems.value = r.problems ?? ['Batch JSON rejected']
    }
  } catch (e: any) {
    txProblems.value = [e?.data?.message || e?.message || 'Batch upload failed']
  } finally {
    txBusy.value = false
  }
}

function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target?.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (e) => {
    batchJson.value = String(e.target?.result || '')
  }
  reader.readAsText(file)
}

// Initial evaluation load on page startup
await fetchReport()
</script>

<template>
  <div class="app-shell">
    <!-- Header -->
    <header class="app-header">
      <div class="header-inner">
        <div>
          <h1>Transaction Compliance Evaluator</h1>
          <p class="subtitle">Real-time AML rule evaluation, live reporting, and transaction auditing</p>
        </div>
        <div class="header-actions">
          <button class="btn btn-secondary" :disabled="evalBusy" @click="fetchReport">
            {{ evalBusy ? 'Evaluating...' : '⟳ Re-evaluate' }}
          </button>
        </div>
      </div>

      <!-- Navigation Tabs -->
      <nav class="nav-tabs">
        <button :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">
          📊 Compliance Report
          <span v-if="evalData" class="tab-badge">{{ evalData.totalTransactions }}</span>
        </button>
        <button :class="{ active: activeTab === 'addRule' }" @click="activeTab = 'addRule'">
          ➕ Add Compliance Rule
        </button>
        <button :class="{ active: activeTab === 'addTx' }" @click="activeTab = 'addTx'">
          💳 Ingest Transactions
        </button>
        <a href="http://localhost:4040" target="_blank" class="nav-flame-btn" title="Open Pyroscope Flame Graph Profiler (Port 4040)">
          🔥 Pyroscope Profiler ↗
        </a>
      </nav>
    </header>

    <main class="app-content">
      <!-- Error banner if schema failed -->
      <div v-if="schemaError" class="alert alert-error">
        <strong>Error:</strong> Could not load rule schema from backend jar. Is the jar built?
      </div>

      <!-- TAB 1: COMPLIANCE REPORT -->
      <section v-show="activeTab === 'report'" class="tab-pane">
        <!-- Summary Stats -->
        <div v-if="evalData" class="stats-row">
          <div class="stat-card">
            <span class="stat-label">Total Transactions</span>
            <span class="stat-val">{{ evalData.totalTransactions }}</span>
          </div>
          <div class="stat-card stat-breached">
            <span class="stat-label">Breached Transactions</span>
            <span class="stat-val">{{ evalData.breachedTransactions }}</span>
          </div>
          <div class="stat-card stat-compliant">
            <span class="stat-label">Compliant Transactions</span>
            <span class="stat-val">{{ evalData.compliantTransactions }}</span>
          </div>
          <div class="stat-card stat-total-breaches">
            <span class="stat-label">Total Rule Violations</span>
            <span class="stat-val">{{ evalData.totalBreaches }}</span>
          </div>
        </div>

        <!-- Controls Bar -->
        <div class="controls-bar">
          <div class="filter-group">
            <button :class="{ active: filterMode === 'all' }" @click="filterMode = 'all'">All</button>
            <button :class="{ active: filterMode === 'breached' }" @click="filterMode = 'breached'">Breached Only</button>
            <button :class="{ active: filterMode === 'compliant' }" @click="filterMode = 'compliant'">Compliant Only</button>
          </div>
          <div class="search-box">
            <input v-model="searchQuery" placeholder="Filter by ID, country, rule..." />
          </div>
          <div class="export-actions">
            <button class="btn btn-sm" @click="showRawTsv = !showRawTsv">
              {{ showRawTsv ? 'Hide Raw TSV' : 'View Raw TSV' }}
            </button>
            <button class="btn btn-sm btn-primary" @click="copyRawTsv">
              {{ tsvCopied ? 'Copied to Clipboard!' : '📋 Copy Raw TSV' }}
            </button>
          </div>
        </div>

        <!-- Raw TSV Monospace Box (Toggleable) -->
        <div v-if="showRawTsv && evalData" class="raw-tsv-card">
          <div class="raw-header">
            <span>Standard TSV Output (Matches Take-Home Spec)</span>
            <button class="btn btn-xs" @click="copyRawTsv">Copy</button>
          </div>
          <pre><code>{{ evalData.rawTsv }}</code></pre>
        </div>

        <!-- Compliance Table -->
        <div class="table-container">
          <table class="report-table">
            <thead>
              <tr>
                <th>Transaction ID</th>
                <th>Time (UTC)</th>
                <th>Amount</th>
                <th>Country</th>
                <th>Status</th>
                <th>Breached Rules</th>
                <th class="text-right">Action</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in filteredRows"
                :key="row.transactionId"
                :class="{ 'row-breached': row.breached, 'row-selected': selectedTx?.transactionId === row.transactionId }"
                @click="selectedTx = row"
              >
                <td class="cell-id">
                  <button class="txn-link-btn" title="View Transaction Details" @click.stop="selectedTx = row">
                    <span class="txn-icon">🔍</span>
                    <strong>{{ row.transactionId }}</strong>
                  </button>
                </td>
                <td>{{ row.timestamp || '—' }}</td>
                <td>{{ row.amount !== undefined ? Number(row.amount).toFixed(2) : '—' }} {{ row.currency || '' }}</td>
                <td><span class="country-pill">{{ row.country || '—' }}</span></td>
                <td>
                  <span v-if="row.breached" class="status-badge badge-breached">Breached</span>
                  <span v-else class="status-badge badge-clean">Compliant</span>
                </td>
                <td class="cell-breaches">
                  <template v-if="row.breached">
                    <span
                      v-for="rule in row.rules"
                      :key="rule.ruleId"
                      :class="['rule-pill', `sev-${rule.severity.toLowerCase()}`]"
                      :title="rule.ruleName ? `${rule.ruleName} (${rule.field} ${rule.operator} ${rule.value})` : ''"
                    >
                      {{ rule.ruleId }} ({{ rule.severity }})
                    </span>
                  </template>
                  <span v-else class="text-muted">none</span>
                </td>
                <td class="text-right">
                  <button class="btn btn-xs btn-outline" @click.stop="selectedTx = row">
                    Details ↗
                  </button>
                </td>
              </tr>
              <tr v-if="filteredRows.length === 0">
                <td colspan="7" class="cell-empty">No transactions found matching filter.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- TAB 2: ADD RULE -->
      <section v-show="activeTab === 'addRule'" class="tab-pane form-pane">
        <div class="panel-card">
          <h2>Add Compliance Rule</h2>
          <p class="panel-hint">Define a condition to breach. <code>rule_id</code> is assigned sequentially by the system.</p>

          <form @submit.prevent="submitRule">
            <fieldset class="form-section">
              <legend>1. Condition</legend>
              <label>
                Field
                <select v-model="ruleForm.field">
                  <option v-for="f in fields" :key="f.name" :value="f.name">{{ f.name }}</option>
                </select>
              </label>

              <label>
                Operator
                <select v-model="ruleForm.operator">
                  <option v-for="o in ruleOps" :key="o" :value="o">{{ o }}</option>
                </select>
              </label>

              <label v-if="useHourSelect">
                Hour of Day (UTC)
                <select v-model="ruleForm.value">
                  <option v-for="h in hourOptions" :key="h" :value="String(h)">{{ String(h).padStart(2, '0') }}:00 UTC</option>
                </select>
                <small>Matching timestamp hour (0–23 UTC).</small>
              </label>

              <label v-else-if="ruleSpec?.type === 'number'">
                Threshold Value (Number)
                <input v-model="ruleForm.value" type="number" step="any" placeholder="10000" required />
                <small>Transaction amount must be strictly greater/less than this number.</small>
              </label>

              <label v-else>
                Compare Value (String)
                <input v-model="ruleForm.value" placeholder="US or IR,KP,SY" required />
                <small>Exact text match. For "in" operator, use comma-separated values (e.g. <code>IR,KP,SY</code>).</small>
              </label>
            </fieldset>

            <fieldset class="form-section">
              <legend>2. Classification</legend>
              <label>
                Severity
                <select v-model="ruleForm.severity">
                  <option v-for="s in severities" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>

              <label>
                Rule Name (Optional label)
                <input v-model="ruleForm.rule_name" placeholder="large_transaction" />
                <small>Short descriptive identifier.</small>
              </label>
            </fieldset>

            <div class="live-preview">
              <span class="preview-label">Live CSV Row Preview:</span>
              <code>(auto),{{ ruleForm.rule_name || 'unnamed' }},{{ ruleForm.field }},{{ ruleForm.operator }},{{ ruleForm.value }},{{ ruleForm.severity }}</code>
            </div>

            <button class="btn btn-primary btn-block" :disabled="ruleBusy">
              {{ ruleBusy ? 'Validating & Saving...' : 'Add Rule to rules.csv' }}
            </button>
          </form>

          <div v-if="addedRuleId" class="alert alert-success">
            Rule <strong>{{ addedRuleId }}</strong> successfully validated and added!
          </div>

          <div v-if="ruleProblems.length" class="alert alert-error">
            <strong>Rule rejected:</strong>
            <ul>
              <li v-for="p in ruleProblems" :key="p">{{ p }}</li>
            </ul>
          </div>
        </div>
      </section>

      <!-- TAB 3: INGEST TRANSACTIONS -->
      <section v-show="activeTab === 'addTx'" class="tab-pane form-pane">
        <div class="panel-card">
          <h2>Ingest Transactions</h2>
          <p class="panel-hint">Transactions are validated against required schema rules before saving to <code>transactions.json</code>.</p>

          <!-- Batch Upload / JSON Paste Form -->
          <form @submit.prevent="submitBatchTx">
            <label>
              Upload <code>transactions.json</code> File
              <input type="file" accept=".json" @change="handleFileUpload" />
            </label>

            <label>
              Or Paste Full JSON Object
              <textarea v-model="batchJson" rows="10" placeholder='{"transactions": [ { "transaction_id": "TXN001", ... } ]}'></textarea>
              <small>Must contain a root <code>transactions</code> array.</small>
            </label>

            <button class="btn btn-primary btn-block" :disabled="txBusy || !batchJson.trim()">
              {{ txBusy ? 'Validating Batch...' : 'Upload & Replace transactions.json' }}
            </button>
          </form>

          <div v-if="addedTxId" class="alert alert-success">
            {{ addedTxId }} successfully validated and saved!
          </div>

          <div v-if="txProblems.length" class="alert alert-error">
            <strong>Validation failed:</strong>
            <ul>
              <li v-for="p in txProblems" :key="p">{{ p }}</li>
            </ul>
          </div>
        </div>
      </section>
    </main>

    <!-- TRANSACTION DETAIL MODAL / DRAWER -->
    <div v-if="selectedTx" class="modal-backdrop" @click.self="selectedTx = null">
      <div class="modal-dialog">
        <!-- Modal Header -->
        <div class="modal-header">
          <div class="modal-title-group">
            <span :class="['status-badge', selectedTx.breached ? 'badge-breached' : 'badge-clean']">
              {{ selectedTx.breached ? `${selectedTx.rules.length} Breach(es)` : 'Compliant' }}
            </span>
            <h3>Transaction {{ selectedTx.transactionId }}</h3>
          </div>
          <div class="modal-nav-actions">
            <button class="btn btn-xs btn-secondary" :disabled="currentTxIndex <= 0" @click="prevTx">◀ Prev</button>
            <span class="modal-index-label">{{ currentTxIndex + 1 }} / {{ filteredRows.length }}</span>
            <button class="btn btn-xs btn-secondary" :disabled="currentTxIndex >= filteredRows.length - 1" @click="nextTx">Next ▶</button>
            <button class="modal-close-btn" title="Close (ESC)" @click="selectedTx = null">✕</button>
          </div>
        </div>

        <!-- Modal Body -->
        <div class="modal-body">
          <!-- Key Fields Grid -->
          <div class="detail-grid">
            <div class="detail-cell">
              <span class="detail-label">Transaction ID</span>
              <span class="detail-value font-mono"><strong>{{ selectedTx.transactionId }}</strong></span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Amount & Currency</span>
              <span class="detail-value highlight-amount">
                {{ selectedTx.amount !== undefined ? Number(selectedTx.amount).toFixed(2) : '—' }}
                <span class="currency-tag">{{ selectedTx.currency || 'N/A' }}</span>
              </span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Country (ISO-3166)</span>
              <span class="detail-value">
                <span v-if="selectedTx.country" class="country-pill-lg">{{ selectedTx.country }}</span>
                <span v-else class="text-muted">Not specified</span>
              </span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Timestamp (UTC)</span>
              <span class="detail-value">
                {{ selectedTx.timestamp || '—' }}
                <small class="utc-hint">{{ formatUtcHour(selectedTx.timestamp) }}</small>
              </span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Customer ID</span>
              <span class="detail-value">{{ selectedTx.customerId || '—' }}</span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Account Type</span>
              <span class="detail-value">{{ selectedTx.accountType || '—' }}</span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Transaction Type</span>
              <span class="detail-value">{{ selectedTx.transactionType || '—' }}</span>
            </div>
            <div class="detail-cell">
              <span class="detail-label">Compliance Result</span>
              <span class="detail-value">
                <strong v-if="selectedTx.breached" class="text-danger">NON-COMPLIANT</strong>
                <strong v-else class="text-success">COMPLIANT (Clean)</strong>
              </span>
            </div>
          </div>

          <!-- Breached Rules Details -->
          <div class="modal-section">
            <h4>Breached Rules Evaluation</h4>
            <div v-if="selectedTx.breached" class="breach-cards-list">
              <div
                v-for="rule in selectedTx.rules"
                :key="rule.ruleId"
                :class="['breach-detail-card', `border-${rule.severity.toLowerCase()}`]"
              >
                <div class="breach-card-top">
                  <div class="breach-id-group">
                    <span :class="['rule-pill', `sev-${rule.severity.toLowerCase()}`]">
                      {{ rule.ruleId }} ({{ rule.severity }})
                    </span>
                    <strong v-if="rule.ruleName" class="rule-name-text">{{ rule.ruleName }}</strong>
                  </div>
                </div>

                <div v-if="rule.field" class="breach-condition-row">
                  <span class="cond-label">Condition:</span>
                  <code>{{ rule.field }} {{ rule.operator }} {{ rule.value }}</code>
                </div>
              </div>
            </div>
            <div v-else class="clean-banner">
              <span class="clean-icon">✓</span>
              <div>
                <strong>No compliance rules breached.</strong>
                <p>This transaction satisfies all active regulatory criteria in rules.csv.</p>
              </div>
            </div>
          </div>

          <!-- Raw JSON Viewer -->
          <div class="modal-section">
            <div class="raw-json-header">
              <h4>Raw JSON Object</h4>
              <button class="btn btn-xs btn-secondary" @click="copyTxJson">
                {{ jsonCopied ? 'Copied!' : '📋 Copy JSON' }}
              </button>
            </div>
            <pre class="raw-json-box"><code>{{ JSON.stringify(selectedTx.rawTransaction || {
              transaction_id: selectedTx.transactionId,
              timestamp: selectedTx.timestamp,
              amount: selectedTx.amount,
              currency: selectedTx.currency,
              country: selectedTx.country,
              customer_id: selectedTx.customerId,
              account_type: selectedTx.accountType,
              transaction_type: selectedTx.transactionType
            }, null, 2) }}</code></pre>
          </div>
        </div>

        <!-- Modal Footer -->
        <div class="modal-footer">
          <button class="btn btn-secondary" @click="selectedTx = null">Close Details</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: #f8fafc;
  color: #0f172a;
  font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.app-header {
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  padding: 1.25rem 2rem 0;
  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}

.header-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  max-width: 1400px;
  margin: 0 auto;
  flex-wrap: wrap;
  gap: 1rem;
}

h1 {
  font-size: 1.5rem;
  font-weight: 700;
  margin: 0;
  color: #0f172a;
}

.subtitle {
  margin: 0.25rem 0 0;
  color: #64748b;
  font-size: 0.875rem;
}

/* Nav Tabs */
.nav-tabs {
  display: flex;
  gap: 0.5rem;
  max-width: 1400px;
  margin: 1.25rem auto 0;
}

.nav-tabs button {
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  padding: 0.65rem 1.25rem;
  font: inherit;
  font-weight: 600;
  font-size: 0.9rem;
  color: #64748b;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 0.4rem;
  transition: all 0.15s;
}

.nav-tabs button:hover {
  color: #1e293b;
}

.nav-tabs button.active {
  color: #2563eb;
  border-bottom-color: #2563eb;
}

.tab-badge {
  background: #e2e8f0;
  color: #334155;
  padding: 0.15rem 0.45rem;
  border-radius: 9999px;
  font-size: 0.75rem;
}

.nav-flame-btn {
  margin-left: auto;
  text-decoration: none;
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #dc2626;
  font-weight: 600;
  font-size: 0.85rem;
  padding: 0.4rem 0.85rem;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  transition: all 0.15s;
}

.nav-flame-btn:hover {
  background: #fee2e2;
  border-color: #fca5a5;
}

/* Main Content */
.app-content {
  max-width: 1400px;
  margin: 1.75rem auto;
  padding: 0 1.5rem 3rem;
}

/* Stats Row */
.stats-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.stat-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 1.1rem;
  display: flex;
  flex-direction: column;
  box-shadow: 0 1px 2px rgba(0,0,0,0.02);
}

.stat-label {
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
  color: #64748b;
  letter-spacing: 0.05em;
}

.stat-val {
  font-size: 1.75rem;
  font-weight: 700;
  margin-top: 0.35rem;
  color: #0f172a;
}

.stat-breached .stat-val { color: #dc2626; }
.stat-compliant .stat-val { color: #16a34a; }
.stat-total-breaches .stat-val { color: #d97706; }

/* Controls Bar */
.controls-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.25rem;
  flex-wrap: wrap;
}

.filter-group {
  display: inline-flex;
  background: #e2e8f0;
  border-radius: 6px;
  padding: 2px;
}

.filter-group button {
  background: transparent;
  border: none;
  padding: 0.4rem 0.85rem;
  font: inherit;
  font-size: 0.825rem;
  font-weight: 600;
  color: #64748b;
  border-radius: 5px;
  cursor: pointer;
}

.filter-group button.active {
  background: #ffffff;
  color: #0f172a;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

.search-box input {
  padding: 0.45rem 0.85rem;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
  font-size: 0.875rem;
  width: 260px;
}

.export-actions {
  display: flex;
  gap: 0.5rem;
}

/* Raw TSV Box */
.raw-tsv-card {
  background: #0f172a;
  color: #f8fafc;
  border-radius: 8px;
  padding: 1rem;
  margin-bottom: 1.5rem;
}

.raw-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 0.8rem;
  color: #94a3b8;
  font-weight: 600;
  margin-bottom: 0.5rem;
}

.raw-tsv-card pre {
  margin: 0;
  font-family: ui-monospace, Menlo, Monaco, monospace;
  font-size: 0.85rem;
  white-space: pre-wrap;
  word-break: break-all;
}

/* Table */
.table-container {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow-x: auto;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}

.report-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 0.9rem;
}

.report-table th {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid #e2e8f0;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.report-table td {
  padding: 0.8rem 1rem;
  border-bottom: 1px solid #f1f5f9;
}

.report-table tbody tr {
  cursor: pointer;
  transition: background 0.1s;
}

.report-table tbody tr:hover td {
  background: #f8fafc;
}

.row-breached td {
  background: #fffcfc;
}

.row-selected td {
  background: #eff6ff !important;
}

.text-right { text-align: right; }

.txn-link-btn {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  padding: 0.25rem 0.6rem;
  border-radius: 6px;
  font: inherit;
  font-size: 0.85rem;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  cursor: pointer;
  transition: all 0.15s;
}

.txn-link-btn:hover {
  background: #dbeafe;
  border-color: #93c5fd;
}

.txn-icon {
  font-size: 0.75rem;
}

.status-badge {
  display: inline-block;
  padding: 0.2rem 0.55rem;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 700;
  text-transform: uppercase;
}

.badge-breached { background: #fee2e2; color: #b91c1c; }
.badge-clean { background: #dcfce7; color: #15803d; }

.country-pill {
  background: #f1f5f9;
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  font-weight: 600;
  font-size: 0.8rem;
}

.cell-breaches {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.rule-pill {
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  font-size: 0.8rem;
  font-weight: 600;
  font-family: ui-monospace, Menlo, monospace;
}

.sev-critical { background: #fee2e2; color: #991b1b; border: 1px solid #fecaca; }
.sev-high { background: #ffedd5; color: #c2410c; border: 1px solid #fed7aa; }
.sev-medium { background: #fef3c7; color: #b45309; border: 1px solid #fde68a; }
.sev-low { background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd; }

.cell-empty {
  text-align: center;
  color: #94a3b8;
  padding: 2rem;
}

/* Forms */
.form-pane {
  max-width: 680px;
  margin: 0 auto;
}

.panel-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 1.75rem;
  box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}

.panel-card h2 {
  margin: 0 0 0.25rem;
  font-size: 1.35rem;
}

.panel-hint {
  color: #64748b;
  font-size: 0.875rem;
  margin: 0 0 1.5rem;
}

form {
  display: grid;
  gap: 1.25rem;
}

.form-section {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 1rem;
  display: grid;
  gap: 0.85rem;
}

.form-section legend {
  font-size: 0.8rem;
  font-weight: 700;
  color: #475569;
  text-transform: uppercase;
  padding: 0 0.35rem;
}

label {
  display: grid;
  gap: 0.25rem;
  font-weight: 600;
  font-size: 0.875rem;
  color: #334155;
}

input, select, textarea {
  padding: 0.5rem 0.75rem;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
  font-size: 0.9rem;
  background: #ffffff;
}

small {
  color: #64748b;
  font-size: 0.75rem;
  font-weight: 400;
}

.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0.85rem; }
.grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 0.85rem; }


.live-preview {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  padding: 0.75rem;
}

.preview-label {
  display: block;
  font-size: 0.75rem;
  font-weight: 700;
  color: #64748b;
  text-transform: uppercase;
  margin-bottom: 0.25rem;
}

.live-preview code {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 0.85rem;
  color: #0f172a;
}

/* Buttons */
.btn {
  padding: 0.55rem 1rem;
  font: inherit;
  font-size: 0.875rem;
  font-weight: 600;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}

.btn-primary { background: #2563eb; color: #fff; }
.btn-primary:hover:not(:disabled) { background: #1d4ed8; }
.btn-secondary { background: #f1f5f9; color: #334155; border-color: #cbd5e1; }
.btn-secondary:hover:not(:disabled) { background: #e2e8f0; }
.btn-outline { background: #fff; color: #2563eb; border-color: #bfdbfe; }
.btn-outline:hover:not(:disabled) { background: #eff6ff; }
.btn-sm { padding: 0.4rem 0.75rem; font-size: 0.8rem; }
.btn-xs { padding: 0.25rem 0.5rem; font-size: 0.75rem; border-radius: 4px; }
.btn-block { width: 100%; padding: 0.7rem; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* Alerts */
.alert {
  padding: 0.85rem 1rem;
  border-radius: 6px;
  margin-top: 1rem;
  font-size: 0.875rem;
}

.alert-success { background: #dcfce7; border: 1px solid #bbf7d0; color: #166534; }
.alert-error { background: #fee2e2; border: 1px solid #fecaca; color: #991b1b; }
.alert-error ul { margin: 0.4rem 0 0 1.25rem; padding: 0; }

.text-danger { color: #dc2626; }
.text-success { color: #16a34a; }

/* MODAL / DRAWER */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(15, 23, 42, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
  padding: 1.5rem;
}

.modal-dialog {
  background: #ffffff;
  border-radius: 12px;
  max-width: 740px;
  width: 100%;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  animation: modalIn 0.15s ease-out;
}

@keyframes modalIn {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

.modal-header {
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #f8fafc;
}

.modal-title-group {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.modal-title-group h3 {
  margin: 0;
  font-size: 1.25rem;
}

.modal-nav-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.modal-index-label {
  font-size: 0.8rem;
  color: #64748b;
  font-weight: 600;
}

.modal-close-btn {
  background: transparent;
  border: none;
  font-size: 1.25rem;
  color: #64748b;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
}

.modal-close-btn:hover {
  background: #e2e8f0;
  color: #0f172a;
}

.modal-body {
  padding: 1.5rem;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1rem;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 1.1rem;
}

.detail-cell {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.detail-label {
  font-size: 0.75rem;
  text-transform: uppercase;
  color: #64748b;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.detail-value {
  font-size: 0.95rem;
  color: #0f172a;
}

.font-mono {
  font-family: ui-monospace, Menlo, monospace;
}

.highlight-amount {
  font-weight: 700;
  color: #0f172a;
}

.currency-tag {
  background: #e2e8f0;
  padding: 0.1rem 0.35rem;
  border-radius: 4px;
  font-size: 0.75rem;
  font-weight: 600;
  margin-left: 0.25rem;
}

.country-pill-lg {
  background: #e0f2fe;
  color: #0369a1;
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  font-weight: 700;
  font-size: 0.85rem;
}

.utc-hint {
  display: block;
  color: #64748b;
  font-size: 0.75rem;
}

.modal-section h4 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
  color: #1e293b;
}

.breach-cards-list {
  display: grid;
  gap: 0.75rem;
}

.breach-detail-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-left-width: 4px;
  border-radius: 6px;
  padding: 0.85rem 1rem;
}

.border-critical { border-left-color: #dc2626; background: #fffbfa; }
.border-high { border-left-color: #ea580c; background: #fffbfa; }
.border-medium { border-left-color: #d97706; background: #fffdfa; }
.border-low { border-left-color: #2563eb; background: #fafdff; }

.breach-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.breach-id-group {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.rule-name-text {
  font-size: 0.9rem;
  color: #1e293b;
}

.breach-condition-row {
  margin-top: 0.5rem;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.85rem;
}

.cond-label {
  color: #64748b;
  font-size: 0.8rem;
  font-weight: 600;
}

.breach-condition-row code {
  background: #f1f5f9;
  padding: 0.15rem 0.45rem;
  border-radius: 4px;
  font-family: ui-monospace, Menlo, monospace;
}

.clean-banner {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  padding: 1rem;
}

.clean-icon {
  background: #16a34a;
  color: #ffffff;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex-shrink: 0;
}

.clean-banner p {
  margin: 0.2rem 0 0;
  font-size: 0.85rem;
  color: #15803d;
}

.raw-json-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.5rem;
}

.raw-json-header h4 {
  margin: 0;
}

.raw-json-box {
  background: #0f172a;
  color: #f8fafc;
  padding: 1rem;
  border-radius: 6px;
  font-family: ui-monospace, Menlo, Monaco, monospace;
  font-size: 0.825rem;
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
}

.modal-footer {
  padding: 1rem 1.5rem;
  border-top: 1px solid #e2e8f0;
  display: flex;
  justify-content: flex-end;
  background: #f8fafc;
}
</style>
