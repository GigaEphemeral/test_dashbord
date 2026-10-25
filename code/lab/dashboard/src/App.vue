<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, fetchContainers, fetchHealth, fetchLogs } from './api'

const TOKEN_STORAGE_KEY = 'ha-lab-token'
const DEFAULT_TAIL = 200

const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) || 'lab-token')
const showToken = ref(false)
const containers = ref([])
const selected = ref('')
const tail = ref(DEFAULT_TAIL)
const logs = ref('')
const error = ref('')
const loadingContainers = ref(false)
const loadingLogs = ref(false)
const healthState = ref('checking')

watch(token, (value) => {
  localStorage.setItem(TOKEN_STORAGE_KEY, value)
})

const healthLabel = computed(() => {
  if (healthState.value === 'up') return '控制器在线'
  if (healthState.value === 'down') return '控制器离线'
  return '检测中…'
})

const healthPillClass = computed(() => {
  if (healthState.value === 'up') return 'ok'
  if (healthState.value === 'down') return 'down'
  return 'muted'
})

function describeError(err) {
  if (err instanceof ApiError) {
    if (err.status === 401) return '401 未授权：X-Lab-Token 不正确，修改令牌后重试。'
    if (err.status === 403) return `403 禁止访问：${err.message}`
    if (err.status === 404) return `404 未找到：${err.message}`
    if (err.status === 503) return `503 服务不可用：${err.message}`
    return `${err.status}：${err.message}`
  }
  return err?.message || '请求失败，请检查网络或控制器状态。'
}

function stateClass(state) {
  return state === 'running' ? 'ok' : 'warn'
}

function healthClass(health) {
  if (health === 'healthy') return 'ok'
  if (health === 'unhealthy') return 'down'
  return 'muted'
}

async function checkHealth() {
  try {
    const payload = await fetchHealth()
    healthState.value = payload?.status === 'UP' ? 'up' : 'down'
  } catch {
    healthState.value = 'down'
  }
}

async function refreshLogs() {
  if (!selected.value) return
  const value = Number(tail.value)
  if (!Number.isInteger(value) || value < 1 || value > 1000) {
    error.value = 'tail 必须是 1–1000 之间的整数。'
    return
  }
  error.value = ''
  loadingLogs.value = true
  try {
    logs.value = await fetchLogs(token.value.trim(), selected.value, value)
  } catch (err) {
    logs.value = ''
    error.value = describeError(err)
  } finally {
    loadingLogs.value = false
  }
}

async function refreshContainers() {
  error.value = ''
  loadingContainers.value = true
  try {
    const list = await fetchContainers(token.value.trim())
    containers.value = list
    if (!list.some((item) => item.name === selected.value)) {
      selected.value = list.find((item) => item.name === 'jl-app-1')?.name || list[0]?.name || ''
    }
    if (selected.value) {
      await refreshLogs()
    } else {
      logs.value = ''
    }
  } catch (err) {
    containers.value = []
    logs.value = ''
    error.value = describeError(err)
  } finally {
    loadingContainers.value = false
  }
}

async function reloadAll() {
  await checkHealth()
  await refreshContainers()
}

function selectContainer(name) {
  selected.value = name
  refreshLogs()
}

onMounted(reloadAll)
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">HA</div>
        <div>
          <h1>HA Lab · 实验操作台</h1>
          <p class="subtitle">M0 骨架 · 容器清单与日志（只读）</p>
        </div>
      </div>
      <div class="topbar-meta">
        <span class="pill" :class="healthPillClass">{{ healthLabel }}</span>
        <a class="link" href="/prom/" target="_blank" rel="noreferrer">Prometheus</a>
      </div>
    </header>

    <main class="content">
      <section class="card token-card">
        <div class="card-head">
          <h2>访问令牌</h2>
          <span class="hint">请求头 X-Lab-Token · 保存在浏览器 localStorage</span>
        </div>
        <div class="token-row">
          <input
            v-model="token"
            :type="showToken ? 'text' : 'password'"
            class="input token-input"
            autocomplete="off"
            spellcheck="false"
            aria-label="实验室访问令牌"
            @keyup.enter="reloadAll"
          />
          <button type="button" class="btn ghost" @click="showToken = !showToken">
            {{ showToken ? '隐藏' : '显示' }}
          </button>
          <button type="button" class="btn" :disabled="loadingContainers" @click="reloadAll">
            {{ loadingContainers ? '连接中…' : '连接 / 刷新' }}
          </button>
        </div>
      </section>

      <div v-if="error" class="error-banner" role="alert">{{ error }}</div>

      <div class="grid">
        <section class="card">
          <div class="card-head">
            <h2>容器清单 <span class="count">{{ containers.length }}</span></h2>
            <button type="button" class="btn ghost" :disabled="loadingContainers" @click="refreshContainers">
              {{ loadingContainers ? '加载中…' : '刷新' }}
            </button>
          </div>
          <p class="hint">仅显示 Docker 标签 <code>lab=java-learn</code> 的白名单容器。</p>
          <ul class="container-list">
            <li v-for="item in containers" :key="item.name">
              <button
                type="button"
                class="container-row"
                :class="{ selected: item.name === selected }"
                @click="selectContainer(item.name)"
              >
                <span class="container-name">{{ item.name }}</span>
                <span class="badges">
                  <span class="badge" :class="stateClass(item.state)">{{ item.state }}</span>
                  <span class="badge" :class="healthClass(item.health)">{{ item.health }}</span>
                </span>
              </button>
            </li>
            <li v-if="!containers.length" class="empty">暂无容器，请检查令牌或控制器状态。</li>
          </ul>
        </section>

        <section class="card logs-card">
          <div class="card-head">
            <h2>日志</h2>
            <div class="log-controls">
              <select v-model="selected" class="input select" aria-label="选择容器" @change="refreshLogs">
                <option value="" disabled>选择容器</option>
                <option v-for="item in containers" :key="item.name" :value="item.name">{{ item.name }}</option>
              </select>
              <label class="tail-label">
                tail
                <input v-model.number="tail" type="number" class="input tail-input" min="1" max="1000" />
              </label>
              <button type="button" class="btn" :disabled="loadingLogs || !selected" @click="refreshLogs">
                {{ loadingLogs ? '读取中…' : '刷新日志' }}
              </button>
            </div>
          </div>
          <pre class="logs">{{ logs || '（暂无日志，选择容器后刷新）' }}</pre>
          <p class="hint">只读 · tail 1–1000 · 经 Docker API 读取 stdout/stderr</p>
        </section>
      </div>
    </main>

    <footer class="footer">
      <span>业务入口 <a href="/api/hello" target="_blank" rel="noreferrer">/api/hello</a></span>
      <span>Grafana <a href="http://127.0.0.1:3000" target="_blank" rel="noreferrer">127.0.0.1:3000</a></span>
      <span>故障注入、暂停/复位与场景向导将在后续里程碑加入</span>
    </footer>
  </div>
</template>
