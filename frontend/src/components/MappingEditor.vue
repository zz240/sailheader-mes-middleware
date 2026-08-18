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
    <el-button size="small" @click="addRow()">添加映射行</el-button>

    <datalist id="field-paths"><option v-for="p in fieldPaths" :key="p" :value="p" /></datalist>
    <datalist id="target-list"><option v-for="t in targetFields" :key="t" :value="t" /></datalist>

    <div v-if="suggestions.length" class="suggest-panel">
      <div class="suggest-title">推荐候选（点击采用填入该行）</div>
      <div v-for="(arr, target) in byTarget" :key="target" class="suggest-group">
        <b>{{ target }}</b>
        <span v-for="s in arr" :key="s.sourcePath + s.score" class="chip">
          {{ s.sourcePath }}
          <el-tag size="small">{{ Math.round(s.score * 100) }}</el-tag>
          {{ s.matchSource }} {{ s.transform }}
          <el-button size="small" @click="applySuggestion(s)">采用</el-button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { MappingSuggestion, ProbeResult } from '../types'

const props = defineProps<{ jobId: number }>()
const emit = defineEmits<{ (e: 'validate'): void }>()

const transforms = ['STRING', 'INT', 'DATE', 'ENUM', 'CONSTANT']
const rows = reactive<{ source: string; target: string; transform: string; extra: string }[]>([])
const sourceSample = ref('')
const targetSample = ref('')
const fieldPaths = ref<string[]>([])
const targetFields = ref<string[]>([])
const suggestions = ref<MappingSuggestion[]>([])

const byTarget = computed(() => {
  const map: Record<string, MappingSuggestion[]> = {}
  suggestions.value.forEach(s => {
    ;(map[s.targetName] = map[s.targetName] || []).push(s)
  })
  return map
})

function addRow(source = '', target = '', transform = 'STRING', extra = '') {
  rows.push({ source, target, transform, extra })
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
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

function applySuggestion(s: MappingSuggestion) {
  let row = rows.find(r => r.target === s.targetName)
  if (!row) {
    row = rows.find(r => !r.target)
    if (!row) {
      addRow()
      row = rows[rows.length - 1]
    }
  }
  if (row.target && row.target !== s.targetName) return
  row.source = s.sourcePath
  row.target = s.targetName
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
    const row = rows.find(r => !r.target) || (addRow(), rows[rows.length - 1])
    row.source = s.sourcePath
    row.target = s.targetName
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
  return rows
    .filter(r => r.target.trim())
    .map(r => {
      const rule: Record<string, any> = {
        source: r.source.trim(),
        target: r.target.trim(),
        transform: r.transform,
      }
      const extra = r.extra.trim()
      if (r.transform === 'CONSTANT') rule.constant = extra
      if (r.transform === 'ENUM' && extra) rule.enumMap = JSON.parse(extra)
      if (r.transform === 'DATE' && extra) rule.datePattern = extra
      return rule
    })
}

function setRules(rules: Record<string, any>[]) {
  rows.length = 0
  rules.forEach(r => addRow(r.source ?? '', r.target ?? '', r.transform ?? 'STRING',
    r.constant ?? (r.enumMap ? JSON.stringify(r.enumMap) : '') ?? r.datePattern ?? ''))
}

defineExpose({ getRules, setRules, loadSampleFields })
</script>

<style scoped>
.mapping-editor { display: flex; flex-direction: column; gap: 8px; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.suggest-panel { border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; }
.suggest-title { font-size: 12px; color: #555; margin-bottom: 4px; }
.suggest-group { margin: 4px 0; font-size: 12px; }
.chip { border: 1px solid #e5e7eb; border-radius: 4px; padding: 1px 5px; margin-right: 6px; display: inline-flex; align-items: center; gap: 4px; }
</style>
