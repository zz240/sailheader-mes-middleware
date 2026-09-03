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
      <el-table-column label="站点" width="140">
        <template #default="{ row }">{{ siteName(row.siteId) }}</template>
      </el-table-column>
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

    <el-dialog v-model="jobFormVisible" :title="editingJob ? '编辑任务' : '新建任务'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="jobForm.name" /></el-form-item>
        <el-form-item label="站点">
          <el-select v-model="jobForm.siteId" placeholder="选择站点" style="width: 100%" @change="applySiteTimezone">
            <el-option v-for="s in sites" :key="s.id" :value="s.id" :label="`#${s.id} ${s.name}（${s.timezone || '默认时区'}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="jobForm.direction" placeholder="选择方向" style="width: 100%">
            <el-option label="北向（客户 → MES）" value="NORTH" />
            <el-option label="南向（MES → 客户）" value="SOUTH" />
          </el-select>
        </el-form-item>
        <el-form-item label="调度">
          <el-select v-model="jobForm.scheduleType" style="width: 100%">
            <el-option label="不自动" value="NONE" />
            <el-option label="每 N 分钟/小时" value="INTERVAL" />
            <el-option label="每天" value="DAILY" />
            <el-option label="每周" value="WEEKLY" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="jobForm.scheduleType === 'INTERVAL'" label="间隔">
          <el-input-number v-model="jobForm.intervalValue" :min="1" />
          <el-select v-model="jobForm.intervalUnit" style="width: 110px; margin-left: 8px">
            <el-option label="分钟" value="MINUTES" />
            <el-option label="小时" value="HOURS" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="jobForm.scheduleType === 'DAILY' || jobForm.scheduleType === 'WEEKLY'" label="执行时间">
          <el-time-picker v-model="jobForm.scheduleTime" value-format="HH:mm" format="HH:mm" placeholder="HH:MM" />
        </el-form-item>
        <el-form-item v-if="jobForm.scheduleType === 'WEEKLY'" label="周几">
          <el-select v-model="jobForm.scheduleWeekday" style="width: 100%">
            <el-option v-for="(name, i) in weekdays" :key="i + 1" :label="name" :value="i + 1" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="jobForm.scheduleType === 'DAILY' || jobForm.scheduleType === 'WEEKLY'" label="时区">
          <el-input :model-value="jobForm.timezone || '默认时区'" readonly />
        </el-form-item>
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
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="index" label="序号" width="70" />
        <el-table-column label="数据源" min-width="130">
          <template #default="{ row }">{{ dsName(row.addition?.dataSourceId) }}</template>
        </el-table-column>
        <el-table-column label="业务键" min-width="110">
          <template #default="{ row }">{{ row.addition?.businessKey ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="openActionEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="removeAction(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" @click="openActionCreate">添加动作</el-button>
      <el-button size="small" @click="validateSelected">校验配置</el-button>
      <el-button size="small" @click="loadReceived">刷新 MES 接收</el-button>
    </div>

    <el-dialog v-model="actionFormVisible" :title="editingAction ? '编辑动作' : '添加动作'" :width="actionDialogWidth">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="actionForm.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="actionForm.type" @change="resetKindFields" style="width: 100%">
            <el-option v-for="k in kinds" :key="k" :value="k" :label="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="序号"><el-input-number v-model="actionForm.index" :min="1" /></el-form-item>

        <template v-if="kindNeedsDataSource">
          <el-form-item label="数据源">
            <el-select v-model="kindForm.dataSourceId" placeholder="选择数据源" style="width: 100%">
              <el-option v-for="ds in filteredDataSources" :key="ds.id" :value="ds.id"
                         :label="`#${ds.id} ${ds.name}（${ds.type}）`" />
            </el-select>
          </el-form-item>
        </template>

        <template v-if="actionForm.type === 'READ_SQL'">
          <el-form-item label="SQL"><el-input v-model="kindForm.sql" type="textarea" :rows="3" placeholder="SELECT * FROM po_order WHERE {watermark}" /></el-form-item>
          <el-form-item label="水位策略">
            <el-select v-model="kindForm.watermarkType" style="width: 100%">
              <el-option label="SEQUENCE：增量（id，严格大于）" value="SEQUENCE" />
              <el-option label="TIMESTAMP：按时间刷量（含边界，幂等）" value="TIMESTAMP" />
            </el-select>
          </el-form-item>
          <el-form-item label="水位列"><el-input v-model="kindForm.watermarkColumn" placeholder="如 id / updated_at" /></el-form-item>
          <el-form-item label="批大小"><el-input-number v-model="kindForm.batchSize" :min="1" :max="10000" placeholder="每批读取行数（缺省 500）" /></el-form-item>
          <el-form-item label="业务键列"><el-input v-model="kindForm.businessKey" placeholder="如 order_no（按业务分组/检索）" /></el-form-item>
        </template>

        <template v-if="actionForm.type === 'WRITE_UPSERT'">
          <el-form-item label="表名"><el-input v-model="kindForm.tableName" /></el-form-item>
          <el-form-item label="幂等键"><el-input v-model="kindForm.idempotencyKey" placeholder="业务主键，如 order_no" /></el-form-item>
        </template>

        <template v-if="actionForm.type === 'HTTP_CALL'">
          <el-form-item label="路径"><el-input v-model="kindForm.path" placeholder="/mock/mes/purchase-order" /></el-form-item>
          <el-form-item label="方法">
            <el-select v-model="kindForm.method" style="width: 100%">
              <el-option label="POST" value="POST" />
              <el-option label="GET" value="GET" />
              <el-option label="PUT" value="PUT" />
            </el-select>
          </el-form-item>
        </template>

        <template v-if="actionForm.type === 'MQ_CONSUME'">
          <el-form-item label="队列"><el-input v-model="kindForm.queue" placeholder="mes.to.edge" /></el-form-item>
          <el-form-item label="exchange"><el-input v-model="kindForm.exchange" placeholder="可选" /></el-form-item>
          <el-form-item label="routingKey"><el-input v-model="kindForm.routingKey" placeholder="可选，缺省=队列" /></el-form-item>
          <el-form-item label="业务键路径"><el-input v-model="kindForm.businessKey" placeholder="如 data.orderNo（按业务分组/检索）" /></el-form-item>
        </template>

        <template v-if="actionForm.type === 'MQ_PUBLISH'">
          <el-form-item label="exchange"><el-input v-model="kindForm.exchange" placeholder="如 edge.notify（需绑定目标队列）" /></el-form-item>
          <el-form-item label="routingKey"><el-input v-model="kindForm.routingKey" placeholder="缺省=queue" /></el-form-item>
          <el-form-item label="queue"><el-input v-model="kindForm.queue" placeholder="可选，routingKey 缺省时用" /></el-form-item>
          <el-form-item label="body 步骤"><el-input-number v-model="kindForm.bodySourceStep" :min="0" placeholder="缺省=最近映射输出" /></el-form-item>
        </template>

        <el-form-item v-if="actionForm.type === 'FIELD_MAP'" label="映射规则">
          <MappingEditor ref="mappingEditorRef" :job-id="selectedJob!.id"
                         :source-steps="actionIndices" :previous-step="previousStep" @validate="validateSelected" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { Action, DataSource, Job, SiteInfo, ValidationResult } from '../types'
import MappingEditor from '../components/MappingEditor.vue'

const kinds = ['READ_SQL', 'WRITE_UPSERT', 'HTTP_CALL', 'MQ_CONSUME', 'MQ_PUBLISH', 'FIELD_MAP']

const jobs = ref<Job[]>([])
const sites = ref<SiteInfo[]>([])
const dataSources = ref<DataSource[]>([])
const selectedJob = ref<Job | null>(null)
const actions = ref<Action[]>([])
const received = ref<any[]>([])

const jobFormVisible = ref(false)
const editingJob = ref<Job | null>(null)
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const jobForm = reactive({
  name: '', siteId: 1, direction: 'NORTH' as 'NORTH' | 'SOUTH',
  scheduleType: 'NONE', intervalValue: 30, intervalUnit: 'MINUTES' as 'MINUTES' | 'HOURS',
  scheduleTime: '00:00', scheduleWeekday: 1, timezone: '',
  retryCount: 0, backoffMs: 1000,
})

const actionFormVisible = ref(false)
const editingAction = ref<Action | null>(null)
const actionForm = reactive({ name: '', type: 'READ_SQL' as string, index: 1 })
const kindForm = reactive<Record<string, any>>({})
const mappingEditorRef = ref<InstanceType<typeof MappingEditor> | null>(null)

const validateVisible = ref(false)
const validation = ref<ValidationResult | null>(null)

const kindNeedsDataSource = computed(() => actionForm.type !== 'FIELD_MAP')
/** 字段映射编辑区较大，弹窗加宽；其余动作保持常规宽度。 */
const actionDialogWidth = computed(() => (actionForm.type === 'FIELD_MAP' ? '1280px' : '720px'))

const filteredDataSources = computed(() => {
  const typeFilter: Record<string, string[]> = {
    READ_SQL: ['MYSQL'],
    WRITE_UPSERT: ['MYSQL'],
    HTTP_CALL: ['HTTP'],
    MQ_CONSUME: ['RABBITMQ'],
    MQ_PUBLISH: ['RABBITMQ'],
    FIELD_MAP: [],
  }
  const allowed = typeFilter[actionForm.type] ?? []
  return dataSources.value.filter(ds => allowed.includes(ds.type))
})

const actionIndices = computed(() => actions.value.map(a => a.index).sort((a, b) => a - b))
const previousStep = computed(() => {
  const idx = actionForm.index ?? 0
  return actionIndices.value.filter(i => i < idx).pop() ?? 0
})

async function load() {
  jobs.value = await api<Job[]>('/jobs')
  if (selectedJob.value) {
    const still = jobs.value.find(j => j.id === selectedJob.value!.id)
    selectedJob.value = still ?? null
    if (still) await loadActions()
  }
}

async function loadMeta() {
  sites.value = await api<SiteInfo[]>('/sites')
  dataSources.value = await api<DataSource[]>('/data-sources')
}

function siteName(id: number) {
  return sites.value.find(s => s.id === id)?.name ?? `#${id}`
}

function dsName(id: number | null | undefined) {
  if (!id) return '-'
  return dataSources.value.find(d => d.id === id)?.name ?? `#${id}`
}

function applySiteTimezone() {
  const site = sites.value.find(s => s.id === jobForm.siteId)
  jobForm.timezone = site?.timezone ?? ''
}

function openCreate() {
  editingJob.value = null
  const first = sites.value[0]
  Object.assign(jobForm, {
    name: '', siteId: first?.id ?? 1, direction: 'NORTH',
    scheduleType: 'NONE', intervalValue: 30, intervalUnit: 'MINUTES',
    scheduleTime: '00:00', scheduleWeekday: 1, timezone: first?.timezone ?? '',
    retryCount: 0, backoffMs: 1000,
  })
  jobFormVisible.value = true
}

function openEdit(row: Job) {
  editingJob.value = row
  const site = sites.value.find(s => s.id === row.siteId)
  Object.assign(jobForm, {
    name: row.name, siteId: row.siteId, direction: row.direction ?? 'NORTH',
    scheduleType: row.scheduleType ?? 'NONE',
    intervalValue: row.intervalValue ?? 30,
    intervalUnit: row.intervalUnit ?? 'MINUTES',
    scheduleTime: row.scheduleTime ?? '00:00',
    scheduleWeekday: row.scheduleWeekday ?? 1,
    timezone: row.timezone ?? site?.timezone ?? '',
    retryCount: row.retryCount ?? 0, backoffMs: row.backoffMs ?? 1000,
  })
  jobFormVisible.value = true
}

async function saveJob() {
  const isTimeBased = jobForm.scheduleType === 'DAILY' || jobForm.scheduleType === 'WEEKLY'
  const body = {
    name: jobForm.name, siteId: jobForm.siteId, status: editingJob.value?.status ?? 0,
    direction: jobForm.direction,
    scheduleType: jobForm.scheduleType || 'NONE',
    intervalValue: jobForm.scheduleType === 'INTERVAL' ? jobForm.intervalValue : null,
    intervalUnit: jobForm.scheduleType === 'INTERVAL' ? jobForm.intervalUnit : null,
    scheduleTime: isTimeBased ? jobForm.scheduleTime : null,
    scheduleWeekday: jobForm.scheduleType === 'WEEKLY' ? jobForm.scheduleWeekday : null,
    timezone: jobForm.timezone || null,
    retryCount: jobForm.retryCount, backoffMs: jobForm.backoffMs,
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

function resetKindFields() {
  Object.keys(kindForm).forEach(k => delete kindForm[k])
  kindForm.dataSourceId = filteredDataSources.value[0]?.id
}

function openActionCreate() {
  editingAction.value = null
  Object.assign(actionForm, { name: '', type: 'READ_SQL', index: actions.value.length + 1 })
  resetKindFields()
  actionFormVisible.value = true
}

function openActionEdit(row: Action) {
  editingAction.value = row
  Object.assign(actionForm, { name: row.name, type: row.type, index: row.index })
  resetKindFields()
  const a = row.addition ?? {}
  Object.keys(a).forEach(k => { kindForm[k] = a[k] })
  if (row.type === 'FIELD_MAP') {
    actionFormVisible.value = true
    setTimeout(() => mappingEditorRef.value?.setConfig(a), 0)
  } else {
    actionFormVisible.value = true
  }
}

function buildActionAddition(): Record<string, any> {
  if (actionForm.type === 'FIELD_MAP') {
    return mappingEditorRef.value?.getConfig() ?? { rules: [] }
  }
  const out: Record<string, any> = {}
  Object.entries(kindForm).forEach(([k, v]) => {
    if (v !== '' && v != null) out[k] = v
  })
  return out
}

async function saveAction() {
  if (!selectedJob.value) return
  let addition: Record<string, any>
  try {
    addition = buildActionAddition()
  } catch (e) {
    ElMessage.error((e as Error).message)
    return
  }
  const body = {
    name: actionForm.name, type: actionForm.type, index: actionForm.index,
    addition,
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

onMounted(() => { loadMeta(); load(); loadReceived() })
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; }
.issue { margin-top: 6px; font-size: 13px; white-space: pre-wrap; }
.issue.error { color: #dc2626; }
.issue.warn { color: #b45309; }
</style>
