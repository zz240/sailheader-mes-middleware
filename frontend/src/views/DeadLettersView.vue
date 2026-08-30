<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>死信</span>
        <div class="filters">
          <el-select v-model="filterJobId" clearable placeholder="按任务过滤" style="width: 180px">
            <el-option v-for="j in jobs" :key="j.id" :value="j.id" :label="`#${j.id} ${j.name}`" />
          </el-select>
          <el-select v-model="filterStatus" clearable placeholder="按状态过滤" style="width: 140px">
            <el-option label="FAILED" value="FAILED" />
            <el-option label="REDELIVERED" value="REDELIVERED" />
          </el-select>
          <el-button type="primary" @click="query">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </div>
    </template>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="jobId" label="任务" width="80" />
      <el-table-column prop="actionIndex" label="动作序号" width="90" />
      <el-table-column label="输入" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.inputJson }}</template>
      </el-table-column>
      <el-table-column label="错误" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.error }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'REDELIVERED' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="detail(row)">详情</el-button>
          <el-button size="small" type="primary" :disabled="row.status === 'REDELIVERED'" @click="redeliver(row)">
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

    <el-dialog v-model="detailVisible" title="死信详情" width="640px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="ID">{{ detailRow?.id }}</el-descriptions-item>
        <el-descriptions-item label="任务">{{ detailRow?.jobId }}</el-descriptions-item>
        <el-descriptions-item label="动作序号">{{ detailRow?.actionIndex }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detailRow?.status }}</el-descriptions-item>
        <el-descriptions-item label="输入">
          <pre class="pre">{{ detailRow?.inputJson }}</pre>
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
import type { DeadLetter, DeadLetterPage, Job } from '../types'

const list = ref<DeadLetter[]>([])
const jobs = ref<Job[]>([])
const filterJobId = ref<number | null>(null)
const filterStatus = ref('FAILED')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const detailVisible = ref(false)
const detailRow = ref<DeadLetter | null>(null)

async function load() {
  const params = new URLSearchParams()
  if (filterJobId.value) params.set('jobId', String(filterJobId.value))
  if (filterStatus.value) params.set('status', filterStatus.value)
  params.set('page', String(currentPage.value))
  params.set('size', String(pageSize.value))
  const qs = params.toString()
  const page = await api<DeadLetterPage>(`/dead-letters${qs ? '?' + qs : ''}`)
  list.value = page.records
  total.value = page.total
}

function query() {
  currentPage.value = 1
  load()
}

function reset() {
  filterJobId.value = null
  filterStatus.value = 'FAILED'
  currentPage.value = 1
  load()
}

function onSizeChange() {
  currentPage.value = 1
  load()
}

async function detail(row: DeadLetter) {
  detailRow.value = await api<DeadLetter>(`/dead-letters/${row.id}`)
  detailVisible.value = true
}

async function redeliver(row: DeadLetter) {
  await ElMessageBox.confirm(`重投死信 #${row.id} 到任务 ${row.jobId}？`, '确认')
  try {
    const r = await api<{ lastRunStatus: string; deadLetterStatus: string }>(`/dead-letters/${row.id}/redeliver`, { method: 'POST' })
    ElMessage.success(`重投完成：${r.lastRunStatus}，死信状态 ${r.deadLetterStatus}`)
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
