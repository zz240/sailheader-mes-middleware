<template>
  <div class="rows-table-wrap">
    <el-table
      :data="rows"
      border
      size="small"
      row-key="id"
      highlight-current-row
      :current-row-key="currentRowKey"
      :expand-row-keys="expandedKeys"
      @expand-change="onExpandChange"
      @current-change="onCurrentChange"
      class="rows-table"
    >
      <el-table-column type="expand" width="34">
        <template #default="{ row }">
          <div v-if="row.iterate === 'FOR_EACH'" class="child-panel">
            <div class="child-toolbar">
              <span class="child-hint">
                {{ child ? '第 ' + ((level ?? 0) + 2) + ' 层：' : '' }}来源相对当前数组元素；$index 生成行号
              </span>
              <el-tooltip content="在该数组元素内再添加一个子数组映射（支持多层嵌套）" placement="top">
                <el-button size="small" @click="addChild(row, 'GROUP')">+ 数组组</el-button>
              </el-tooltip>
              <el-tooltip content="添加一行字段映射（来源填当前元素的字段名）" placement="top">
                <el-button size="small" type="primary" plain @click="addChild(row, 'STRING')">+ 字段</el-button>
              </el-tooltip>
              <el-tooltip content="按数组下标生成行号（从 1 开始，可在参数列改起始值）" placement="top">
                <el-button size="small" @click="addChild(row, 'INDEX')">+ 行号</el-button>
              </el-tooltip>
            </div>
            <MappingRowsTable
              :rows="row.children"
              child
              :level="(level ?? 0) + 1"
              :source-steps="sourceSteps"
              :child-paths="childPathsOf(row)"
              :target-fields="targetFields"
              :source-sample="sourceSample"
              :parent-source="join(parentSource, row.source)"
              :parent-target="join(parentTarget, row.target)"
              :highlight-id="highlightId"
              @row-select="emitSelect"
            />
          </div>
          <div v-else class="leaf-hint">叶子映射行，无子层级</div>
        </template>
      </el-table-column>

      <el-table-column v-if="!child" label="来源步骤" width="104">
        <template #default="{ row }">
          <el-select v-model="row.sourceStep" placeholder="来源动作" size="small">
            <el-option v-for="s in sourceSteps" :key="s" :value="s" :label="s === 0 ? '任务输入' : `动作 ${s}`" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="来源字段" min-width="140">
        <template #default="{ row }">
          <el-select
            v-model="row.source"
            filterable
            allow-create
            default-first-option
            size="small"
            :disabled="row.transform === 'INDEX'"
            :placeholder="row.transform === 'INDEX' ? '$index' : (child ? '相对路径' : '来源字段')"
            style="width: 100%"
          >
            <el-option v-for="p in (child ? childPaths : fieldPaths)" :key="p" :value="p" :label="p" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="目标字段" min-width="120">
        <template #default="{ row }">
          <el-select
            v-model="row.target"
            filterable
            allow-create
            default-first-option
            size="small"
            placeholder="目标字段"
            style="width: 100%"
          >
            <el-option v-for="t in targetFields" :key="t" :value="t" :label="t" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="转换" width="100">
        <template #default="{ row }">
          <el-select v-model="row.transform" size="small">
            <el-option v-for="t in transforms" :key="t" :value="t" :label="t" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="参数" min-width="130">
        <template #default="{ row }">
          <el-input v-if="row.transform === 'CONSTANT'" v-model="row.extra" size="small" placeholder="常量值" />
          <el-input v-else-if="row.transform === 'DATE'" v-model="row.extra" size="small" placeholder="yyyy-MM-dd" />
          <el-input-number
            v-else-if="row.transform === 'INDEX'"
            :model-value="row.startAt ?? 1"
            :min="0"
            size="small"
            @update:model-value="(v: number | null) => (row.startAt = v ?? 1)"
            style="width: 100%"
          />
          <el-tooltip v-else-if="row.transform === 'ENUM'" content="配置来源值→目标值的映射表" placement="top">
            <el-button size="small" @click="openEnum(row)">
              {{ row.enumMap && Object.keys(row.enumMap).length ? `枚举(${Object.keys(row.enumMap).length})` : '编辑枚举' }}
            </el-button>
          </el-tooltip>
          <el-tooltip v-else-if="row.transform === 'CONCAT'" content="拼接：常量 + 来源字段片段" placement="top">
            <el-button size="small" @click="openConcat(row)">
              {{ row.parts.length ? `拼接(${row.parts.length})` : '编辑拼接' }}
            </el-button>
          </el-tooltip>
          <span v-else class="param-none">—</span>
        </template>
      </el-table-column>

      <el-table-column width="58" align="center">
        <template #default="{ $index }">
          <el-tooltip content="删除这一行" placement="top">
            <el-button size="small" type="danger" text @click="rows.splice($index, 1)">删</el-button>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="enumVisible" title="编辑枚举映射" width="460px" append-to-body>
      <div class="dialog-hint">来源值出现时替换为目标值；留空键的一行会被忽略</div>
      <div v-for="(e, i) in enumRows" :key="i" class="kv-row">
        <el-input v-model="e.key" placeholder="来源值" size="small" />
        <span class="kv-arrow">→</span>
        <el-input v-model="e.value" placeholder="目标值" size="small" />
        <el-button size="small" type="danger" text @click="enumRows.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="enumRows.push({ key: '', value: '' })">添加一行</el-button>
      <template #footer>
        <el-button @click="enumVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEnum">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="concatVisible" title="编辑拼接片段" width="520px" append-to-body>
      <div class="dialog-hint">片段按顺序拼接：字符串常量 或 来源字段路径（组内为相对路径）</div>
      <div v-for="(p, i) in concatParts" :key="i" class="kv-row">
        <el-select v-model="p.type" style="width: 104px" size="small">
          <el-option value="const" label="常量" />
          <el-option value="path" label="来源字段" />
        </el-select>
        <el-input v-model="p.value" :placeholder="p.type === 'const' ? '如 BOM-' : '如 productCode'" size="small" />
        <el-button size="small" type="danger" text @click="concatParts.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" @click="concatParts.push({ type: 'const', value: '' })">添加片段</el-button>
      <template #footer>
        <el-button @click="concatVisible = false">取消</el-button>
        <el-button type="primary" @click="saveConcat">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { createGroupRow, createRow, type MapRow } from '../mapping/rows'

const props = defineProps<{
  rows: MapRow[]
  child?: boolean
  level?: number
  sourceSteps: number[]
  childPaths: string[]
  targetFields: string[]
  sourceSample?: any
  fieldPaths?: string[]
  parentSource?: string
  parentTarget?: string
  highlightId?: number | null
}>()

const emit = defineEmits<{
  (e: 'row-select', info: { id: number; sourcePath: string; targetPath: string }): void
}>()

const baseTransforms = ['STRING', 'INT', 'DATE', 'ENUM', 'CONSTANT', 'CONCAT']
const transforms = computed(() => (props.child ? [...baseTransforms, 'INDEX'] : baseTransforms))
const expandedKeys = ref<string[]>([])
const currentRowKey = ref<number | null>(props.highlightId ?? null)

watch(() => props.highlightId, id => {
  if (id == null) return
  // 本层包含该行才设置 current-row-key（不包含时保持原状，由父层表格负责高亮）
  if (props.rows.some(r => r.id === id)) currentRowKey.value = id
})

function onExpandChange(_row: MapRow, expanded: MapRow[]) {
  expandedKeys.value = expanded.map(r => String(r.id))
}

function addChild(group: MapRow, kind: 'STRING' | 'INDEX' | 'GROUP') {
  if (kind === 'INDEX') {
    group.children.push(createRow(null, '$index', '', 'INDEX', '', 1))
  } else if (kind === 'GROUP') {
    group.children.push(createGroupRow(null, '', ''))
  } else {
    group.children.push(createRow(null, '', '', 'STRING'))
  }
}

function onCurrentChange(row: MapRow | null) {
  if (!row) return
  emitSelect({
    id: row.id,
    sourcePath: row.transform === 'INDEX' ? '' : join(props.parentSource, row.source),
    targetPath: join(props.parentTarget, row.target),
  })
}

function emitSelect(info: { id: number; sourcePath: string; targetPath: string }) {
  emit('row-select', info)
}

function join(prefix: string | undefined, path: string) {
  if (!path) return ''
  return prefix ? `${prefix}.${path}` : path
}

/** 组内来源补全：取来源样例中组数组首元素的所有叶子路径（相对路径，支持嵌套前缀）。 */
function childPathsOf(group: MapRow): string[] {
  const arrayPath = join(props.parentSource, group.source)
  const node = lookupPath(props.sourceSample, arrayPath)
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
.rows-table-wrap { width: 100%; }
.rows-table { width: 100%; }
.child-panel { padding: 6px 10px; background: #fafafa; }
.child-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.child-hint { font-size: 12px; color: #888; margin-right: auto; }
.leaf-hint { padding: 6px 10px; font-size: 12px; color: #bbb; }
.dialog-hint { font-size: 12px; color: #888; margin-bottom: 8px; }
.kv-row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.kv-arrow { color: #999; }
.param-none { color: #bbb; }
</style>
