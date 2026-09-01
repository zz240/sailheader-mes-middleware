<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>消息历史</span>
        <div class="filters">
          <el-radio-group v-model="mode" @change="query">
            <el-radio-button value="steps">消息步</el-radio-button>
            <el-radio-button value="runs">运行</el-radio-button>
          </el-radio-group>
          <el-select v-model="filterJobId" clearable placeholder="按任务过滤" style="width: 180px">
            <el-option v-for="j in jobs" :key="j.id" :value="j.id" :label="`#${j.id} ${j.name}`" />
          </el-select>
          <el-select v-model="filterStatus" clearable placeholder="按状态过滤" style="width: 140px">
            <el-option label="RUNNING" value="RUNNING" />
            <el-option label="FAILED" value="FAILED" />
            <el-option label="SUCCESS" value="SUCCESS" />
            <el-option label="REDELIVERED" value="REDELIVERED" />
          </el-select>
          <el-input v-model="filterBusinessKey" clearable placeholder="按业务键检索" style="width: 180px" />
          <el-button type="primary" @click="query">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </div>
    </template>

    <template v-if="mode === 'runs'">
      <el-table :data="runs" border stripe>
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
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="showRun(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template v-else>
      <el-table :data="steps" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="runId" label="运行" width="80" />
        <el-table-column prop="messageId" label="消息" min-width="130" show-overflow-tooltip />
        <el-table-column prop="step" label="动作序号" width="90" />
        <el-table-column prop="actionName" label="动作" min-width="120" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="tagType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="businessKey" label="业务键" min-width="120" show-overflow-tooltip />
        <el-table-column label="错误" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.error }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="detail(row)">详情</el-button>
            <el-button size="small" type="primary" :disabled="row.status !== 'FAILED'" @click="redeliver(row)">
              重投
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

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

    <el-dialog v-model="detailVisible" title="消息步详情" width="720px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="ID">{{ detailRow?.id }}</el-descriptions-item>
        <el-descriptions-item label="运行">{{ detailRow?.runId }}</el-descriptions-item>
        <el-descriptions-item label="消息">{{ detailRow?.messageId }}</el-descriptions-item>
        <el-descriptions-item label="动作序号">{{ detailRow?.step }}</el-descriptions-item>
        <el-descriptions-item label="动作">{{ detailRow?.actionName }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailRow?.status }}</el-descriptions-item>
        <el-descriptions-item label="业务键">{{ detailRow?.businessKey }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ detailRow?.attempt }}</el-descriptions-item>
        <el-descriptions-item label="输入快照">
          <pre class="pre">{{ detailRow?.inputSnapshot }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="数据快照">
          <pre class="pre">{{ detailRow?.dataSnapshot }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误">
          <pre class="pre">{{ detailRow?.error }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { JobRun, Job, MessageStep, Page } from '../types'

const jobs = ref<Job[]>([])
const runs = ref<JobRun[]>([])
const steps = ref<MessageStep[]>([])
const mode = ref<'runs' | 'steps'>('steps')
const filterJobId = ref<number | null>(null)
const filterStatus = ref('FAILED')
const filterBusinessKey = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const detailVisible = ref(false)
const detailRow = ref<MessageStep | null>(null)

async function load() {
  const params = new URLSearchParams()
  if (filterJobId.value) params.set('jobId', String(filterJobId.value))
  if (mode.value === 'runs') {
    if (filterStatus.value) params.set('status', filterStatus.value)
  } else {
    if (filterStatus.value) params.set('status', filterStatus.value)
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
  filterStatus.value = 'FAILED'
  filterBusinessKey.value = ''
  mode.value = 'steps'
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

async function showRun(row: JobRun) {
  currentPage.value = 1
  mode.value = 'steps'
  filterJobId.value = row.jobId
  // 切到该运行的消息步
  const params = new URLSearchParams()
  params.set('jobId', String(row.jobId))
  params.set('runId', String(row.id))
  params.set('status', filterStatus.value || '')
  params.set('page', '1')
  params.set('size', String(pageSize.value))
  const page = await api<Page<MessageStep>>(`/history/steps?${params.toString()}`)
  steps.value = page.records
  total.value = page.total
}

async function detail(row: MessageStep) {
  detailRow.value = await api<MessageStep>(`/history/steps/${row.id}`)
  detailVisible.value = true
}

async function redeliver(row: MessageStep) {
  await ElMessageBox.confirm(`从失败消息步 #${row.id}（${row.actionName}）断点续跑？`, '确认重投')
  try {
    const r = await api<{ status: string }>(`/history/steps/${row.id}/redeliver`, { method: 'POST' })
    ElMessage.success(`重投完成：消息步状态 ${r.status}`)
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
.pre { white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
