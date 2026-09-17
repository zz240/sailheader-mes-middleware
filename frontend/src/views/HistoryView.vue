<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>消息历史</span>
        <div class="filters">
          <el-select v-model="filterJobId" clearable placeholder="按任务过滤" style="width: 180px">
            <el-option v-for="j in jobs" :key="j.id" :value="j.id" :label="`#${j.id} ${j.name}`" />
          </el-select>
          <el-select v-model="filterStatus" clearable placeholder="状态（消息步默认 FAILED）" style="width: 190px">
            <el-option label="FAILED" value="FAILED" />
            <el-option label="SUCCESS" value="SUCCESS" />
            <el-option label="REDELIVERED" value="REDELIVERED" />
          </el-select>
          <el-input v-model="filterBusinessKey" clearable placeholder="按业务键检索（消息步）" style="width: 200px" />
          <el-button type="primary" @click="query">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </div>
    </template>

    <el-radio-group v-model="mode" style="margin-bottom: 12px">
      <el-radio-button value="runs">按运行展开</el-radio-button>
      <el-radio-button value="steps">消息步检索</el-radio-button>
    </el-radio-group>

    <!-- 按运行展开 -->
    <el-table v-if="mode === 'runs'" :data="runs" border stripe row-key="id" @expand-change="onExpand">
      <el-table-column type="expand" width="40">
        <template #default="{ row }">
          <div class="run-detail">
            <div v-if="runLoading[row.id]" class="muted">加载消息步中…</div>
            <template v-else-if="grouped(runSteps[row.id]).length">
              <el-collapse v-for="g in grouped(runSteps[row.id])" :key="g.messageId" class="msg-collapse">
                <el-collapse-item>
                  <template #title>
                    <span class="msg-title">
                      <span class="mono">消息 {{ shortId(g.messageId) }}</span>
                      <el-tag v-if="g.businessKey" size="small" class="bk">{{ g.businessKey }}</el-tag>
                      <span class="muted mono">{{ g.messageId }}</span>
                      <el-button link type="primary" size="small" @click.stop="copyId(g.messageId)">复制</el-button>
                    </span>
                  </template>
                  <el-table :data="g.steps" size="small" border row-key="id" class="step-table">
                    <el-table-column type="expand" width="30">
                      <template #default="{ row: s }">
                        <div class="step-detail">
                          <div class="kv">
                            <div class="k">输入快照</div>
                            <VueJsonPretty :data="parseJson(s.inputSnapshot)" :deep="2" :show-length="false" />
                          </div>
                          <div class="kv">
                            <div class="k">数据快照</div>
                            <VueJsonPretty :data="parseJson(s.dataSnapshot)" :deep="2" :show-length="false" />
                          </div>
                          <div class="kv" v-if="s.error">
                            <div class="k">错误</div>
                            <pre class="err">{{ s.error }}</pre>
                          </div>
                        </div>
                      </template>
                    </el-table-column>
                    <el-table-column prop="step" label="步骤" width="70" />
                    <el-table-column prop="actionName" label="动作" min-width="110" />
                    <el-table-column label="状态" width="120">
                      <template #default="{ row }">
                        <el-tag :type="tagType(row.status)" size="small">{{ row.status }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="attempt" label="尝试次数" width="90" />
                    <el-table-column prop="createdAt" label="时间" min-width="160" />
                    <el-table-column label="错误" min-width="180" show-overflow-tooltip>
                      <template #default="{ row }">{{ row.error }}</template>
                    </el-table-column>
                    <el-table-column label="操作" width="90">
                      <template #default="{ row }">
                        <el-button size="small" type="primary" :disabled="row.status !== 'FAILED'" @click="redeliver(row)">
                          重投
                        </el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                </el-collapse-item>
              </el-collapse>
              <div v-if="!grouped(runSteps[row.id]).length" class="muted">该运行暂无消息步。</div>
            </template>
            <div v-else class="muted">该运行暂无消息步。</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="运行 ID" width="90" />
      <el-table-column prop="jobId" label="任务" width="80" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="startedAt" label="开始时间" min-width="170" />
      <el-table-column prop="finishedAt" label="结束时间" min-width="170" />
      <el-table-column label="错误" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.error }}</template>
      </el-table-column>
    </el-table>

    <!-- 消息步检索 -->
    <el-table v-else :data="steps" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="runId" label="运行" width="80" />
      <el-table-column label="消息" min-width="170">
        <template #default="{ row }">
          <span class="mono">{{ shortId(row.messageId) }}</span>
          <el-button link type="primary" size="small" @click="copyId(row.messageId)">复制</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="step" label="步骤" width="70" />
      <el-table-column prop="actionName" label="动作" min-width="110" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="businessKey" label="业务键" min-width="120" show-overflow-tooltip />
      <el-table-column label="错误" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.error }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" min-width="160" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openStep(row)">详情</el-button>
          <el-button size="small" type="primary" :disabled="row.status !== 'FAILED'" @click="redeliver(row)">
            重投
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      class="pager"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <!-- 消息步详情 -->
    <el-dialog v-model="detailVisible" title="消息步详情" width="760px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="ID">{{ detailRow?.id }}</el-descriptions-item>
        <el-descriptions-item label="运行">{{ detailRow?.runId }}</el-descriptions-item>
        <el-descriptions-item label="消息">
          <span class="mono">{{ detailRow?.messageId }}</span>
          <el-button link type="primary" size="small" @click="copyId(detailRow?.messageId ?? '')">复制</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="动作">{{ detailRow?.step }} · {{ detailRow?.actionName }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailRow?.status }}</el-descriptions-item>
        <el-descriptions-item label="业务键">{{ detailRow?.businessKey }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ detailRow?.attempt }}</el-descriptions-item>
        <el-descriptions-item label="输入快照">
          <VueJsonPretty :data="parseJson(detailRow?.inputSnapshot)" :deep="3" :show-length="false" />
        </el-descriptions-item>
        <el-descriptions-item label="数据快照">
          <VueJsonPretty :data="parseJson(detailRow?.dataSnapshot)" :deep="3" :show-length="false" />
        </el-descriptions-item>
        <el-descriptions-item label="错误">
          <pre class="err">{{ detailRow?.error }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import VueJsonPretty from 'vue-json-pretty'
import { api, ApiError } from '../api/client'
import type { Job, JobRun, MessageStep, Page } from '../types'

interface MessageGroup {
  messageId: string
  businessKey: string | null
  steps: MessageStep[]
}

const jobs = ref<Job[]>([])
const runs = ref<JobRun[]>([])
const steps = ref<MessageStep[]>([])
const mode = ref<'runs' | 'steps'>('runs')
const filterJobId = ref<number | null>(null)
const filterStatus = ref('')
const filterBusinessKey = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const detailVisible = ref(false)
const detailRow = ref<MessageStep | null>(null)

const runSteps = reactive<Record<number, MessageStep[]>>({})
const runLoading = reactive<Record<number, boolean>>({})

async function load() {
  const params = new URLSearchParams()
  if (filterJobId.value) params.set('jobId', String(filterJobId.value))
  if (mode.value === 'runs') {
    if (filterStatus.value) params.set('status', filterStatus.value) // 运行视图默认不过滤，选了才过滤
  } else {
    params.set('status', filterStatus.value || 'FAILED') // 消息步检索默认 FAILED（#74）
    if (filterBusinessKey.value) params.set('businessKey', filterBusinessKey.value)
  }
  params.set('page', String(currentPage.value))
  params.set('size', String(pageSize.value))
  const qs = params.toString()
  if (mode.value === 'runs') {
    const page = await api<Page<JobRun>>(`/history/runs${qs ? '?' + qs : ''}`)
    runs.value = page.records
    total.value = page.total
  } else {
    const page = await api<Page<MessageStep>>(`/history/steps${qs ? '?' + qs : ''}`)
    steps.value = page.records
    total.value = page.total
  }
}

function query() {
  currentPage.value = 1
  load()
}

function reset() {
  filterJobId.value = null
  filterStatus.value = ''
  filterBusinessKey.value = ''
  mode.value = 'runs'
  currentPage.value = 1
  load()
}

function onSizeChange() {
  currentPage.value = 1
  load()
}

function tagType(status: string) {
  if (status === 'REDELIVERED' || status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'info'
}

function shortId(id?: string | null) {
  return id && id.length > 10 ? `${id.slice(0, 8)}…` : id || '-'
}

function parseJson(raw?: string | null): any {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

function grouped(list?: MessageStep[]): MessageGroup[] {
  if (!list || !list.length) return []
  const map = new Map<string, MessageStep[]>()
  for (const s of list) {
    if (!map.has(s.messageId)) map.set(s.messageId, [])
    map.get(s.messageId)!.push(s)
  }
  return [...map.entries()].map(([messageId, list2]) => ({
    messageId,
    businessKey: list2[0]?.businessKey ?? null,
    steps: [...list2].sort((a, b) => (a.step ?? 0) - (b.step ?? 0)),
  }))
}

async function onExpand(row: JobRun, expandedRows: JobRun[]) {
  const expanded = expandedRows.some((r) => r.id === row.id)
  if (expanded && !runSteps[row.id]) {
    runLoading[row.id] = true
    try {
      const page = await api<Page<MessageStep>>(`/history/steps?runId=${row.id}&size=100`)
      runSteps[row.id] = page.records
    } finally {
      runLoading[row.id] = false
    }
  }
}

async function copyId(id: string) {
  try {
    await navigator.clipboard.writeText(id)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function openStep(row: MessageStep) {
  detailRow.value = await api<MessageStep>(`/history/steps/${row.id}`)
  detailVisible.value = true
}

async function redeliver(row: MessageStep) {
  await ElMessageBox.confirm(`从失败消息步 #${row.id}（${row.actionName}）断点续跑？`, '确认重投')
  try {
    const r = await api<{ status: string }>(`/history/steps/${row.id}/redeliver`, { method: 'POST' })
    ElMessage.success(`重投完成：消息步状态 ${r.status}`)
    // 刷新当前视图；runs 模式下同时刷新已展开运行的消息步缓存
    if (mode.value === 'runs' && row.runId) {
      const page = await api<Page<MessageStep>>(`/history/steps?runId=${row.runId}&size=100`)
      runSteps[row.runId] = page.records
    }
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(async () => {
  jobs.value = await api<Job[]>('/jobs')
  await load()
})
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; }
.filters { display: flex; gap: 8px; }
.pager { margin-top: 12px; justify-content: flex-end; }
.muted { color: var(--muted); }
.mono { font-family: var(--font-mono); }
.run-detail { padding: 8px 16px; }
.msg-collapse { margin-bottom: 8px; border: 1px solid var(--border); border-radius: var(--radius-lg); }
.msg-title { display: inline-flex; align-items: center; gap: 8px; }
.bk { margin-left: 4px; }
.step-table { margin: 8px 0 0; }
.step-detail { padding: 8px 4px; }
.kv { margin: 6px 0; }
.k { font-weight: 600; color: var(--muted); margin-bottom: 4px; }
.err { white-space: pre-wrap; word-break: break-all; margin: 0; color: var(--danger-ink); }
</style>
