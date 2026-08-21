<template>
  <div class="mapping-editor">
    <el-input v-model="sourceSample" type="textarea" :rows="2"
              placeholder='来源样例 JSON，如 {"order_no":"PO-1","data":{"items":[{"name":"x"}]}}' />
    <div class="row">
      <el-input v-model="targetSample" placeholder='目标字段样例 JSON，如 {"sheet_no":"PO-1","status":1}' style="width: 360px" />
      <el-button @click="importTargetFields">导入目标字段</el-button>
      <el-button type="primary" @click="fetchSuggestions">获取推荐</el-button>
      <el-button type="primary" @click="autoFill">一键自动填充</el-button>
      <el-button @click="emit('validate')">校验任务</el-button>
    </div>

    <el-table :data="rows" border size="small">
      <el-table-column label="来源步骤" width="120">
        <template #default="{ row }">
          <el-select v-model="row.sourceStep" placeholder="来源动作">
            <el-option v-for="s in sourceSteps" :key="s" :value="s" :label="s === 0 ? '任务输入' : `动作 ${s}`" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="来源字段">
        <template #default="{ row }">
          <el-input v-model="row.source" list="field-paths" placeholder="来源字段路径" />
        </template>
      </el-table-column>
      <el-table-column label="目标字段">
        <template #default="{ row }">
          <el-input v-model="row.target" list="target-list" placeholder="目标字段" />
        </template>
      </el-table-column>
      <el-table-column label="转换" width="120">
        <template #default="{ row }">
          <el-select v-model="row.transform">
            <el-option v-for="t in transforms" :key="t" :value="t" :label="t" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="常量/枚举/日期">
        <template #default="{ row }">
          <el-input v-model="row.extra" placeholder="常量值 / enumMap JSON / yyyy-MM-dd" />
        </template>
      </el-table-column>
      <el-table-column width="70">
        <template #default="{ $index }">
          <el-button size="small" type="danger" @click="rows.splice($index, 1)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button size="small" @click="addRow(defaultStep())">添加映射行</el-button>

    <datalist id="field-paths"><option v-for="p in fieldPaths" :key="p" :value="p" /></datalist>
    <datalist id="target-list"><option v-for="t in targetFields" :key="t" :value="t" /></datalist>

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
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { MappingSuggestion, ProbeResult } from '../types'

const props = defineProps<{ jobId: number; sourceSteps?: number[]; previousStep?: number }>()
const emit = defineEmits<{ (e: 'validate'): void }>()

const transforms = ['STRING', 'INT', 'DATE', 'ENUM', 'CONSTANT']
const rows = reactive<{ sourceStep: number; source: string; target: string; transform: string; extra: string }[]>([])
const sourceSample = ref('')
const targetSample = ref('')
const fieldPaths = ref<string[]>([])
const targetFields = ref<string[]>([])
const suggestions = ref<MappingSuggestion[]>([])
const suggestPage = ref(1)
const pageSize = 8

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

function addRow(sourceStep: number, source = '', target = '', transform = 'STRING', extra = '') {
  rows.push({ sourceStep, source, target, transform, extra })
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
        targets: targetFields.value.map(name => ({ name, required: true })),
      }),
    })
    suggestPage.value = 1
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function applySuggestion(s: MappingSuggestion) {
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

function getRules() {
  const rules: Record<string, any>[] = []
  rows.forEach((r, idx) => {
    if (!r.target.trim()) return
    const rule: Record<string, any> = {
      sourceStep: r.sourceStep,
      source: r.source.trim(),
      target: r.target.trim(),
      transform: r.transform,
    }
    const extra = r.extra.trim()
    if (r.transform === 'CONSTANT') rule.constant = extra
    if (r.transform === 'ENUM' && extra) {
      try {
        rule.enumMap = JSON.parse(extra)
      } catch {
        throw new Error(`第 ${idx + 1} 行映射：枚举映射必须是合法 JSON，如 {"1":"已处理"}，当前输入：${extra}`)
      }
    }
    if (r.transform === 'DATE' && extra) rule.datePattern = extra
    rules.push(rule)
  })
  return rules
}

function setRules(rules: Record<string, any>[]) {
  rows.length = 0
  rules.forEach(r => addRow(r.sourceStep ?? defaultStep(), r.source ?? '', r.target ?? '', r.transform ?? 'STRING', extraOf(r)))
}

function extraOf(rule: Record<string, any>) {
  if (rule.constant != null) return String(rule.constant)
  if (rule.enumMap != null) return JSON.stringify(rule.enumMap)
  if (rule.datePattern != null) return String(rule.datePattern)
  return ''
}

function defaultStep() {
  if (props.previousStep != null) return props.previousStep
  const steps = props.sourceSteps ?? []
  return steps.length ? steps[steps.length - 1] : 0
}

defineExpose({ getRules, setRules, loadSampleFields })
</script>

<style scoped>
.mapping-editor { display: flex; flex-direction: column; gap: 8px; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.suggest-panel { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; }
.suggest-title { font-size: 12px; color: #555; margin-bottom: 4px; }
.suggest-pager { margin-top: 6px; justify-content: flex-end; }
.src-path { font-family: Consolas, monospace; font-size: 12px; }
.target-adopted { color: #67c23a; font-weight: 600; }
</style>
