<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>任务与动作</span>
        <el-button type="primary" @click="openCreate">新建任务</el-button>
      </div>
    </template>

    <el-table :data="jobs" border stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近执行" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.lastRunStatus" :type="row.lastRunStatus === 'SUCCESS' ? 'success' : 'danger'" size="small">
            {{ row.lastRunStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastRunAt" label="最近时间" width="170" />
      <el-table-column label="操作" width="420" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="selectJob(row)">动作</el-button>
          <el-button size="small" @click="toggleStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
          <el-button size="small" @click="execute(row)">执行</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="jobFormVisible" :title="editingJob ? '编辑任务' : '新建任务'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="jobForm.name" /></el-form-item>
        <el-form-item label="站点 ID"><el-input-number v-model="jobForm.siteId" :min="1" /></el-form-item>
        <el-form-item label="水位策略">
          <el-select v-model="jobForm.watermarkType">
            <el-option label="自增列" value="SEQUENCE" />
            <el-option label="时间列" value="TIMESTAMP" />
          </el-select>
        </el-form-item>
        <el-form-item label="水位列"><el-input v-model="jobForm.watermarkColumn" placeholder="如 id / updated_at" /></el-form-item>
        <el-form-item label="cron"><el-input v-model="jobForm.crontab" placeholder="可选" /></el-form-item>
        <el-form-item label="重试次数"><el-input-number v-model="jobForm.retryCount" :min="0" /></el-form-item>
        <el-form-item label="退避(ms)"><el-input-number v-model="jobForm.backoffMs" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="jobFormVisible = false">取消</el-button>
        <el-button type="primary" @click="saveJob">保存</el-button>
      </template>
    </el-dialog>

    <el-divider />
    <div v-if="selectedJob">
      <h3>动作链：{{ selectedJob.name }}（#{{ selectedJob.id }}）</h3>
      <el-table :data="actions" border size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="index" label="序号" width="70" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="openActionEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="removeAction(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" @click="openActionCreate">添加动作</el-button>
      <el-button size="small" @click="validateSelected">校验任务</el-button>
      <el-button size="small" @click="loadReceived">刷新 MES 接收</el-button>
    </div>

    <el-dialog v-model="actionFormVisible" :title="editingAction ? '编辑动作' : '添加动作'" width="720px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="actionForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="actionForm.type" @change="actionTypeChanged">
            <el-option label="远程访问" value="REMOTE_ACCESS" />
            <el-option label="字段映射" value="FIELD_MAPPING" />
          </el-select>
        </el-form-item>
        <el-form-item label="序号"><el-input-number v-model="actionForm.index" :min="1" /></el-form-item>
        <el-form-item v-if="actionForm.type === 'REMOTE_ACCESS'" label="addition">
          <el-input v-model="actionAdditionText" type="textarea" :rows="4"
                    placeholder='MySQL 读 {"dataSourceId":1,"sql":"..."}；MySQL 写 {"dataSourceId":1,"tableName":"...","idempotencyKey":"..."}；HTTP 写 {"dataSourceId":1,"path":"/mock/...","method":"POST"}；MQ 消费 {"dataSourceId":1,"queue":"mes.to.edge"}' />
        </el-form-item>
        <el-form-item v-else label="映射规则">
          <MappingEditor ref="mappingEditorRef" :job-id="selectedJob!.id" @validate="validateSelected" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionFormVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAction">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="validateVisible" title="校验结果" width="640px">
      <el-alert v-if="validation" :type="validation.valid ? 'success' : 'error'" :closable="false"
                :title="validation.valid ? '校验通过' : `校验失败（${validation.errors.length} 个错误）`" />
      <div v-for="e in validation?.errors ?? []" :key="'e' + e.scope + e.field + e.message" class="issue error">
        [错误] {{ e.scope }}:{{ e.field }} {{ e.message }}
      </div>
      <div v-for="w in validation?.warnings ?? []" :key="'w' + w.scope + w.field + w.message" class="issue warn">
        [警告] {{ w.scope }}:{{ w.field }} {{ w.message }}
      </div>
    </el-dialog>

    <el-divider />
    <h3>mock MES 最近收到的数据</h3>
    <el-table :data="received" border size="small">
      <el-table-column type="index" width="60" />
      <el-table-column label="内容"><template #default="{ row }">{{ JSON.stringify(row) }}</template></el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { Action, Job, ValidationResult } from '../types'
import MappingEditor from '../components/MappingEditor.vue'

const jobs = ref<Job[]>([])
const selectedJob = ref<Job | null>(null)
const actions = ref<Action[]>([])
const received = ref<any[]>([])

const jobFormVisible = ref(false)
const editingJob = ref<Job | null>(null)
const jobForm = reactive({ name: '', siteId: 1, watermarkType: 'SEQUENCE', watermarkColumn: '', crontab: '', retryCount: 0, backoffMs: 1000 })

const actionFormVisible = ref(false)
const editingAction = ref<Action | null>(null)
const actionForm = reactive({ name: '', type: 'REMOTE_ACCESS' as string, index: 1 })
const actionAdditionText = ref('{}')
const mappingEditorRef = ref<InstanceType<typeof MappingEditor> | null>(null)

const validateVisible = ref(false)
const validation = ref<ValidationResult | null>(null)

async function load() {
  jobs.value = await api<Job[]>('/jobs')
  if (selectedJob.value) {
    const still = jobs.value.find(j => j.id === selectedJob.value!.id)
    selectedJob.value = still ?? null
    if (still) await loadActions()
  }
}

function openCreate() {
  editingJob.value = null
  Object.assign(jobForm, { name: '', siteId: 1, watermarkType: 'SEQUENCE', watermarkColumn: '', crontab: '', retryCount: 0, backoffMs: 1000 })
  jobFormVisible.value = true
}

function openEdit(row: Job) {
  editingJob.value = row
  Object.assign(jobForm, {
    name: row.name, siteId: row.siteId, watermarkType: row.watermarkType ?? 'SEQUENCE',
    watermarkColumn: row.watermarkColumn ?? '', crontab: row.crontab ?? '',
    retryCount: row.retryCount ?? 0, backoffMs: row.backoffMs ?? 1000,
  })
  jobFormVisible.value = true
}

async function saveJob() {
  const body = {
    name: jobForm.name, siteId: jobForm.siteId, status: editingJob.value?.status ?? 0,
    watermarkType: jobForm.watermarkType, watermarkColumn: jobForm.watermarkColumn || null,
    crontab: jobForm.crontab || null, retryCount: jobForm.retryCount, backoffMs: jobForm.backoffMs,
  }
  try {
    if (editingJob.value) await api(`/jobs/${editingJob.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api('/jobs', { method: 'POST', body: JSON.stringify(body) })
    jobFormVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function toggleStatus(row: Job) {
  await api(`/jobs/${row.id}/status`, { method: 'PUT', body: JSON.stringify({ status: row.status === 1 ? 0 : 1 }) })
  await load()
}

async function remove(row: Job) {
  await ElMessageBox.confirm(`删除任务 ${row.name}？`, '确认')
  await api(`/jobs/${row.id}`, { method: 'DELETE' })
  if (selectedJob.value?.id === row.id) selectedJob.value = null
  await load()
}

async function selectJob(row: Job) {
  selectedJob.value = row
  await loadActions()
}

async function loadActions() {
  if (!selectedJob.value) return
  actions.value = await api<Action[]>(`/jobs/${selectedJob.value.id}/actions`)
}

async function execute(row: Job) {
  try {
    const r = await api<Job>(`/jobs/${row.id}/execute`, { method: 'POST', body: '{}' })
    ElMessage.success(`执行完成：${r.lastRunStatus}${r.lastError ? ' ' + r.lastError : ''}`)
    await load()
    await loadReceived()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function openActionCreate() {
  editingAction.value = null
  Object.assign(actionForm, { name: '', type: 'REMOTE_ACCESS', index: actions.value.length + 1 })
  actionAdditionText.value = '{}'
  actionFormVisible.value = true
}

function openActionEdit(row: Action) {
  editingAction.value = row
  Object.assign(actionForm, { name: row.name, type: row.type, index: row.index })
  if (row.type === 'FIELD_MAPPING') {
    actionFormVisible.value = true
    setTimeout(() => mappingEditorRef.value?.setRules((row.addition?.rules as Record<string, any>[]) ?? []), 0)
  } else {
    actionAdditionText.value = JSON.stringify(row.addition || {}, null, 2)
    actionFormVisible.value = true
  }
}

function actionTypeChanged() {
  if (actionForm.type === 'FIELD_MAPPING') {
    setTimeout(() => mappingEditorRef.value?.setRules([]), 0)
  }
}

async function saveAction() {
  if (!selectedJob.value) return
  let body: Record<string, any>
  if (actionForm.type === 'FIELD_MAPPING') {
    body = {
      name: actionForm.name, type: 'FIELD_MAPPING', index: actionForm.index,
      addition: { rules: mappingEditorRef.value?.getRules() ?? [] },
    }
  } else {
    body = {
      name: actionForm.name, type: 'REMOTE_ACCESS', index: actionForm.index,
      addition: JSON.parse(actionAdditionText.value || '{}'),
    }
  }
  try {
    if (editingAction.value) await api(`/actions/${editingAction.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    else await api(`/jobs/${selectedJob.value.id}/actions`, { method: 'POST', body: JSON.stringify(body) })
    actionFormVisible.value = false
    await loadActions()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function removeAction(row: Action) {
  await api(`/actions/${row.id}`, { method: 'DELETE' })
  await loadActions()
}

async function validateSelected() {
  if (!selectedJob.value) return
  validation.value = await api<ValidationResult>(`/jobs/${selectedJob.value.id}/validate`, { method: 'POST' })
  validateVisible.value = true
}

async function loadReceived() {
  received.value = await fetch('/mock/mes/received').then(r => r.json())
}

onMounted(() => { load(); loadReceived() })
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; }
.issue { margin-top: 6px; font-size: 13px; white-space: pre-wrap; }
.issue.error { color: #dc2626; }
.issue.warn { color: #b45309; }
</style>
