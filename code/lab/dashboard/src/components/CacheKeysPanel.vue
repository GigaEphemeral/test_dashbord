<script setup>
import { computed, ref, watch } from 'vue'
import { describeError } from '../api'

/**
 * Stage2 B3-02 cache key browser. SCAN-only paging, single-key detail and the
 * two-scope experiment reset. The component is intentionally self-contained:
 * it talks to the app through the controller proxy (`/lab-api`) and is not
 * mounted here (the cache page owns placement).
 *
 * Destructive reset is opt-in and confirmed twice: the includeBusiness checkbox
 * only enables the button, and a browser confirm names the two business tables
 * that will be truncated/reset when it is checked.
 */
const props = defineProps({
  token: { type: String, default: '' },
  active: { type: Boolean, default: true }
})

const API_BASE = '/lab-api'
const DEFAULT_PREFIX = 'cache:product:'
const DEFAULT_LIMIT = 100

const instance = ref('app-1')
const seedCount = ref('')
const seedResult = ref(null)
const seeding = ref(false)

const prefix = ref(DEFAULT_PREFIX)
const limit = ref(DEFAULT_LIMIT)
const cursor = ref('')
const page = ref(null)
const loadingKeys = ref(false)

const selectedKey = ref('')
const detail = ref(null)
const detailError = ref('')
const loadingDetail = ref(false)

const includeBusiness = ref(false)
const resetResult = ref(null)
const resetting = ref(false)
const error = ref('')

const canNext = computed(() => Boolean(page.value?.cursor) && page.value.cursor !== '0')
const items = computed(() => page.value?.items || [])

function cacheBase() {
  return `/chaos/app/${encodeURIComponent(instance.value)}/lab/cache`
}

async function request(path, { method = 'GET', body } = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'X-Lab-Token': props.token,
      ...(body ? { 'Content-Type': 'application/json' } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  })
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // Proxy/HTML error body: fall through to the status message.
  }
  if (!response.ok || (payload && payload.code !== '0')) {
    const message = payload?.message || `HTTP ${response.status}`
    const err = new Error(message)
    err.status = response.status
    err.code = payload?.code
    throw err
  }
  return payload?.data ?? null
}

async function runSeed() {
  if (!props.token || seeding.value) return
  error.value = ''
  seeding.value = true
  try {
    const body = seedCount.value === '' ? {} : { count: Number(seedCount.value) }
    seedResult.value = await request(`${cacheBase()}/seed`, { method: 'POST', body })
  } catch (err) {
    seedResult.value = null
    error.value = `播种失败：${describeError(err)}`
  } finally {
    seeding.value = false
  }
}

async function loadKeys() {
  if (!props.token || loadingKeys.value) return
  error.value = ''
  loadingKeys.value = true
  try {
    const query = new URLSearchParams()
    if (prefix.value) query.set('prefix', prefix.value)
    if (cursor.value !== '') query.set('cursor', cursor.value)
    if (limit.value !== '') query.set('limit', String(limit.value))
    const suffix = query.toString() ? `?${query.toString()}` : ''
    page.value = await request(`${cacheBase()}/keys${suffix}`)
    cursor.value = page.value?.cursor || '0'
  } catch (err) {
    page.value = null
    error.value = `键列表加载失败：${describeError(err)}`
  } finally {
    loadingKeys.value = false
  }
}

async function nextPage() {
  if (!canNext.value) return
  cursor.value = page.value.cursor
  await loadKeys()
}

async function showKey(key) {
  if (!props.token) return
  selectedKey.value = key
  detail.value = null
  detailError.value = ''
  loadingDetail.value = true
  try {
    detail.value = await request(`${cacheBase()}/key`, { method: 'POST', body: { key } })
  } catch (err) {
    detailError.value = describeError(err)
  } finally {
    loadingDetail.value = false
  }
}

async function runReset() {
  if (!props.token || resetting.value) return
  if (includeBusiness.value) {
    const confirmed = window.confirm(
      '确认同时重置业务数据？这会 TRUNCATE order_order 并把 inventory_stock 恢复为确定性库存公式。'
    )
    if (!confirmed) return
  }
  error.value = ''
  resetting.value = true
  try {
    resetResult.value = await request(`${cacheBase()}/reset`, {
      method: 'POST',
      body: { includeBusiness: includeBusiness.value }
    })
    page.value = null
    detail.value = null
    selectedKey.value = ''
  } catch (err) {
    resetResult.value = null
    error.value = `重置失败：${describeError(err)}`
  } finally {
    resetting.value = false
  }
}

watch(
  () => [props.active, props.token],
  ([active]) => {
    if (active && props.token && !page.value) loadKeys()
  },
  { immediate: true }
)
</script>

<template>
  <section class="card cache-keys-panel" data-testid="cache-keys-panel">
    <div class="card-head">
      <div>
        <h2>缓存键浏览</h2>
        <p class="hint">
          Redis SCAN 分页（不使用 KEYS）；前缀白名单默认 <code>cache:product:</code>，游标为 Redis 原始游标。
        </p>
      </div>
    </div>

    <p v-if="error" class="error-line" role="alert">{{ error }}</p>

    <div class="keys-toolbar">
      <label>
        实例
        <select v-model="instance" class="input" data-testid="cache-keys-instance">
          <option value="app-1">app-1</option>
          <option value="app-2">app-2</option>
          <option value="app-3">app-3</option>
        </select>
      </label>
      <label>
        播种数量
        <input
          v-model="seedCount"
          class="input"
          type="number"
          min="1"
          placeholder="留空使用配置上限"
          data-testid="cache-seed-count"
        />
      </label>
      <button class="btn" :disabled="seeding || !token" data-testid="cache-seed-run" @click="runSeed">
        {{ seeding ? '播种中…' : '播种实验数据' }}
      </button>
      <span v-if="seedResult" class="hint">
        请求 {{ seedResult.requestedProducts }} · 新增 {{ seedResult.inserted }} · 已存在
        {{ seedResult.alreadyPresent }} · 区间 {{ seedResult.startId }}–{{ seedResult.endId }}
      </span>
    </div>

    <div class="keys-toolbar">
      <label>
        前缀
        <input v-model="prefix" class="input" data-testid="cache-keys-prefix" />
      </label>
      <label>
        每页数量
        <input
          v-model="limit"
          class="input"
          type="number"
          min="1"
          data-testid="cache-keys-limit"
        />
      </label>
      <label>
        游标
        <input v-model="cursor" class="input" placeholder="0" />
      </label>
      <button class="btn" :disabled="loadingKeys || !token" data-testid="cache-keys-load" @click="loadKeys">
        {{ loadingKeys ? '加载中…' : '加载' }}
      </button>
      <button class="btn ghost" :disabled="!canNext" data-testid="cache-keys-next" @click="nextPage">
        下一页
      </button>
      <span v-if="page" class="hint">
        本页 {{ page.scanned }} 个键 · 下一游标 <code>{{ page.cursor }}</code>
      </span>
    </div>

    <table v-if="page" class="data-table" data-testid="cache-keys-table">
      <thead>
        <tr>
          <th>key</th>
          <th>TTL (s)</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(item, index) in items"
          :key="item.key"
          :data-testid="`cache-key-row-${index}`"
          class="key-row"
          @click="showKey(item.key)"
        >
          <td><code>{{ item.key }}</code></td>
          <td>{{ item.ttlSeconds === -1 ? '无过期' : item.ttlSeconds }}</td>
        </tr>
        <tr v-if="!items.length">
          <td colspan="2" class="hint">该游标没有匹配的键。</td>
        </tr>
      </tbody>
    </table>

    <section v-if="selectedKey" class="key-detail" data-testid="cache-key-detail">
      <h3>单键详情</h3>
      <p><code>{{ selectedKey }}</code></p>
      <p v-if="loadingDetail" class="hint">加载中…</p>
      <p v-else-if="detailError" class="error-line">{{ detailError }}</p>
      <template v-else-if="detail">
        <dl>
          <dt>L2</dt>
          <dd>{{ detail.l2Present ? '命中' : '缺失' }} · TTL {{ detail.ttlSeconds ?? '—' }}s</dd>
          <dt>L1</dt>
          <dd>{{ detail.l1Present ? '存在（非计数 peek）' : '不存在' }}</dd>
          <dt>大小</dt>
          <dd>
            {{ detail.valueBytes }} / {{ detail.valueMaxBytes }} bytes
            <strong v-if="detail.truncated">（已截断）</strong>
          </dd>
        </dl>
        <pre v-if="detail.value">{{ detail.value }}</pre>
      </template>
    </section>

    <div class="reset-zone">
      <label class="reset-check">
        <input
          v-model="includeBusiness"
          type="checkbox"
          data-testid="cache-reset-include-business"
        />
        同时重置业务数据（TRUNCATE order_order，库存恢复确定性公式，破坏性）
      </label>
      <button
        class="btn danger"
        :disabled="resetting || !token"
        data-testid="cache-reset-run"
        @click="runReset"
      >
        {{ resetting ? '重置中…' : '重置实验缓存' }}
      </button>
      <span v-if="resetResult" class="hint">
        已删除 {{ resetResult.deletedKeys }} 个缓存键 · 业务数据{{ resetResult.includeBusiness ? '已重置' : '未触碰' }}
      </span>
    </div>
  </section>
</template>

<style scoped>
.cache-keys-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.keys-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
}

.keys-toolbar label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.key-row {
  cursor: pointer;
}

.key-detail {
  border: 1px solid var(--border, #2a2f3a);
  border-radius: 8px;
  padding: 12px;
}

.key-detail dl {
  display: grid;
  grid-template-columns: 60px 1fr;
  gap: 4px 12px;
  margin: 0 0 8px;
}

.key-detail pre {
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.reset-zone {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--border, #2a2f3a);
  padding-top: 12px;
}

.reset-check {
  display: flex;
  align-items: center;
  gap: 8px;
}

.error-line {
  color: #ff6b6b;
}

.hint {
  color: var(--muted, #8b93a1);
  font-size: 12px;
}
</style>
