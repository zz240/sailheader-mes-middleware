<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="100%"
    :with-header="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
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
          <!-- 手写表达式这一边没有「默认来源」也没有必填进度：那边只有一段表达式 -->
          <template v-if="rowsMode">
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
          </template>
          <span v-else class="wb-chip chip-mode" title="这个动作保存的是一段手写 JSONata 表达式">手写 JSONata</span>

          <el-button size="small" @click="close">取消</el-button>
          <el-tooltip content="把这份草稿交给后端判一遍：有没有错、错在哪一行，顺带看编译产物" placement="bottom">
            <el-button size="small" :loading="validating" @click="validateConfig()">校验配置</el-button>
          </el-tooltip>
          <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
          <!-- 「高级：手写 JSONata」（#106）：不显眼但可达。深层数组的任意重排只能走它，
               所以它一直在（票里那句话）。 -->
          <el-button
            v-if="rowsMode"
            size="small"
            text
            class="wb-advanced"
            @click="openExpression()"
          >高级：手写 JSONata</el-button>
        </div>
      </header>

      <!-- 逐行配置这一边：左来源清单 + 右逐行配置 -->
      <el-alert
        v-if="rowsMode && storedExpression"
        class="wb-alert"
        type="warning"
        :closable="false"
        title="这个动作原来配的是手写 JSONata 表达式"
        :description="expressionReplaced()"
      />
      <div v-if="rowsMode" class="wb-body">
        <!-- 左边来源清单（#117 拆出去）：给哪些字段看当前选中的那一行，点一项就填进这一行 -->
        <MappingSourcePanel
          ref="sourcePanel"
          :source-tip="sourceTip"
          :loading="loading"
          @pick="pick"
        />

        <!-- 行列表（#117 拆出去）：行头、四列行渲染与就地参数面板；共享状态由 workbench-state 提供 -->
        <MappingRowList
          :target-tip="targetTip"
          :has-validation="validation != null"
          :has-errors="(validation?.errors.length ?? 0) > 0"
          :summary="issueSummary"
          :issues="locatedIssues"
          :row-issues="rowIssues"
          @reload-targets="reloadTargets"
        />
      </div>

      <!-- 手写 JSONata 这一边（#106）：整段表达式就是这次映射要执行的东西（与后端 mode=EXPRESSION 一致）。
           表达式的正文仍存在工作台这一层（保存与送校验读的都是它），子组件只负责把它画出来、改它。 -->
      <MappingExpressionEditor
        v-else
        v-model="expressionText"
        :has-validation="validation != null"
        :has-errors="(validation?.errors.length ?? 0) > 0"
        :summary="issueSummary"
        :issues="expressionIssues"
        @switch-back="switchBackToRows"
      />

      <!-- 底部编译产物（#103）：两个标签页，JSONata 与规则 JSON -->
      <div v-if="validation" class="wb-foot">
        <el-tabs v-model="artifactTab" class="wb-artifacts">
          <el-tab-pane label="JSONata" name="jsonata">
            <pre class="wb-artifact">{{ validation.compiled.jsonata || '这份草稿还没编译出表达式——先把错误改掉' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="规则 JSON" name="rules">
            <pre class="wb-artifact">{{ validation.compiled.rulesJson || '（没有内容）' }}</pre>
          </el-tab-pane>
        </el-tabs>
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
  mergeRowsWithTargets, progressOf, rowsFromConfig, rowsFromTargetFields, targetFieldsFromPaths,
  type TargetField,
} from '../mapping/editor-model.ts'
import { hasMapping, markHumanEdited, type MapRow } from '../mapping/rows.ts'
import { stepLabel } from '../mapping/labels.ts'
import {
  editorModeOf, expressionOf, expressionReplacedNotice, expressionSkipNotice,
  hasExpression, type EditorMode,
} from '../mapping/expression.ts'
import { keyIntent, rowStepIndex } from '../mapping/keyboard.ts'
import { createWorkbenchState, provideWorkbenchState } from '../mapping/workbench-state.ts'
import { useMappingDraft } from '../mapping/useMappingDraft.ts'
import MappingExpressionEditor from './MappingExpressionEditor.vue'
import MappingRowList from './MappingRowList.vue'
import MappingSourcePanel from './MappingSourcePanel.vue'

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
const rootEl = ref<HTMLElement | null>(null)

/** 底部编译产物那两个标签页。 */
const artifactTab = ref('jsonata')

/**
 * 编辑器里那些**跨组件共用**的状态（行、选中、参数面板、收起集合、清单）：建一份，provide 给子组件。
 *
 * 左边来源清单、行列表、问题面板拆出去之后（#117），这几样是它们与键盘那一段共用的：一处状态源比
 * 一层层 props / emit 传下去短得多，也不会出现两个副本各自记着「选中的是哪一行」。
 * 状态的来龙去脉与「行上的操作」都在 `mapping/workbench-state.ts`。
 */
const state = createWorkbenchState()
provideWorkbenchState(state)
const {
  rows, targets, sourceFields, selectedId, defaultStep, clearedRows, actionNames, validation,
  displayRows, selectedEntry,
  panel, closePanel, openPanel, walkPanelOption, confirmPanelOption,
  clearRow, pick,
} = state

/** 左边来源清单那一块（「走清单」那一段的状态归它，这里只按键盘的意图调它）。 */
const sourcePanel = ref<InstanceType<typeof MappingSourcePanel> | null>(null)

/** 编辑器上下文（哪一步能当来源、写目标是谁），以及正在编辑的动作那一条。 */
const context = ref<FieldMappingEditorContext | null>(null)
const targetSourceKey = ref<string>('')

/**
 * 这个编辑器现在在哪一边（#106）：逐行配置，还是手写 JSONata。
 *
 * 打开时按已存配置定（`mode=EXPRESSION` 就直接进手写这一边）——以前是给一条「这一轮看不了它」的
 * 警告，那等于把已经配好的表达式藏起来，只有删掉重配一条路。两边读写的是同一份数据格式（#98 C1），
 * 所以随时能来回切。
 */
const editorMode = ref<EditorMode>(editorModeOf(props.config))
const expressionText = ref(expressionOf(props.config))
/** 现在在逐行配置这一边（手写那一边没有「行」这套东西，跟行有关的判断都看它）。 */
const rowsMode = computed(() => editorMode.value === 'rows')
/** 已存配置里本来就有一段手写表达式（切回逐行后要说清它会被替换）。 */
const storedExpression = computed(() => hasExpression(expressionOf(props.config)))
const expressionReplaced = () => expressionReplacedNotice()

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

/** 左边来源清单（搜索、三档过滤、已用高亮与计数、键盘「走清单」那一段）在 `MappingSourcePanel.vue`。
 *  行头与四列行渲染、就地参数面板在 `MappingRowList.vue`（#117）。 */

const progress = computed(() => progressOf(rows.value, targets.value))
const progressPercent = computed(() => (progress.value.total ? Math.round(progress.value.configured / progress.value.total * 100) : 0))

// 行的铺开、展开/收起、选中、新增行、清除/撤销、参数面板那一套都在 `mapping/workbench-state.ts`（#117）。

// ---------------------------------------------------------------- 加载（上下文、目标清单、来源清单）

async function load() {
  loading.value = true
  try {
    const query = new URLSearchParams()
    if (props.actionId != null) query.set('actionId', String(props.actionId))
    else if (props.actionIndex != null) query.set('actionIndex', String(props.actionIndex))
    const ctx = await api<FieldMappingEditorContext>(`/jobs/${props.jobId}/field-mapping-editor?${query.toString()}`)
    context.value = ctx
    actionNames.value = actionNamesOf(ctx)
    sourceFields.value = ctx.source?.fields ?? []
    defaultStep.value = ctx.source?.step ?? previousActionIndex(ctx)
    const saved = rowsFromConfig(props.config)
    targets.value = await loadTargets(ctx)
    rows.value = saved.length ? mergeRowsWithTargets(saved, targets.value, defaultStep.value) : rowsFromTargetFields(targets.value, defaultStep.value)
    // 行是重铺出来的（id 全换了），上一次打开留下的撤销快照跟着作废。
    clearedRows.value = new Map()
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

/** 动作序号 → 动作名（行与片段上那些人话要用；由上下文给）。 */
function actionNamesOf(ctx: FieldMappingEditorContext): Record<number, string> {
  const map: Record<number, string> = {}
  for (const a of ctx.actions ?? []) {
    if (a.index != null) map[a.index] = a.name ?? ''
  }
  return map
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
    return targetFieldsFromPaths(structure.fields.map(f => f.name), requiredOf(structure), typesOf(structure))
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

/** 类型口径：探测给什么带什么（清单上要显示它，下一张票的类型检查也要用它）。 */
function typesOf(structure: SourceStructure): Map<string, string> {
  const types = new Map<string, string>()
  for (const f of structure.fields ?? []) {
    if (f.type) types.set(f.name, f.type)
  }
  return types
}

async function reloadTargets() {
  const ctx = context.value
  if (!ctx) return
  loading.value = true
  try {
    targets.value = await loadTargets(ctx)
    rows.value = mergeRowsWithTargets(rows.value, targets.value, defaultStep.value)
    // 已配的行按目标字段沿用同一个行对象（含 id），所以撤销快照能留就留着；重铺掉的那些行
    // 换了 id，快照按 id 找不到就自然不显示，不用特意清。
    ElMessage.success(targets.value.length ? '目标字段已更新' : '没探测到目标字段')
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 校验配置与保存（#103）

/** 链上执行顺序更早的动作序号（顶部那个下拉去掉「任务输入」）：sourceStep 只能指向这些。 */
const earlierActionIndexes = computed(() =>
  stepOptions.value.map(o => o.step ?? 0).filter(step => step > 0))

/**
 * 校验与保存那一段（草稿怎么装、回来的问题怎么落到行上、保存前拦哪几道）在 `mapping/useMappingDraft.ts`。
 *
 * 工作台这一层只把它接起来：两个按钮的 loading、问题列表与手写那一边的结果都从它来；保存通过时它把
 * 「会写出去的那一份」交回来，这里负责发 `saved` 并收起抽屉（那是唯一入口那一层的事）。
 */
const draft = useMappingDraft({
  state,
  context,
  earlierActionIndexes,
  actionIndex: () => props.actionIndex ?? null,
  editorMode,
  expressionText,
  storedExpression,
  onSaved: (config) => {
    emit('saved', config)
    visible.value = false
  },
})
const {
  validating, saving, expressionIssues, locatedIssues, rowIssues, issueSummary,
  validateConfig, save, getConfig,
} = draft

function selectFirst() {
  const first = displayRows.value.find(e => e.row.target.trim())
  selectedId.value = first?.row.id ?? null
}

/** 切默认来源：空行跟着换步骤，左边字段清单也要换成那个动作的清单。 */
async function onDefaultStepChange(step: number) {
  defaultStep.value = step
  // 还没选来源的行跟着默认来源走（已配的行不动）。
  const walk = (list: MapRow[]) => {
    for (const row of list) {
      if (!row.source.trim()) {
        row.sourceStep = step
        // 人主动换了来源步骤：这一行不再算「自动填上的那一行」（#105）。
        markHumanEdited(row)
      }
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
    sourceFields.value = sample.fields ?? []
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 键盘（#99 上下走行 / #106 补齐其余）

/**
 * 键盘上的每一个键要做什么，全在 `keyboard.ts` 里定（那是一份能用 node 直接跑的检查）；
 * 这里只把意图落到界面上：选中哪一行、焦点给谁、开哪个面板。
 *
 * 挂窗口监听、走**捕获阶段**（`{ capture: true }`）：焦点可能停在抽屉里的任意地方，而键盘操作
 * 不该受它影响；更要紧的是「目标字段那个输入框里到底算不算在输入里」要看**按下那一刻**的下拉状态
 * ——el-autocomplete 自己也在 input 上处理这些键，冒泡阶段再问就晚了（它按完回车已经把下拉收了，
 * 我们这边就会当成「下拉关着」，于是同一次回车既选中了补全项、又开了「选来源」；真机实测踩到过）。
 */
function onKeydown(e: KeyboardEvent) {
  if (!visible.value) return
  // 手写表达式那一边没有行、没有来源清单：那边只有一段文本，键盘全归输入框
  if (editorMode.value === 'expression') return
  // 确认框开着时不接管：那一刻键盘归那个框（Enter 是「确定」，退格不该去清某一行）
  if (document.querySelector('.el-message-box')) return
  const intent = keyIntent(e.key, {
    inTextEntry: inTextEntryFor(e.key, e.target),
    hasSelection: selectedId.value != null,
    pickingSource: sourcePanel.value?.picking === true,
    panel: panel.value,
  })
  if (!intent) return
  e.preventDefault()
  switch (intent.kind) {
    case 'move':
      moveSelection(intent.delta)
      return
    case 'transform':
      openTransformPanel()
      return
    case 'pick-source':
      startPickingSource()
      return
    case 'clear':
      clearSelectedRow()
      return
    case 'leave':
      sourcePanel.value?.endPick()
      return
    case 'close-panel':
      closePanel()
      return
    case 'walk-item':
      sourcePanel.value?.movePick(intent.delta)
      return
    case 'confirm-pick':
      sourcePanel.value?.confirmPick()
      return
    case 'walk-option':
      walkPanelOption(intent.delta)
      return
    case 'confirm-option':
      confirmPanelOption()
      return
  }
}

/** 上下走行：只在铺出来的、有目标字段的行之间走（从哪头进由 `rowStepIndex` 定）。 */
function moveSelection(delta: -1 | 1) {
  const list = displayRows.value.filter(entry => entry.row.target.trim())
  if (!list.length) return
  const current = list.findIndex(entry => entry.row.id === selectedId.value)
  selectedId.value = list[rowStepIndex(current, delta, list.length)].row.id
  nextTick(() => {
    document.querySelector(`[data-row-id="${selectedId.value}"]`)?.scrollIntoView({ block: 'nearest' })
  })
}

/** `/` 换转换：打开选中这一行的转换面板（键盘停在它现在用的那一种上，由 `openPanel` 摆好）。 */
function openTransformPanel() {
  const entry = selectedEntry.value
  if (!entry) return
  openPanel(entry, 'op')
}

/** 回车选来源：进「走清单」这一段（清单那一侧自己把焦点收进去、停在第一项上）。 */
function startPickingSource() {
  const entry = selectedEntry.value
  if (!entry) return
  if (entry.row.iterate !== 'FOR_EACH' && (entry.row.transform === 'CONSTANT' || entry.row.transform === 'INDEX')) {
    ElMessage.warning(entry.row.transform === 'CONSTANT'
      ? '这一行是常量：不取来源，填的是那个写死的值'
      : '这一行是行号：来源就是行号本身，不用选来源字段')
    return
  }
  const panel = sourcePanel.value
  if (!panel?.itemCount) {
    ElMessage.warning(panel?.emptyText ?? '')
    return
  }
  closePanel()
  panel.beginPick()
}

/** 退格 / 删除：清除选中这一行的取值方式（和行里的「清除」是同一件事，清除后照样能撤销）。 */
function clearSelectedRow() {
  const entry = selectedEntry.value
  if (!entry || !hasMapping(entry.row)) return
  clearRow(entry)
}

/** 这个事件发生在某个输入框 / 下拉框里吗。 */
function isTextEntry(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  const tag = el?.tagName?.toLowerCase() ?? ''
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el?.isContentEditable === true
}

/**
 * 这个键在这个焦点位置上算不算「在输入里」（算了就一个键都不抢）。
 *
 * 一处例外（#106 收掉 #102 留下的那条）：**目标字段**那个单行输入框里，补全下拉没开着时
 * `↑↓` 与回车照样归界面——`↑↓` 走行、回车进「选来源」这一段。理由：加完一行光标就落在这个输入框里，
 * 而「走行 / 选来源」正是键盘配一张清单的两步；这两个键在一个单行输入框里本来也没有别的用处
 * （下拉开着时归 el-autocomplete 换高亮 / 选中，所以那时让开）。
 *
 * 别的输入框一律不抢：搜索框的回车有它自己的活（跳行 / 补全），行号、常量、拼接片段里
 * `↑↓` 与回车都是输入的一部分，文本域（含手写表达式那一整段）更是一个键都不能动。
 */
function inTextEntryFor(key: string, target: EventTarget | null): boolean {
  if (!isTextEntry(target)) return false
  if (key !== 'ArrowUp' && key !== 'ArrowDown' && key !== 'Enter') return true
  const el = target as HTMLElement
  if (el.tagName?.toLowerCase() !== 'input' || !el.closest('.wb-col-target')) return true
  return hasOpenAutocomplete()
}

/**
 * 目标字段的补全下拉开着吗（开着时这些键归它）。
 *
 * 两个坑：① 补全是 teleport 到 body 的，每个输入框一个 popper，**只看 `querySelector` 拿到的第一个
 * 会认到别行那个关着的**——要看「有没有任何一个正铺着」；② 没有候选时 element-plus 自己就把
 * `visible` 关掉（`suggestionVisible = 候选数 > 0 && activated`），所以「铺着」就等于「有候选」。
 */
function hasOpenAutocomplete(): boolean {
  return [...document.querySelectorAll<HTMLElement>('.el-autocomplete-suggestion')]
    .some(el => el.offsetParent !== null)
}

onMounted(() => window.addEventListener('keydown', onKeydown, { capture: true }))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, { capture: true }))

/**
 * 切到手写 JSONata（#106）：先说清这条路跳过规则树校验、逐行的规则树会被整段替换。
 *
 * 切哪一边、切完清掉哪些东西（上一次校验的结果、开着的面板、没走完的「选来源」）是工作台这一层的事；
 * 手写那一边自己只管画（`MappingExpressionEditor.vue`），保存与校验在 `useMappingDraft` 里。
 */
async function openExpression() {
  const ok = await ElMessageBox.confirm(
    `${expressionReplaced()}\n\n${expressionSkipNotice()}`,
    '高级：手写 JSONata',
    { type: 'warning', confirmButtonText: '切到手写表达式', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  editorMode.value = 'expression'
  // 上一次校验是逐行那边跑的：换了一边就不该再显示它的行状态与问题
  validation.value = null
  closePanel()
  sourcePanel.value?.endPick()
}

/** 切回逐行配置（#106）：说清保存时规则树会替换掉这份手写表达式。 */
async function switchBackToRows() {
  const ok = await ElMessageBox.confirm(
    expressionReplacedNotice(),
    '切回逐行配置',
    { type: 'warning', confirmButtonText: '切回去', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  editorMode.value = 'rows'
  validation.value = null
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
.wb-alert { margin: 8px 16px 0; }

/* 来源清单那一整块、行列表与就地参数面板、问题列表的样式都在各自组件里（#117 拆出去了）。 */

/* 底部编译产物：两个标签页（JSONata / 规则 JSON） */
.wb-foot { border-top: 1px solid #e5e7eb; padding: 0 16px; background: #fff; }
.wb-artifacts :deep(.el-tabs__header) { margin-bottom: 4px; }
.wb-artifact {
  margin: 0 0 8px; max-height: 150px; overflow: auto;
  font-family: Consolas, monospace; font-size: 12px; line-height: 1.5;
  background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 4px; padding: 8px;
  white-space: pre; color: #111827;
}

/* 头部那个「手写 JSONata」的当前模式标，与「高级」入口的样式（不显眼，但一直都在） */
.wb-chip.chip-mode { color: #3730a3; border-color: #c7d2fe; background: #eef2ff; cursor: default; }
.wb-advanced { color: #9ca3af; }

/* 窄屏（#106）：先把左边来源清单挪到上面（中等宽度），行里那四列再依次竖起来。
   清单与行自己那几档在各自的组件里（#117），这里只管顶部那一条与抽屉这一层。 */
@media (max-width: 1100px) {
  .wb-head { gap: 10px; }
  .wb-head-actions { flex-wrap: wrap; row-gap: 6px; }
  .wb-progress { width: 160px; }
  .wb-step-select { width: 160px; }
}

@media (max-width: 1024px) {
  /* 来源清单铺在上面：宽度不够时它和行列表并排，两边都读不成
     （清单自己那一侧的尺寸在 `MappingSourcePanel.vue` 里） */
  .wb-body { flex-direction: column; }
}

@media (max-width: 640px) {
  .wb-head { padding: 8px; }
  .wb-progress { width: 120px; }
  .wb-step-select { width: 100%; }
}
</style>

<!-- 参数面板的基元与它们的响应式规则：两个参数编辑器组件（行级 / 片段级共用）内部也要用，见那个文件 -->
<style src="../styles/workbench-panel.css"></style>
<!-- 校验结果列表的基元：逐行那边的问题列表与手写那边的结果列表共用，见那个文件 -->
<style src="../styles/workbench-issues.css"></style>
