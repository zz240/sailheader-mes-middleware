<template>
  <div class="mapping-editor">
    <el-radio-group v-model="mode" size="small">
      <el-radio-button value="RULES">结构化规则</el-radio-button>
      <el-radio-button value="EXPRESSION">手写 JSONata 表达式</el-radio-button>
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
      <el-input v-model="sourceSample" type="textarea" :rows="2"
                placeholder='来源样例 JSON，如 {"product":{"productCode":"PO-1","bomItems":[{"materialCode":"M1"}]}}' />
      <div class="row">
        <el-input v-model="targetSample" placeholder='目标字段样例 JSON，如 {"bom":{"bomNo":"x","bomLines":[]}}' style="width: 360px" />
        <el-button @click="importTargetFields">导入目标字段</el-button>
        <el-button type="primary" @click="fetchSuggestions">获取推荐</el-button>
        <el-button type="primary" @click="autoFill">一键自动填充</el-button>
        <el-button @click="emit('validate')">校验任务</el-button>
      </div>

      <div v-if="sourceTree.length || targetTree.length" class="trees">
        <div class="tree-box">
          <div class="tree-title">来源字段（先点来源，再点目标自动落行）</div>
          <el-tree
            :data="sourceTree"
            node-key="key"
            :props="{ label: 'label', children: 'children' }"
            :current-node-key="selectedSource?.key"
            highlight-current
            @node-click="onSourceClick"
            class="field-tree"
          />
        </div>
        <div class="tree-box">
          <div class="tree-title">目标字段（数组节点自动生成组行）</div>
          <el-tree
            :data="targetTree"
            node-key="key"
            :props="{ label: 'label', children: 'children' }"
            :current-node-key="selectedTarget?.key"
            highlight-current
            @node-click="onTargetClick"
            class="field-tree"
          />
        </div>
      </div>

      <div class="table-toolbar">
        <el-button size="small" type="primary" plain @click="addRow(defaultStep())">添加映射行</el-button>
        <el-button size="small" @click="addGroupRow()">添加数组组</el-button>
        <span v-if="selectedSource" class="pick-hint">已选来源：{{ selectedSource.path }}</span>
      </div>

      <MappingRowsTable
        :rows="rows"
        :source-steps="sourceSteps"
        :child-paths="[]"
        :target-fields="targetFields"
        :source-sample="parsedSourceSample"
        :field-paths="fieldPaths"
      />

      <div v-if="suggestions.length" class="suggest-panel">
        <div class="suggest-title">推荐候选（点击“采用”填入映射行；已填写的目标字段自动标记为已采用）</div>
        <el-table :data="paginatedSuggestions" border size="small" max-height="320">
          <el-table-column label="目标字段" width="140">
            <template #default="{ row }">
              <span :class="{ 'target-adopted': isAdopted(row.targetName) }">{{ row.targetName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源路径" min-width="180">
            <template #default="{ row }"><code class="src-path">{{ row.sourcePath }}</code></template>
          </el-table-column>
          <el-table-column label="匹配方式" width="110">
            <template #default="{ row }">{{ row.matchSource }}</template>
          </el-table-column>
          <el-table-column label="转换" min-width="120">
            <template #default="{ row }">{{ transformLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="置信度" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="scoreType(row.score)">{{ Math.round(row.score * 100) }}%</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="center">
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
const pageSize = 8

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

function onSourceClick(node: FieldNode) {
  selectedSource.value = node
}

function onTargetClick(node: FieldNode) {
  const src = selectedSource.value
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

function fillLeafRow(source: string, target: string) {
  const row = rows.find(r => r.target.trim() === target) ?? rows.find(r => !r.target.trim())
  if (row) {
    row.source = source
    row.target = target
    row.sourceStep = defaultStep()
  } else {
    rows.push(createRow(defaultStep(), source, target, 'STRING'))
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
  if (s.transform === 'ENUM' && s.enumMap) return `枚举（${Object.keys(s.enumMap).length} 项）`
  if (s.transform === 'DATE' && s.datePattern) return `日期 ${s.datePattern}`
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
    ElMessage.success(`目标字段已导入：${targetFields.value.join(', ')}`)
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function fetchSuggestions() {
  if (!targetFields.value.length) {
    ElMessage.warning('请先导入目标字段')
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

/** 目标树里的数组节点路径（如 bom.bomLines）——参与组建议。 */
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
  ElMessage.success(`一键自动填充 ${filled} 行（只补空行）`)
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
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.hint { font-size: 12px; color: #888; }
.trees { display: flex; gap: 12px; }
.tree-box { flex: 1; border: 1px solid #e5e7eb; border-radius: 6px; padding: 6px; }
.tree-title { font-size: 12px; color: #555; margin-bottom: 4px; }
.field-tree { max-height: 220px; overflow: auto; }
.table-toolbar { display: flex; align-items: center; gap: 8px; }
.pick-hint { font-size: 12px; color: #409eff; }
.suggest-panel { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; }
.suggest-title { font-size: 12px; color: #555; margin-bottom: 4px; }
.suggest-pager { margin-top: 6px; justify-content: flex-end; }
.src-path { font-family: Consolas, monospace; font-size: 12px; }
.target-adopted { color: #67c23a; font-weight: 600; }
</style>
