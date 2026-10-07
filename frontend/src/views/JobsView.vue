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
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button size="small" @click="openActionEdit(row)">编辑</el-button>
            <!-- 字段映射的配置入口只有这一个：打开新版逐行编辑器（#107 起旧编辑器已删） -->
            <el-button
              v-if="row.type === 'FIELD_MAP'"
              size="small"
              type="primary"
              plain
              @click="openMappingWorkbench(row)"
            >编辑映射</el-button>
            <el-button size="small" type="danger" @click="removeAction(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" type="primary" @click="openActionCreate">添加动作</el-button>
      <el-button size="small" @click="validateSelected">校验配置</el-button>
      <el-button size="small" @click="loadReceived">刷新 MES 接收</el-button>
    </div>

    <el-dialog v-model="actionFormVisible" :title="editingAction ? '编辑动作' : '添加动作'" width="720px">
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
          <el-form-item label="exchange">
            <el-input v-model="kindForm.exchange" placeholder="可选，缺省=数据源名" />
          </el-form-item>
          <el-form-item label="exchangeType">
            <el-select v-model="kindForm.exchangeType" style="width: 100%">
              <el-option label="direct（缺省，直连队列 rk）" value="direct" />
              <el-option label="topic（routingKey 为模式串）" value="topic" />
              <el-option label="fanout（广播，routingKey 留空）" value="fanout" />
              <el-option label="headers" value="headers" />
            </el-select>
          </el-form-item>
          <el-form-item label="routingKey">
            <el-input v-model="kindForm.routingKey" placeholder="binding key：topic 用模式串（如 order.#），fanout 留空；缺省=队列" />
          </el-form-item>
          <el-form-item label="业务键路径"><el-input v-model="kindForm.businessKey" placeholder="如 data.orderNo（按业务分组/检索）" /></el-form-item>
        </template>

        <template v-if="actionForm.type === 'ONE_TO_MANY'">
          <el-form-item label="档位">
            <el-select v-model="kindForm.mode" style="width: 100%" @change="onModeChange">
              <el-option label="拆分：一条消息摊成 N 条新消息" value="split" />
              <el-option label="还原：把出站拿回的响应还回原来那几条消息" value="restore" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源步骤">
            <el-input-number v-model="kindForm.sourceStep" :min="0"
              :placeholder="kindForm.mode === 'restore'
                ? '必填：回应在哪个动作的输出里（通常就是出站那一步的序号）'
                : '留空 = 拆当前消息体；填了则取该步骤的输出'" />
          </el-form-item>
          <el-form-item label="来源路径">
            <el-input v-model="kindForm.sourcePath"
              :placeholder="kindForm.mode === 'restore'
                ? '留空 = 来源本身就得是数组；如 results / data.items'
                : '留空 = 来源本身就得是数组；如 lines / data.items'" />
          </el-form-item>
          <template v-if="kindForm.mode === 'split'">
            <el-form-item label="元素键">
              <el-input v-model="kindForm.elementKey" placeholder="必填：子消息的业务键取自元素里的哪个字段，如 itemCode" />
            </el-form-item>
            <el-form-item label="保留外层字段">
              <el-switch v-model="kindForm.keepParent" />
              <span style="margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px">
                父消息的字段是否摊平进子消息（只影响子消息内容，不影响谱系）
              </span>
            </el-form-item>
          </template>
          <template v-else>
            <el-form-item label="响应配对字段">
              <el-input v-model="kindForm.resultKeyField" placeholder="必填：响应每一项里这个字段与消息的业务键相等就算配上，如 FCode" />
            </el-form-item>
            <el-form-item label="结果落进 body 的键">
              <el-input v-model="kindForm.attachAs" placeholder="必填：配对到的那一份写进各自 body 的这个键，紧随其后的动作直接用，如 registration" />
            </el-form-item>
          </template>
          <el-alert type="info" :closable="false" show-icon
            :title="kindForm.mode === 'restore'
              ? '还原是「回到原来那几条消息」，不是新造消息'
              : '拆分是「造新消息」：一条消息摊成 N 条'"
            :description="kindForm.mode === 'restore'
              ? '消息标识 / 业务键 / 父消息标识 / 批内序号都沿用合并那一批的，批号也不换。有一条配不上就整批失败（同成同败）。它必须挂在上游有合并动作的链上；没有响应的出口（MQ 发布）就不摆这一档。'
              : '子消息是新造的消息（新标识、新批号），父消息标识指向被拆的那条（谱系）。来源取不到就当场失败，不静默拆出 0 条。'" />
        </template>

        <template v-if="actionForm.type === 'AGGREGATE'">
          <el-form-item label="请求体字段名">
            <el-input v-model="kindForm.bodyField" placeholder="必填：整批包成 {&quot;字段名&quot;: [ … 每条消息自己那一片 … ]}，如 codes" />
          </el-form-item>
          <el-alert type="info" :closable="false" show-icon
            title="合并只打包，发去哪儿由后面的出站动作决定"
            description="攒齐的边界是上游读取批读完那一刻（不做条数阈值：要限制条数就调读库那一步的批大小）。打包完链上只有一条消息，后面的 HTTP_CALL / MQ_PUBLISH 因此只发一次；响应由「还原」那一档摊回原来那几条消息。" />
        </template>

        <template v-if="actionForm.type === 'MQ_PUBLISH'">
          <el-form-item label="exchange"><el-input v-model="kindForm.exchange" placeholder="如 edge.notify（需绑定目标队列）" /></el-form-item>
          <el-form-item label="routingKey"><el-input v-model="kindForm.routingKey" placeholder="缺省=queue" /></el-form-item>
          <el-form-item label="queue"><el-input v-model="kindForm.queue" placeholder="可选，routingKey 缺省时用" /></el-form-item>
          <el-form-item label="body 步骤"><el-input-number v-model="kindForm.bodySourceStep" :min="1" placeholder="必填：发布哪一步输出（通常 FIELD_MAP 序号）" /></el-form-item>
        </template>

        <!-- 字段映射弹窗里没有可编辑的配置：映射整份在全屏编辑器里配（#107）。两句提示按
             新建 / 已有分开说，因为两边的保存各自做什么不一样（见 `buildActionAddition`）。 -->
        <el-alert
          v-if="actionForm.type === 'FIELD_MAP'"
          type="info"
          :closable="false"
          show-icon
          :title="editingAction ? '改名称 / 序号顺便保存映射' : '字段映射整份在编辑器里配'"
          :description="editingAction
            ? '点「保存」会把名称、序号与这份映射一起写回去。'
            : '点「去配字段映射」打开编辑器，保存时把动作与这份映射一起建出来；直接点「保存」建不出来。'"
        />
      </el-form>
      <template #footer>
        <el-button @click="actionFormVisible = false">取消</el-button>
        <!-- 只有新建才走「去配字段映射」：已有的动作用「保存」就够了，再开一次会拿空草稿盖掉它 -->
        <el-button v-if="actionForm.type === 'FIELD_MAP' && !editingAction"
                  type="primary" plain @click="openMappingWorkbenchForDraft">
          去配字段映射
        </el-button>
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

    <!-- 字段映射编辑器（全屏抽屉）：动作表上「编辑映射」的唯一出口，新建 FIELD_MAP 也从这里进
         （弹窗里的「去配字段映射」）。`workbenchTarget` 收拢了「已有动作 / 还没建的动作」两种情况。 -->
    <FieldMappingWorkbench
      v-if="workbenchOpen && selectedJob"
      :key="`wb-${workbenchTarget.actionId ?? 'new'}-${workbenchTarget.actionIndex ?? ''}`"
      :job-id="selectedJob.id"
      :job-name="selectedJob.name"
      :action-id="workbenchTarget.actionId"
      :action-index="workbenchTarget.actionIndex"
      :action-name="workbenchTarget.actionName"
      :config="workbenchConfig"
      @saved="onWorkbenchSaved"
      @closed="workbenchOpen = false"
    />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { Action, DataSource, Job, SiteInfo, ValidationResult } from '../types'
import FieldMappingWorkbench from '../components/FieldMappingWorkbench.vue'

const kinds = ['READ_SQL', 'WRITE_UPSERT', 'HTTP_CALL', 'MQ_CONSUME', 'MQ_PUBLISH', 'FIELD_MAP', 'ONE_TO_MANY', 'AGGREGATE']

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

const validateVisible = ref(false)
const validation = ref<ValidationResult | null>(null)

/**
 * 字段映射编辑器（全屏抽屉）的入口状态：动作表上的「编辑映射」。
 *
 * 两种情况：`workbenchAction` 有值 = 改已有动作的那份映射；为 null = 这个动作**还没建**
 * （从「添加动作」里选 FIELD_MAP 进来的，`workbenchDraftAction` 装着它准备占用的名字与序号）。
 * 为什么会后者：后端「字段映射动作需要至少一条映射规则」是保存期校验，空规则的动作存不下去，
 * 所以创建这一步只能与那份草稿一起完成（细节见 `buildActionAddition`）。
 */
const workbenchOpen = ref(false)
const workbenchAction = ref<Action | null>(null)
const workbenchConfig = ref<Record<string, any> | null>(null)
const workbenchDraftAction = ref<Partial<Action> | null>(null)

/** 抽屉打开时那份身份（`key` 与三个 prop 都从这一份来）：已有动作用它自己，新建用弹窗里刚填的。 */
const workbenchTarget = computed(() => ({
  actionId: workbenchAction.value?.id ?? null,
  actionIndex: workbenchAction.value?.index ?? workbenchDraftAction.value?.index ?? null,
  actionName: workbenchAction.value?.name ?? workbenchDraftAction.value?.name ?? '',
}))

function openMappingWorkbench(row: Action) {
  workbenchAction.value = row
  workbenchDraftAction.value = null
  workbenchConfig.value = (row.addition ?? {}) as Record<string, any>
  workbenchOpen.value = true
}

/** 「添加动作」里选 FIELD_MAP：不建动作，直接开编辑器（保存时连动作一起建）。 */
function openMappingWorkbenchForDraft() {
  if (!actionForm.name.trim()) {
    ElMessage.warning('先给这个动作起个名字')
    return
  }
  workbenchAction.value = null
  workbenchDraftAction.value = { name: actionForm.name.trim(), index: actionForm.index }
  workbenchConfig.value = null
  actionFormVisible.value = false
  workbenchOpen.value = true
}

/** 动作的通用字段（PUT /actions/{id} 的 body 形状，与 saveAction 共用）。 */
function actionBody(action: { name: string; type: string; index: number }, addition: Record<string, any>) {
  return { name: action.name, type: action.type, index: action.index, addition }
}

/** 编辑器点保存：已有动作写回去，新建动作先建出来再写这份映射。 */
async function onWorkbenchSaved(config: Record<string, any>) {
  try {
    if (workbenchAction.value) {
      await api(`/actions/${workbenchAction.value.id}`, {
        method: 'PUT',
        body: JSON.stringify(actionBody({ ...workbenchAction.value }, config)),
      })
    } else {
      const draft = workbenchDraftAction.value
      if (!draft || !selectedJob.value) return
      await api(`/jobs/${selectedJob.value.id}/actions`, {
        method: 'POST',
        body: JSON.stringify(actionBody({ ...draft, type: 'FIELD_MAP' } as Action, config)),
      })
    }
    workbenchOpen.value = false
    workbenchAction.value = null
    workbenchDraftAction.value = null
    ElMessage.success('字段映射已保存')
    await loadActions()
  } catch (e) {
    // 存不下去（400 / 网络）时抽屉还开着：`FieldMappingWorkbench` 保存失败时不会自己关，
    // 点「取消」就能把它放回来
    ElMessage.error((e as ApiError).message)
  }
}

/** 一分多（拆分/还原两档）与字段映射在链上干活，不引用数据源；合并也不连出口（出口由后面的出站动作承担）。 */
const kindNeedsDataSource = computed(() => !['FIELD_MAP', 'ONE_TO_MANY', 'AGGREGATE'].includes(actionForm.type))

const filteredDataSources = computed(() => {
  const typeFilter: Record<string, string[]> = {
    READ_SQL: ['MYSQL', 'SQLSERVER'],
    WRITE_UPSERT: ['MYSQL', 'SQLSERVER'],
    HTTP_CALL: ['HTTP'],
    MQ_CONSUME: ['RABBITMQ'],
    MQ_PUBLISH: ['RABBITMQ'],
    FIELD_MAP: [],
  }
  const allowed = typeFilter[actionForm.type] ?? []
  return dataSources.value.filter(ds => allowed.includes(ds.type))
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
  // MQ_CONSUME exchange 类型缺省 direct（ADR-0010）；切换类型时重置回缺省
  if (actionForm.type === 'MQ_CONSUME') kindForm.exchangeType = 'direct'
  // 一分多缺省「拆分」档，且该档缺省摊平外层字段（与后端 OneToManyConfig 的缺省一致）：
  // 开关一进来就是"开"，别让用户以为关着——后端在字段缺失时也是 true
  if (actionForm.type === 'ONE_TO_MANY') {
    kindForm.mode = 'split'
    kindForm.keepParent = true
  }
}

/** 换档位时把**另一档**的字段清掉：两档各认各的键，留着会被后端当「未知字段」拒掉。 */
function onModeChange(mode: string) {
  if (mode === 'restore') {
    delete kindForm.elementKey
    delete kindForm.keepParent
  } else {
    delete kindForm.resultKeyField
    delete kindForm.attachAs
    if (kindForm.keepParent == null) kindForm.keepParent = true
  }
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
  actionFormVisible.value = true
}

/**
 * 弹窗发出的 addition。
 *
 * 新建 FIELD_MAP：只能发一份空规则，后端会以「字段映射动作需要至少一条映射规则」拒掉
 * （`FieldMappingConfig.problems()` 是保存期校验）——界面上的出路是「去配字段映射」，
 * 从编辑器里把动作与那份草稿一起建出来。这句原因写在这里，别处只指过来。
 *
 * 改已有 FIELD_MAP：把那一行的映射原样带上（弹窗里刚改过就用编辑器里那份）——它是
 * 「改名称 / 序号」唯一能存下去的办法（不带映射的 PUT 会被同一条校验拒掉），代价是弹窗里
 * 改了映射没人知道，所以按钮上写「改名称 / 序号顺便保存映射」。
 */
function buildActionAddition(): Record<string, any> {
  if (actionForm.type === 'FIELD_MAP') {
    if (!editingAction.value) return { rules: [] }
    return workbenchAction.value?.id === editingAction.value.id && workbenchConfig.value
      ? workbenchConfig.value
      : editingAction.value.addition ?? { rules: [] }
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
  const body = actionBody({ name: actionForm.name, type: actionForm.type, index: actionForm.index }, addition)
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
.issue { margin-top: 6px; font-size: var(--fs-body); white-space: pre-wrap; }
.issue.error { color: var(--danger-ink); }
.issue.warn { color: var(--warn-ink); }
</style>
