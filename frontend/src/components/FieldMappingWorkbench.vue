<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="100%"
    :with-header="false"
    :close-on-click-modal="false"
    class="mapping-workbench-drawer"
  >
    <div class="wb" tabindex="-1" ref="rootEl">
      <!-- 顶部：标题 + 默认来源 + 进度 -->
      <header class="wb-head">
        <div class="wb-title">
          <strong>字段映射</strong>
          <span class="wb-sub">{{ actionName || '新动作' }}<template v-if="jobName"> · {{ jobName }}</template></span>
        </div>

        <div class="wb-head-actions">
          <span class="wb-label">默认来源</span>
          <el-select
            :model-value="defaultStep"
            size="small"
            class="wb-step-select"
            @update:model-value="onDefaultStepChange"
          >
            <el-option
              v-for="s in stepOptions"
              :key="String(s.step)"
              :value="s.step ?? 0"
              :label="s.label"
            />
          </el-select>

          <el-tooltip placement="bottom" effect="light">
            <template #content>
              <div>必填：顶层 {{ progress.top.configured }}/{{ progress.top.total }}</div>
              <div>必填：数组内 {{ progress.array.configured }}/{{ progress.array.total }}</div>
              <div>可选：{{ progress.optional.configured }}/{{ progress.optional.total }}</div>
            </template>
            <div class="wb-progress">
              <el-progress
                :percentage="progressPercent"
                :stroke-width="14"
                :status="progress.total > 0 && progress.configured >= progress.total ? 'success' : undefined"
              />
              <span class="wb-progress-text">必填 {{ progress.configured }}/{{ progress.total }}</span>
            </div>
          </el-tooltip>

          <el-button size="small" @click="close">取消</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </header>

      <!-- 主体：左来源清单 + 右逐行配置 -->
      <el-alert
        v-if="expressionMode"
        class="wb-alert"
        type="warning"
        :closable="false"
        title="这个动作现在用的是手写 JSONata 表达式"
        description="新版逐行编辑器这一轮还看不了它。保存会用逐行规则替换掉这份表达式；只想改表达式请先用旧编辑器。"
      />
      <div class="wb-body">
        <aside class="wb-source">
          <div class="wb-panel-title">来源字段</div>
          <div class="wb-panel-tip">{{ sourceTip }}</div>
          <div v-if="loading" class="wb-empty">加载中…</div>
          <div v-else-if="!sourceGroups.length" class="wb-empty">这一来源还没有字段清单</div>
          <div v-else class="wb-source-list">
            <div v-for="group in sourceGroups" :key="String(group.step)" class="wb-source-group">
              <div class="wb-source-group-title">{{ group.label }}</div>
              <div
                v-for="field in group.fields"
                :key="field.name"
                class="wb-source-item"
                :class="{ used: group.used.has(field.name), 'array-field': isArrayPath(field.name) }"
                tabindex="0"
                @click="pick(field, group)"
                @keydown.enter.prevent="pick(field, group)"
              >
                <div class="wb-source-item-main">
                  <span class="wb-source-name">{{ field.name }}</span>
                  <span v-if="isArrayPath(field.name)" class="wb-chip">数组</span>
                  <span v-if="group.used.has(field.name)" class="wb-chip used">已用 {{ group.used.get(field.name) }}</span>
                </div>
                <div class="wb-source-meta">{{ field.type }}<template v-if="sampleOf(field)"> · 样例 {{ sampleOf(field) }}</template></div>
              </div>
            </div>
          </div>
        </aside>

        <section class="wb-rows">
          <div class="wb-rows-head">
            <div class="wb-panel-title">目标字段</div>
            <div class="wb-panel-tip">{{ targetTip }}</div>
            <el-button size="small" text type="primary" @click="reloadTargets">重新探测目标字段</el-button>
          </div>

          <div v-if="!rows.length" class="wb-empty">
            还没有目标字段清单——选一个写目标动作，或者先点「重新探测目标字段」。
          </div>

          <div v-else class="wb-row-list">
            <template v-for="entry in displayRows" :key="entry.row.id">
              <div
                class="wb-row"
                :class="{ selected: entry.row.id === selectedId, child: entry.depth > 0 }"
                :data-row-id="entry.row.id"
                @click="select(entry.row)"
              >
                <div class="wb-col-target">
                  <el-input
                    v-model="entry.row.target"
                    size="small"
                    placeholder="目标字段路径（可手输）"
                    @focus="select(entry.row)"
                  >
                    <template v-if="!entry.fromTargets" #append>
                      <span class="wb-chip">手写</span>
                    </template>
                  </el-input>
                </div>

                <div class="wb-col-transform">
                  <el-select v-model="entry.row.transform" size="small">
                    <el-option v-for="t in transformOptions" :key="t.value" :value="t.value" :label="t.label" />
                  </el-select>
                </div>

                <div class="wb-col-source">
                  <template v-if="entry.row.iterate === 'FOR_EACH'">
                    <div class="wb-source-value" :title="rowFullLabel(entry)">
                      {{ entry.row.source ? rowLabel(entry, false) : '还没选来源数组' }}
                    </div>
                    <div class="wb-child-hint">下面 {{ entry.row.children.length }} 个子字段在数组元素内取值</div>
                  </template>
                  <div v-else class="wb-source-value" :title="rowFullLabel(entry)">
                    {{ entry.row.source ? rowLabel(entry, entry.depth > 0) : emptyRowLabel() }}
                  </div>
                </div>
              </div>

              <div v-if="entry.row.iterate === 'FOR_EACH' && entry.row.children.length" class="wb-children-title">
                数组元素内
              </div>
            </template>
          </div>
        </section>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { EditorSourceSample, FieldMappingEditorContext, SourceStructure } from '../types'
import {
  fillRowFromSource, flattenRows, isArrayPath, mergeRowsWithTargets, nextEmptyRow, progressOf,
  rowReferenceFullLabel, rowReferenceLabel, rowsFromConfig, rowsFromTargetFields, targetFieldsFromPaths,
  type FlatRow, type SourceField, type TargetField,
} from '../mapping/editor-model.ts'
import { ruleFromRow, type MapRow } from '../mapping/rows.ts'
import { emptyRowLabel, stepLabel, transformLabel, TRANSFORM_KINDS } from '../mapping/labels.ts'

const props = defineProps<{
  jobId: number
  jobName?: string
  actionId?: number | null
  /** 新建动作时它准备占用的序号（用来找链上更早的动作）。 */
  actionIndex?: number | null
  actionName?: string
  /** 打开时的已存配置（编辑已有动作）。 */
  config?: Record<string, any> | null
}>()

const emit = defineEmits<{ (e: 'saved', config: Record<string, any>): void; (e: 'closed'): void }>()

const visible = ref(true)
const loading = ref(false)
const saving = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/** 这个动作原来是手写 JSONata（mode=EXPRESSION）：这一轮看不了它，保存会替换掉。 */
const expressionMode = computed(() => props.config?.mode === 'EXPRESSION')

const context = ref<FieldMappingEditorContext | null>(null)
const rows = ref<MapRow[]>([])
const targets = ref<TargetField[]>([])
const targetSourceKey = ref<string>('')
const selectedId = ref<number | null>(null)
const defaultStep = ref<number>(0)

const transformOptions = computed(() => TRANSFORM_KINDS.map(t => ({ value: t, label: transformLabel(t) })))

const actionNames = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  for (const a of context.value?.actions ?? []) {
    if (a.index != null) map[a.index] = a.name ?? ''
  }
  return map
})

/** 链上更早的动作都能当默认来源；任务输入（0）永远在选项里。 */
const stepOptions = computed(() => {
  const options: { step: number | null; label: string }[] = [{ step: 0, label: '任务输入' }]
  for (const a of context.value?.actions ?? []) {
    if (a.index == null || a.index <= 0) continue
    if (props.actionIndex != null && a.index >= props.actionIndex) continue
    options.push({ step: a.index, label: stepLabel(a.index, a.name) })
  }
  return options
})

const sourceTip = computed(() => {
  const sample = context.value?.source
  if (!sample) return '链上还没有更早的动作'
  if (!sample.fields.length) return `${stepLabel(sample.step, sample.stepName)} 还没有运行记录`
  return `来自 ${stepLabel(sample.step, sample.stepName)} 最近一次成功运行`
})

const targetTip = computed(() => {
  const target = context.value?.writeTarget
  if (!target) return '这个动作后面没有写库动作，目标字段要手工导入'
  if (!targets.value.length) return `写目标：${target.actionName ?? `动作 ${target.actionIndex}`}（结构还没探测出来）`
  return `目标：${target.actionName ?? `动作 ${target.actionIndex}`}${targetSourceKey.value ? ` · ${targetSourceKey.value}` : ''}`
})

const sourceGroups = computed(() => {
  const sample = context.value?.source
  const groups: { step: number | null; label: string; fields: SourceField[]; used: Map<string, number> }[] = []
  if (sample && sample.fields.length) {
    groups.push({
      step: sample.step,
      label: stepLabel(sample.step, sample.stepName),
      fields: sample.fields,
      used: usedCounts(sample.step),
    })
  }
  return groups
})

const progress = computed(() => progressOf(rows.value, targets.value))
const progressPercent = computed(() => (progress.value.total ? Math.round(progress.value.configured / progress.value.total * 100) : 0))

interface DisplayRow extends FlatRow {
  /** 这一行的目标字段来自目标清单（而不是手输的）。 */
  fromTargets: boolean
}

/** 行在界面上按层级铺开：数组行后面紧跟它的子行。 */
const displayRows = computed<DisplayRow[]>(() =>
  flattenRows(rows.value).map(entry => ({ ...entry, fromTargets: targetPaths.value.has(entry.row.target.trim()) })))

/** 目标清单里的字段路径（含数组内的相对路径），用来标出哪些行是清单给的。 */
const targetPaths = computed(() => {
  const paths = new Set<string>()
  const walk = (list: TargetField[]) => {
    for (const field of list) {
      paths.add(field.path)
      if (field.element?.length) walk(field.element)
    }
  }
  walk(targets.value)
  return paths
})

async function load() {
  loading.value = true
  try {
    const query = new URLSearchParams()
    if (props.actionId != null) query.set('actionId', String(props.actionId))
    else if (props.actionIndex != null) query.set('actionIndex', String(props.actionIndex))
    const ctx = await api<FieldMappingEditorContext>(`/jobs/${props.jobId}/field-mapping-editor?${query.toString()}`)
    context.value = ctx
    defaultStep.value = ctx.source?.step ?? previousActionIndex(ctx)
    const saved = rowsFromConfig(props.config)
    targets.value = await loadTargets(ctx)
    rows.value = saved.length ? mergeRowsWithTargets(saved, targets.value, defaultStep.value) : rowsFromTargetFields(targets.value, defaultStep.value)
    selectFirst()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
  await nextTick()
  rootEl.value?.focus()
}

/** 默认来源步骤：链上更早的那个动作；没有更早的动作就是任务输入（0）。 */
function previousActionIndex(ctx: FieldMappingEditorContext): number {
  const earlier = (ctx.actions ?? [])
    .map(a => a.index)
    .filter((i): i is number => i != null)
    .filter(i => props.actionIndex == null || i < props.actionIndex)
  return earlier.length ? Math.max(...earlier) : 0
}

/** 目标字段清单：连库探测写目标那张表；探不出来就空着，编辑器退化成手写目标字段。 */
async function loadTargets(ctx: FieldMappingEditorContext): Promise<TargetField[]> {
  const target = ctx.writeTarget
  targetSourceKey.value = ''
  if (!target?.actionId) return []
  if (!target.tableName) {
    // 没配表名就直接说清楚：探测接口自己不知道要探哪张表（#98 H3：HTTP/MQ 做目标时没结构可探）。
    ElMessage.warning(`写目标动作「${target.actionName ?? `动作 ${target.actionIndex}`}」没有配表名，目标字段要自己填`)
    return []
  }
  try {
    const structure = await api<SourceStructure>(`/actions/${target.actionId}/probe-target`, {
      method: 'POST',
      body: JSON.stringify({ table: target.tableName, refresh: true }),
    })
    targetSourceKey.value = structure.sourceKey
    return targetFieldsFromPaths(structure.fields.map(f => f.name), requiredOf(structure))
  } catch (e) {
    // 探测失败（连不上库 / 表名不合法）不阻塞编辑器，退化成手写目标字段。
    ElMessage.warning(`目标结构没探测出来：${(e as ApiError).message}`)
    return []
  }
}

/** 必填口径：库里 NOT NULL 的列当成必填。 */
function requiredOf(structure: SourceStructure): Map<string, boolean> {
  const required = new Map<string, boolean>()
  for (const f of structure.fields ?? []) required.set(f.name, f.nullable === false)
  return required
}

async function reloadTargets() {
  const ctx = context.value
  if (!ctx) return
  loading.value = true
  try {
    targets.value = await loadTargets(ctx)
    rows.value = mergeRowsWithTargets(rows.value, targets.value, defaultStep.value)
    ElMessage.success(targets.value.length ? '目标字段已更新' : '没探测到目标字段')
  } finally {
    loading.value = false
  }
}

function usedCounts(step: number | null): Map<string, number> {
  const counts = new Map<string, number>()
  for (const { row } of flattenRows(rows.value)) {
    if (row.sourceStep === step && row.source.trim() && row.transform !== 'INDEX') {
      const key = row.source.trim()
      counts.set(key, (counts.get(key) ?? 0) + 1)
    }
  }
  return counts
}

function sampleOf(field: SourceField): string {
  const first = field.samples?.[0]
  if (first == null) return ''
  const text = typeof first === 'object' ? JSON.stringify(first) : String(first)
  return text.length > 18 ? `${text.slice(0, 18)}…` : text
}

function rowLabel(entry: DisplayRow, isChild: boolean) {
  return rowReferenceLabel(entry.row, actionNames.value, isChild)
}

function rowFullLabel(entry: DisplayRow) {
  const inherited = entry.parent?.sourceStep ?? null
  return rowReferenceFullLabel(entry.row, actionNames.value, inherited)
}

function select(row: MapRow) {
  selectedId.value = row.id
}

function selectFirst() {
  const first = displayRows.value.find(e => e.row.target.trim())
  selectedId.value = first?.row.id ?? null
}

/** 点来源字段：填进当前行，然后自动走到下一个空行。 */
function pick(field: SourceField, group: { step: number | null }) {
  const entry = displayRows.value.find(e => e.row.id === selectedId.value)
    ?? displayRows.value.find(e => !e.row.source.trim() && e.row.iterate !== 'FOR_EACH')
    ?? null
  const result = fillRowFromSource(entry?.row ?? null, field, group.step, (entry?.depth ?? 0) > 0)
  if (!result.row) {
    if (result.reason === 'array-expects-array') ElMessage.warning('这一行是数组映射，来源要选一个数组（左边带「数组」标记的字段）')
    else if (result.reason === 'not-array') ElMessage.warning('这是一个数组，要填进数组映射行')
    else if (result.reason === 'child-wants-relative') ElMessage.warning('数组子行的来源写的是相对路径——用下面这个数组元素内的字段')
    else ElMessage.warning('先选一行要填的目标字段')
    return
  }
  selectedId.value = result.row.id
  const next = nextEmptyRow(rows.value, result.row)
  if (next && next.id !== result.row.id) selectedId.value = next.id
}

/** 切默认来源：空行跟着换步骤，左边字段清单也要换成那个动作的清单。 */
async function onDefaultStepChange(step: number) {
  defaultStep.value = step
  // 还没选来源的行跟着默认来源走（已配的行不动）。
  const walk = (list: MapRow[]) => {
    for (const row of list) {
      if (!row.source.trim()) row.sourceStep = step
      if (row.children.length) walk(row.children)
    }
  }
  walk(rows.value)
  await loadSourceFields(step)
}

/** 重新取某个动作的字段清单（换默认来源时必须换，否则会把 A 的字段配到 B 的步骤上）。 */
async function loadSourceFields(step: number) {
  loading.value = true
  try {
    const sample = await api<EditorSourceSample>(`/jobs/${props.jobId}/field-mapping-editor/source-fields?step=${step}`)
    if (context.value) context.value = { ...context.value, source: sample }
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}

/** 上下键在行之间移动（#99 验收）。挂窗口监听：焦点可能停在行里的任意输入框上。 */
function onKeydown(e: KeyboardEvent) {
  if (!visible.value || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) return
  const list = displayRows.value.filter(entry => entry.row.target.trim())
  if (!list.length) return
  const current = list.findIndex(entry => entry.row.id === selectedId.value)
  const nextIndex = e.key === 'ArrowDown'
    ? Math.min(current + 1, list.length - 1)
    : Math.max(current - 1, 0)
  selectedId.value = list[nextIndex].row.id
  e.preventDefault()
  nextTick(() => {
    document.querySelector(`[data-row-id="${selectedId.value}"]`)?.scrollIntoView({ block: 'nearest' })
  })
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

/** 配置 → 规则（编辑器保存时用；一行就是一条规则）。 */
function getConfig(): Record<string, any> {
  return { rules: rows.value.map((r, i) => ruleFromRow(r, i)).filter(Boolean) }
}

async function save() {
  if (expressionMode.value) {
    try {
      await ElMessageBox.confirm(
        '这个动作现在用的是手写 JSONata 表达式。新版逐行编辑器还看不了它，保存会用逐行规则替换掉它。',
        '会替换手写表达式',
        { type: 'warning', confirmButtonText: '替换并保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  if (!rows.value.some(r => r.target.trim())) {
    ElMessage.warning('还没有任何目标字段——改一个写目标动作，或者先点「重新探测目标字段」')
    return
  }
  saving.value = true
  try {
    emit('saved', getConfig())
    visible.value = false
  } finally {
    saving.value = false
  }
}

function close() {
  visible.value = false
  emit('closed')
}

defineExpose({ getConfig, load })

load()
</script>

<style scoped>
.wb { display: flex; flex-direction: column; height: 100vh; outline: none; }
.wb-head {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  padding: 10px 16px; border-bottom: 1px solid #e5e7eb; background: #fff;
}
.wb-title { display: flex; align-items: baseline; gap: 8px; }
.wb-sub { font-size: 12px; color: #6b7280; }
.wb-head-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.wb-label { font-size: 12px; color: #6b7280; }
.wb-step-select { width: 200px; }
.wb-progress { width: 220px; display: flex; align-items: center; gap: 8px; }
.wb-progress-text { font-size: 12px; color: #6b7280; white-space: nowrap; }

.wb-body { display: flex; flex: 1; min-height: 0; }
.wb-source {
  width: 280px; flex-shrink: 0; border-right: 1px solid #e5e7eb;
  display: flex; flex-direction: column; padding: 10px; min-height: 0;
}
.wb-panel-title { font-size: 13px; font-weight: 600; color: #111827; }
.wb-panel-tip { font-size: 11px; color: #9ca3af; margin: 2px 0 8px; }
.wb-source-list { overflow: auto; flex: 1; }
.wb-source-group + .wb-source-group { margin-top: 10px; }
.wb-source-group-title { font-size: 12px; color: #374151; font-weight: 600; margin-bottom: 4px; }
.wb-source-item { padding: 5px 6px; border-radius: 4px; cursor: pointer; }
.wb-source-item:hover { background: #f3f4f6; }
.wb-source-item.used { background: #f0f9ff; }
.wb-source-item.array-field .wb-source-name { color: #7c3aed; }
.wb-source-item-main { display: flex; align-items: center; gap: 6px; }
.wb-source-name { font-family: Consolas, monospace; font-size: 12px; }
.wb-source-meta { font-size: 11px; color: #9ca3af; margin-top: 2px; }
.wb-chip { font-size: 10px; color: #6b7280; border: 1px solid #d1d5db; border-radius: 3px; padding: 0 4px; }
.wb-chip.used { color: #0369a1; border-color: #bae6fd; }

.wb-rows { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 10px 16px; min-height: 0; }
.wb-rows-head { display: flex; align-items: center; gap: 10px; }
.wb-rows-head .wb-panel-tip { margin: 0; }
.wb-row-list { overflow: auto; flex: 1; margin-top: 6px; }
.wb-row {
  display: flex; align-items: center; gap: 10px;
  padding: 5px 8px; border-radius: 4px; border: 1px solid transparent;
}
.wb-row:hover { background: #f9fafb; }
.wb-row.selected { border-color: #409eff; background: #ecf5ff; }
.wb-row.child { margin-left: 24px; background: #fcfcfd; }
.wb-row.child.selected { background: #ecf5ff; }
.wb-col-target { width: 280px; flex-shrink: 0; }
.wb-col-transform { width: 130px; flex-shrink: 0; }
.wb-col-source { flex: 1; min-width: 0; }
.wb-source-value { font-size: 13px; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wb-child-hint { font-size: 11px; color: #9ca3af; }
.wb-children-title { font-size: 11px; color: #6b7280; margin: 4px 0 2px 32px; }
.wb-empty { font-size: 12px; color: #9ca3af; padding: 10px 0; }
.wb-alert { margin: 8px 16px 0; }

@media (max-width: 900px) {
  .wb-source { width: 200px; }
  .wb-col-target { width: 180px; }
  .wb-col-transform { width: 110px; }
}
</style>
