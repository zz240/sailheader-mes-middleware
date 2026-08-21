<template>
  <div class="mapping-editor">
    <el-radio-group v-model="mode" size="small">
      <el-tooltip content="表格点选方式配置字段映射（推荐）" placement="top">
        <el-radio-button value="RULES">结构化规则</el-radio-button>
      </el-tooltip>
      <el-tooltip content="直接写 JSONata 表达式，适合复杂/跨层变换" placement="top">
        <el-radio-button value="EXPRESSION">手写表达式</el-radio-button>
      </el-tooltip>
    </el-radio-group>

    <template v-if="mode === 'EXPRESSION'">
      <el-input
        v-model="expression"
        type="textarea"
        :rows="6"
        placeholder='手写 JSONata 表达式，如 {"bomNo": $s_1.productCode}；上下文步骤可用 $s_1（动作 1）、$s_0（任务输入）等变量'
      />
      <div class="hint">
        可用变量：$s_&lt;动作序号&gt; 引用上下文；$dateFormat(值, "yyyy-MM-dd") 格式化日期；mode=EXPRESSION 跳过规则树校验。
      </div>
    </template>

    <template v-else>
      <div class="toolbar">
        <div class="toolbar-left">
          <div class="sample-box">
            <span class="sample-label">来源样例</span>
            <el-input v-model="sourceSample" type="textarea" :rows="1" resize="none"
                      placeholder='{"product":{"productCode":"PO-1","bomItems":[{"materialCode":"M1"}]}}' />
          </div>
          <div class="sample-box">
            <span class="sample-label">对接样例</span>
            <el-input v-model="targetSample" :rows="1"
                      placeholder='{"bom":{"bomNo":"x","bomLines":[]}}' />
          </div>
        </div>
        <div class="toolbar-buttons">
          <el-tooltip content="从对接样例提取字段清单，用于右侧目标树与推荐" placement="top">
            <el-button size="small" @click="importTargetFields">生成目标字段</el-button>
          </el-tooltip>
          <el-tooltip content="按历史确认 + 字段相似度推荐映射候选" placement="top">
            <el-button size="small" type="primary" plain @click="fetchSuggestions">获取推荐</el-button>
          </el-tooltip>
          <el-tooltip content="把推荐结果里得分最高的候选自动填入空行" placement="top">
            <el-button size="small" type="primary" plain @click="autoFill">一键自动填充</el-button>
          </el-tooltip>
          <el-tooltip content="检查当前任务与动作配置是否完整（必填项、数据源引用等）" placement="top">
            <el-button size="small" @click="emit('validate')">校验配置</el-button>
          </el-tooltip>
        </div>
      </div>

      <div class="editor-body">
        <div class="tree-panel">
          <div class="panel-title">源结构</div>
          <div class="panel-tip">点选字段 → 再点右侧目标，自动生成映射行</div>
          <el-tree
            :data="sourceTree"
            node-key="key"
            :props="{ label: 'label', children: 'children' }"
            :current-node-key="selectedSource?.key"
            highlight-current
            @node-click="onSourceClick"
            class="field-tree"
          >
            <template #default="{ data }">
              <span :class="['tree-label', { 'hl-node': hlClass(data.path, sourceHighlights) }]">{{ data.label }}</span>
            </template>
          </el-tree>
        </div>

        <div class="center-panel">
          <div class="table-toolbar">
            <span class="panel-title">映射关系</span>
            <el-tooltip content="添加一行：来源字段 → 目标字段 + 转换" placement="top">
              <el-button size="small" type="primary" plain @click="addRow(defaultStep())">+ 字段映射</el-button>
            </el-tooltip>
            <el-tooltip content="添加数组组：来源数组 → 目标数组，展开后配置元素字段映射" placement="top">
              <el-button size="small" @click="addGroupRow()">+ 数组映射</el-button>
            </el-tooltip>
            <span v-if="selectedSource" class="pick-hint">已选来源：{{ selectedSource.path }}（点右侧目标落行）</span>
          </div>
          <MappingRowsTable
            :rows="rows"
            :source-steps="sourceSteps"
            :child-paths="[]"
            :target-fields="targetFields"
            :source-sample="parsedSourceSample"
            :field-paths="fieldPaths"
            :highlight-id="highlightId"
            @row-select="onRowSelect"
          />

          <div v-if="suggestions.length" class="suggest-panel">
            <div class="suggest-title">推荐候选（点击“采用”填入映射行）</div>
            <el-table :data="paginatedSuggestions" border size="small" max-height="220">
              <el-table-column label="目标字段" width="120">
                <template #default="{ row }">
                  <span :class="{ 'target-adopted': isAdopted(row.targetName) }">{{ row.targetName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="来源路径" min-width="150">
                <template #default="{ row }"><code class="src-path">{{ row.sourcePath }}</code></template>
              </el-table-column>
              <el-table-column label="转换" width="110">
                <template #default="{ row }">{{ transformLabel(row) }}</template>
              </el-table-column>
              <el-table-column label="置信度" width="80">
                <template #default="{ row }">
                  <el-tag size="small" :type="scoreType(row.score)">{{ Math.round(row.score * 100) }}%</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="84" align="center">
                <template #default="{ row }">
                  <el-button v-if="!isAdopted(row.targetName)" size="small" type="primary" @click="applySuggestion(row)">采用</el-button>
                  <el-tag v-else size="small" type="success">已采用</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              v-if="suggestions.length > pageSize"
              v-model:current-page="suggestPage"
              :page-size="pageSize"
              :total="suggestions.length"
              layout="prev, pager, next, total"
              small
              class="suggest-pager"
            />
          </div>
        </div>

        <div class="tree-panel">
          <div class="panel-title">对接结构</div>
          <div class="panel-tip">数组节点生成数组组行；点字段即绑定</div>
          <el-tree
            :data="targetTree"
            node-key="key"
            :props="{ label: 'label', children: 'children' }"
            :current-node-key="selectedTarget?.key"
            highlight-current
            @node-click="onTargetClick"
            class="field-tree"
          >
            <template #default="{ data }">
              <span :class="['tree-label', { 'hl-node': hlClass(data.path, targetHighlights) }]">{{ data.label }}</span>
            </template>
          </el-tree>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { MappingSuggestion, ProbeResult } from '../types'
import MappingRowsTable from './MappingRowsTable.vue'
import { createGroupRow, createRow, rowFromRule, ruleFromRow, type MapRow } from '../mapping/rows'

const props = defineProps<{ jobId: number; sourceSteps?: number[]; previousStep?: number }>()
const emit = defineEmits<{ (e: 'validate'): void }>()

const mode = ref<'RULES' | 'EXPRESSION'>('RULES')
const expression = ref('')
const rows = reactive<MapRow[]>([])
const sourceSample = ref('')
const targetSample = ref('')
const fieldPaths = ref<string[]>([])
const targetFields = ref<string[]>([])
const suggestions = ref<MappingSuggestion[]>([])
const suggestPage = ref(1)
const pageSize = 6

const sourceSteps = computed(() => props.sourceSteps ?? [])

const parsedSourceSample = computed<any>(() => {
  try {
    return JSON.parse(sourceSample.value || '{}')
  } catch {
    return null
  }
})

// ---------- 双树 ----------
interface FieldNode {
  key: string
  label: string
  path: string
  isArray: boolean
  children?: FieldNode[]
}

const selectedSource = ref<FieldNode | null>(null)
const selectedTarget = ref<FieldNode | null>(null)

const sourceTree = computed(() => buildTree(parsedSourceSample.value, ''))
const targetTree = computed(() => {
  try {
    return buildTree(JSON.parse(targetSample.value || '{}'), '')
  } catch {
    return []
  }
})

function buildTree(value: any, prefix: string): FieldNode[] {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return []
  return Object.entries(value).map(([k, v]) => {
    const path = prefix ? `${prefix}.${k}` : k
    const isArray = Array.isArray(v)
    const sample = isArray ? (v as any[])[0] : v
    const children = sample != null && typeof sample === 'object' && !Array.isArray(sample)
      ? buildTree(sample, path)
      : undefined
    return {
      key: path,
      label: isArray ? `${k} []` : k,
      path,
      isArray,
      children: children && children.length ? children : undefined,
    }
  })
}

// ---------- 选中联动：表格行 ↔ 双树高亮 ----------
const highlightId = ref<number | null>(null)
const selectedInfo = ref<{ sourcePath: string; targetPath: string } | null>(null)

const sourceHighlights = computed<Set<string>>(() => {
  const set = new Set<string>()
  if (selectedInfo.value?.sourcePath) set.add(selectedInfo.value.sourcePath)
  return set
})
const targetHighlights = computed<Set<string>>(() => {
  const set = new Set<string>()
  if (selectedInfo.value?.targetPath) set.add(selectedInfo.value.targetPath)
  return set
})

function hlClass(path: string, set: Set<string>) {
  return set.has(path) ? 'hl-node' : ''
}

function onRowSelect(info: { id: number; sourcePath: string; targetPath: string }) {
  highlightId.value = info.id
  selectedInfo.value = { sourcePath: info.sourcePath, targetPath: info.targetPath }
}

/** 选中某一行并同步双树高亮（表格行点击与树点选定位共用）。 */
function selectRow(row: MapRow, sourcePath: string, targetPath: string) {
  highlightId.value = row.id
  selectedInfo.value = { sourcePath, targetPath }
}

function onSourceClick(node: FieldNode) {
  selectedSource.value = node
  const hit = findRowByPath(node.path, true)
  if (hit) selectRow(hit.row, hit.sourcePath, hit.targetPath)
}

function onTargetClick(node: FieldNode) {
  const src = selectedSource.value
  const hit = findRowByPath(node.path, false)
  if (hit) selectRow(hit.row, hit.sourcePath, hit.targetPath)
  if (!src) {
    selectedTarget.value = node
    return
  }
  if (node.isArray) {
    const existing = rows.find(r => r.target.trim() === node.path)
    if (existing) {
      if (!existing.source.trim()) existing.source = src.path
    } else {
      addGroupRow(defaultStep(), src.path, node.path)
    }
  } else {
    fillLeafRow(src.path, node.path)
  }
  selectedSource.value = null
  selectedTarget.value = node
}

/** 在规则树中查找 source/target 与给定绝对路径匹配的行，返回行及其绝对路径（含组内前缀拼接）。 */
function findRowByPath(path: string, sourceSide: boolean): { row: MapRow; sourcePath: string; targetPath: string } | null {
  const walk = (list: MapRow[], pSource: string, pTarget: string): { row: MapRow; sourcePath: string; targetPath: string } | null => {
    for (const row of list) {
      const absSource = pSource && row.source ? `${pSource}.${row.source}` : row.source
      const absTarget = pTarget && row.target ? `${pTarget}.${row.target}` : row.target
      if ((sourceSide && absSource === path) || (!sourceSide && absTarget === path)) {
        return { row, sourcePath: absSource, targetPath: absTarget }
      }
      if (row.iterate === 'FOR_EACH') {
        const found = walk(row.children, absSource, absTarget)
        if (found) return found
      }
    }
    return null
  }
  return walk(rows, '', '')
}

function fillLeafRow(source: string, target: string) {
  const row = rows.find(r => r.target.trim() === target) ?? rows.find(r => !r.target.trim())
  if (row) {
    row.source = source
    row.target = target
    row.sourceStep = defaultStep()
    selectRow(row, source, target)
  } else {
    const created = createRow(defaultStep(), source, target, 'STRING')
    rows.push(created)
    selectRow(created, source, target)
  }
}

// ---------- 行操作 ----------
function addRow(sourceStep: number, source = '', target = '', transform = 'STRING', extra = '') {
  rows.push(createRow(sourceStep, source, target, transform, extra))
}

function addGroupRow(sourceStep?: number, source = '', target = '') {
  rows.push(createGroupRow(sourceStep ?? defaultStep(), source, target))
}

// ---------- 建议 ----------
const paginatedSuggestions = computed(() => {
  const start = (suggestPage.value - 1) * pageSize
  return suggestions.value.slice(start, start + pageSize)
})

const adoptedTargets = computed(() => new Set(rows.filter(r => r.target.trim()).map(r => r.target.trim())))

function isAdopted(target: string) {
  return adoptedTargets.value.has(target)
}

function scoreType(score: number): 'success' | 'warning' | 'info' {
  if (score >= 0.8) return 'success'
  if (score >= 0.5) return 'warning'
  return 'info'
}

function transformLabel(s: MappingSuggestion) {
  if (s.transform === 'CONSTANT') return `常量「${s.constant ?? ''}」`
  if (s.transform === 'ENUM' && s.enumMap) return `枚举(${Object.keys(s.enumMap).length})`
  if (s.transform === 'DATE' && s.datePattern) return `日期 ${s.datePattern}`
  if (s.children?.length) return `数组(${s.children.length} 字段)`
  return s.transform
}

async function loadSampleFields() {
  try {
    const sample = JSON.parse(sourceSample.value || '{}')
    fieldPaths.value = await api<string[]>('/sample-fields', { method: 'POST', body: JSON.stringify({ sample }) })
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function importTargetFields() {
  try {
    const sample = JSON.parse(targetSample.value || '{}')
    targetFields.value = await api<string[]>('/sample-fields', { method: 'POST', body: JSON.stringify({ sample }) })
    ElMessage.success(`目标字段已生成：${targetFields.value.join(', ')}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

const arrayTargetPaths = computed(() => {
  const out: string[] = []
  const walk = (nodes: FieldNode[]) => {
    nodes.forEach(n => {
      if (n.isArray) out.push(n.path)
      if (n.children?.length) walk(n.children)
    })
  }
  walk(targetTree.value)
  return out
})

async function fetchSuggestions() {
  if (!targetFields.value.length) {
    ElMessage.warning('请先点击“生成目标字段”')
    return
  }
  try {
    const sample = JSON.parse(sourceSample.value || '{}')
    const source = await api<ProbeResult>('/probe/sample', { method: 'POST', body: JSON.stringify({ sample }) })
    suggestions.value = await api<MappingSuggestion[]>('/mapping-suggestions', {
      method: 'POST',
      body: JSON.stringify({
        jobId: props.jobId,
        source,
        targets: [
          ...targetFields.value.map(name => ({ name, required: true })),
          ...arrayTargetPaths.value.map(name => ({ name, required: false, array: true })),
        ],
      }),
    })
    suggestPage.value = 1
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function applySuggestion(s: MappingSuggestion) {
  if (s.children?.length) {
    const group = rows.find(r => r.target.trim() === s.targetName && r.iterate === 'FOR_EACH')
    const row = group ?? createGroupRow(defaultStep(), s.sourcePath, s.targetName)
    if (!group) rows.push(row)
    row.children = s.children.map(c => createRow(
      null,
      c.sourcePath,
      c.targetName,
      transformSelectValue(c.transform),
      extraFor(c),
    ))
    selectRow(row, s.sourcePath, s.targetName)
    return
  }
  let row = rows.find(r => r.target === s.targetName)
  if (!row) {
    row = rows.find(r => !r.target)
    if (!row) {
      addRow(defaultStep())
      row = rows[rows.length - 1]
    }
  }
  if (row.target && row.target !== s.targetName) return
  row.source = s.sourcePath
  row.target = s.targetName
  row.sourceStep = defaultStep()
  row.transform = transformSelectValue(s.transform)
  row.extra = extraFor(s)
  selectRow(row, s.sourcePath, s.targetName)
}

function autoFill() {
  if (!suggestions.value.length) {
    ElMessage.warning('请先获取推荐')
    return
  }
  const top1: Record<string, MappingSuggestion> = {}
  suggestions.value.forEach(s => {
    if (!(s.targetName in top1)) top1[s.targetName] = s
  })
  let filled = 0
  Object.values(top1).forEach(s => {
    const existing = rows.find(r => r.target === s.targetName)
    if (existing) return
    const row = rows.find(r => !r.target) || (addRow(defaultStep()), rows[rows.length - 1])
    row.source = s.sourcePath
    row.target = s.targetName
    row.sourceStep = defaultStep()
    row.transform = transformSelectValue(s.transform)
    row.extra = extraFor(s)
    filled++
  })
  ElMessage.success(`已自动填充 ${filled} 行（只补空行）`)
}

function transformSelectValue(kind: string) {
  return kind === 'BOOLEAN' || kind === 'NUMBER' ? 'STRING' : kind
}

function extraFor(s: MappingSuggestion) {
  if (s.transform === 'CONSTANT') return s.constant ?? ''
  if (s.transform === 'ENUM' && s.enumMap) return JSON.stringify(s.enumMap)
  if (s.transform === 'DATE' && s.datePattern) return s.datePattern
  return ''
}

// ---------- 配置存取（递归） ----------
function getConfig(): Record<string, any> {
  if (mode.value === 'EXPRESSION') {
    return { mode: 'EXPRESSION', expression: expression.value, rules: [] }
  }
  const rules = rows.map((r, i) => ruleFromRow(r, i)).filter(Boolean)
  return { rules }
}

function setConfig(config: Record<string, any>) {
  mode.value = config.mode === 'EXPRESSION' ? 'EXPRESSION' : 'RULES'
  expression.value = config.expression ?? ''
  rows.length = 0
  const rules = Array.isArray(config.rules) ? config.rules : []
  rules.forEach(r => rows.push(rowFromRule(r)))
}

function getRules() {
  return getConfig().rules ?? []
}

function setRules(rules: Record<string, any>[]) {
  setConfig({ rules })
}

function defaultStep() {
  if (props.previousStep != null) return props.previousStep
  const steps = props.sourceSteps ?? []
  return steps.length ? steps[steps.length - 1] : 0
}

defineExpose({ getConfig, setConfig, getRules, setRules, loadSampleFields })
</script>

<style scoped>
.mapping-editor { display: flex; flex-direction: column; gap: 8px; }
.hint { font-size: 12px; color: #888; }

/* 顶部工具栏 */
.toolbar { display: flex; gap: 10px; align-items: flex-start; flex-wrap: wrap; }
.toolbar-left { display: flex; gap: 10px; flex: 1; min-width: 0; }
.sample-box { flex: 1; min-width: 180px; display: flex; align-items: center; gap: 6px; }
.sample-label { font-size: 12px; color: #666; white-space: nowrap; }
.toolbar-buttons { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }

/* 三栏布局 */
.editor-body { display: flex; gap: 10px; align-items: stretch; }
.tree-panel {
  width: 200px; flex-shrink: 0; border: 1px solid #e5e7eb; border-radius: 6px;
  padding: 6px; display: flex; flex-direction: column; max-height: 420px;
}
.panel-title { font-size: 12px; font-weight: 600; color: #333; }
.panel-tip { font-size: 11px; color: #999; margin: 2px 0 4px; }
.field-tree { overflow: auto; flex: 1; }
.center-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.table-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pick-hint { font-size: 12px; color: #409eff; }

/* 树节点高亮（选中映射行时联动） */
.tree-label { font-size: 12px; }
.tree-label.hl-node { color: #409eff; font-weight: 700; }

.suggest-panel { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; }
.suggest-title { font-size: 12px; color: #555; margin-bottom: 4px; }
.suggest-pager { margin-top: 6px; justify-content: flex-end; }
.src-path { font-family: Consolas, monospace; font-size: 12px; }
.target-adopted { color: #67c23a; font-weight: 600; }
</style>
