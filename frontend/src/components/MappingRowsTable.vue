<template>
  <el-table
    :data="rows"
    border
    size="small"
    row-key="id"
    :expand-row-keys="expandedKeys"
    @expand-change="onExpandChange"
  >
    <el-table-column v-if="!child" type="expand" width="36">
      <template #default="{ row }">
        <div v-if="row.iterate === 'FOR_EACH'" class="child-panel">
          <div class="child-toolbar">
            <span class="child-hint">组内子映射（来源相对当前数组元素；$index 生成行号）</span>
            <el-button size="small" type="primary" plain @click="addChild(row, 'STRING')">添加子映射</el-button>
            <el-button size="small" @click="addChild(row, 'INDEX')">添加行号</el-button>
          </div>
          <MappingRowsTable
            :rows="row.children"
            child
            :source-steps="sourceSteps"
            :child-paths="childPathsOf(row)"
            :target-fields="targetFields"
            :source-sample="sourceSample"
          />
        </div>
      </template>
    </el-table-column>

    <el-table-column v-if="!child" label="来源步骤" width="120">
      <template #default="{ row }">
        <el-select v-model="row.sourceStep" placeholder="来源动作">
          <el-option v-for="s in sourceSteps" :key="s" :value="s" :label="s === 0 ? '任务输入' : `动作 ${s}`" />
        </el-select>
      </template>
    </el-table-column>

    <el-table-column label="来源字段" min-width="150">
      <template #default="{ row }">
        <el-select
          v-model="row.source"
          filterable
          allow-create
          default-first-option
          :disabled="row.transform === 'INDEX'"
          :placeholder="row.transform === 'INDEX' ? '$index（自动行号）' : (child ? '相对路径，如 materialCode' : '来源字段路径')"
          style="width: 100%"
        >
          <el-option v-for="p in (child ? childPaths : fieldPaths)" :key="p" :value="p" :label="p" />
        </el-select>
      </template>
    </el-table-column>

    <el-table-column label="目标字段" min-width="130">
      <template #default="{ row }">
        <el-select
          v-model="row.target"
          filterable
          allow-create
          default-first-option
          placeholder="目标字段"
          style="width: 100%"
        >
          <el-option v-for="t in targetFields" :key="t" :value="t" :label="t" />
        </el-select>
      </template>
    </el-table-column>

    <el-table-column label="转换" width="120">
      <template #default="{ row }">
        <el-select v-model="row.transform">
          <el-option v-for="t in transforms" :key="t" :value="t" :label="t" />
        </el-select>
      </template>
    </el-table-column>

    <el-table-column label="参数" min-width="150">
      <template #default="{ row }">
        <el-input v-if="row.transform === 'CONSTANT'" v-model="row.extra" placeholder="常量值" />
        <el-input v-else-if="row.transform === 'DATE'" v-model="row.extra" placeholder="yyyy-MM-dd" />
        <el-input-number
          v-else-if="row.transform === 'INDEX'"
          :model-value="row.startAt ?? 1"
          :min="0"
          @update:model-value="(v: number | null) => (row.startAt = v ?? 1)"
          style="width: 100%"
        />
        <el-button v-else-if="row.transform === 'ENUM'" size="small" @click="openEnum(row)">
          {{ row.enumMap && Object.keys(row.enumMap).length ? `枚举（${Object.keys(row.enumMap).length} 项）` : '编辑枚举' }}
        </el-button>
        <el-button v-else-if="row.transform === 'CONCAT'" size="small" @click="openConcat(row)">
          {{ row.parts.length ? `拼接（${row.parts.length} 段）` : '编辑拼接' }}
        </el-button>
        <span v-else class="param-none">—</span>
      </template>
    </el-table-column>

    <el-table-column width="70" align="center">
      <template #default="{ $index }">
        <el-button size="small" type="danger" @click="rows.splice($index, 1)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="enumVisible" title="编辑枚举映射" width="460px" append-to-body>
    <div v-for="(e, i) in enumRows" :key="i" class="kv-row">
      <el-input v-model="e.key" placeholder="来源值" />
      <span class="kv-arrow">→</span>
      <el-input v-model="e.value" placeholder="目标值" />
      <el-button size="small" type="danger" @click="enumRows.splice(i, 1)">删</el-button>
    </div>
    <el-button size="small" @click="enumRows.push({ key: '', value: '' })">添加一行</el-button>
    <template #footer>
      <el-button @click="enumVisible = false">取消</el-button>
      <el-button type="primary" @click="saveEnum">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="concatVisible" title="编辑拼接片段" width="520px" append-to-body>
    <div class="concat-hint">片段按顺序拼接：字符串常量 或 来源字段路径（组内为相对路径）</div>
    <div v-for="(p, i) in concatParts" :key="i" class="kv-row">
      <el-select v-model="p.type" style="width: 110px">
        <el-option value="const" label="常量" />
        <el-option value="path" label="来源字段" />
      </el-select>
      <el-input v-model="p.value" :placeholder="p.type === 'const' ? '如 BOM-' : '如 product.productCode'" />
      <el-button size="small" type="danger" @click="concatParts.splice(i, 1)">删</el-button>
    </div>
    <el-button size="small" @click="concatParts.push({ type: 'const', value: '' })">添加片段</el-button>
    <template #footer>
      <el-button @click="concatVisible = false">取消</el-button>
      <el-button type="primary" @click="saveConcat">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { createRow, type MapRow } from '../mapping/rows'

const props = defineProps<{
  rows: MapRow[]
  child?: boolean
  sourceSteps: number[]
  childPaths: string[]
  targetFields: string[]
  sourceSample?: any
  fieldPaths?: string[]
}>()

const baseTransforms = ['STRING', 'INT', 'DATE', 'ENUM', 'CONSTANT', 'CONCAT']
const transforms = computed(() => (props.child ? [...baseTransforms, 'INDEX'] : baseTransforms))
const expandedKeys = ref<string[]>([])

function onExpandChange(_row: MapRow, expanded: MapRow[]) {
  expandedKeys.value = expanded.map(r => String(r.id))
}

function addChild(group: MapRow, kind: 'STRING' | 'INDEX') {
  if (kind === 'INDEX') {
    group.children.push(createRow(null, '$index', '', 'INDEX', '', 1))
  } else {
    group.children.push(createRow(null, '', '', 'STRING'))
  }
}

/** 组内来源补全：取来源样例中 group.source 数组首元素的所有叶子路径（相对路径）。 */
function childPathsOf(group: MapRow): string[] {
  const node = lookupPath(props.sourceSample, group.source)
  const first = Array.isArray(node) ? node[0] : null
  if (first == null || typeof first !== 'object') return []
  const out: string[] = []
  collectLeafPaths(first, '', out)
  return out
}

function lookupPath(obj: any, path: string): any {
  if (obj == null || !path) return obj
  return path.split('.').reduce((acc, seg) => (acc == null ? undefined : acc[seg]), obj)
}

function collectLeafPaths(obj: any, prefix: string, out: string[]) {
  if (obj == null || typeof obj !== 'object') return
  Object.entries(obj).forEach(([k, v]) => {
    const path = prefix ? `${prefix}.${k}` : k
    if (Array.isArray(v)) {
      const first = v[0]
      if (first != null && typeof first === 'object') collectLeafPaths(first, path, out)
      else out.push(path)
    } else if (v != null && typeof v === 'object') {
      collectLeafPaths(v, path, out)
    } else {
      out.push(path)
    }
  })
}

// ---------- ENUM 弹窗 ----------
const enumVisible = ref(false)
const enumRows = reactive<{ key: string; value: string }[]>([])
let enumTarget: MapRow | null = null

function openEnum(row: MapRow) {
  enumTarget = row
  enumRows.length = 0
  Object.entries(row.enumMap ?? {}).forEach(([k, v]) => enumRows.push({ key: k, value: String(v) }))
  if (!enumRows.length) enumRows.push({ key: '', value: '' })
  enumVisible.value = true
}

function saveEnum() {
  if (!enumTarget) return
  const map: Record<string, any> = {}
  enumRows.forEach(e => {
    if (e.key.trim()) map[e.key.trim()] = e.value
  })
  enumTarget.enumMap = map
  enumVisible.value = false
}

// ---------- CONCAT 弹窗 ----------
const concatVisible = ref(false)
const concatParts = reactive<{ type: 'const' | 'path'; value: string }[]>([])
let concatTarget: MapRow | null = null

function openConcat(row: MapRow) {
  concatTarget = row
  concatParts.length = 0
  row.parts.forEach(p => concatParts.push({ type: p.type, value: p.value }))
  if (!concatParts.length) concatParts.push({ type: 'const', value: '' })
  concatVisible.value = true
}

function saveConcat() {
  if (!concatTarget) return
  concatTarget.parts = concatParts
    .filter(p => p.value.trim() !== '')
    .map(p => ({ type: p.type, value: p.value }))
  concatVisible.value = false
}
</script>

<style scoped>
.child-panel { padding: 6px 12px; background: #fafafa; }
.child-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.child-hint { font-size: 12px; color: #888; margin-right: auto; }
.kv-row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.kv-arrow { color: #999; }
.concat-hint { font-size: 12px; color: #888; margin-bottom: 8px; }
.param-none { color: #bbb; }
</style>
