<script setup lang="ts">
/**
 * 枚举映射的参数编辑器（#112 起行级与片段级共用这一份）：一张「来源值 → 目标值」的表。
 *
 * 表格里改的每一格立刻回写（行上是 `row.enumMap`，片段上是 `part.enumMap`），组件自己不存副本——
 * 唯一副本在行/片段那儿，读的写的都是它。
 */
import { computed } from 'vue'
import { appendEnumSamples, missingEnumKeys, setEnumKey, type EnumPair } from '../mapping/transforms.ts'
import { realEnumMap, ENUM_PLACEHOLDER } from '../mapping/rows.ts'
import type { SourceField } from '../mapping/editor-model.ts'

const props = defineProps<{
  /** 当前生效的键值表（可能带还没填键的占位格）。 */
  map: Record<string, any> | null | undefined
  /** 这一行/这个片段来源字段的样例值（「按样例补全」用；拿不到就不给补全按钮）。 */
  sampleField?: SourceField
}>()

const emit = defineEmits<{
  (e: 'update:map', value: Record<string, any>): void
}>()

/** 表里的行：行的身份 = 它在表里的位置（键会随用户输入变，拿键当 key 会让正在输入的那行被重建）。 */
const pairs = computed<EnumPair[]>(() => {
  const map = props.map ?? {}
  return Object.keys(map).map((key, i) => ({
    id: i,
    key: key.startsWith(ENUM_PLACEHOLDER) ? '' : key,
    value: String(map[key] ?? ''),
  }))
})

/** 表格里改了一格：把这一格并进整张表（值照抄，键按已填的算）。 */
function setPair(index: number, patch: Partial<Pick<EnumPair, 'key' | 'value'>>) {
  const base = pairs.value.map((pair, i) => (i === index ? { ...pair, ...patch } : pair))
  // 改键撞上已有的键时按「后改的赢」合并（setEnumKey 负责把被撞掉的那行去掉）
  emit('update:map', mapOf(patch.key === undefined ? base : setEnumKey(base, index, patch.key)))
}

function mapOf(list: EnumPair[]): Record<string, any> {
  const map: Record<string, any> = {}
  list.forEach((pair, i) => {
    const key = pair.key.trim()
    // 空键的两条会互相覆盖，用占位键各占一个位置，等用户填上真键
    map[key || `${ENUM_PLACEHOLDER}${i}`] = pair.value
  })
  return map
}

function addRow() {
  emit('update:map', mapOf([...pairs.value, { id: pairs.value.length, key: '', value: '' }]))
}

function removeRow(index: number) {
  emit('update:map', mapOf(pairs.value.filter((_, i) => i !== index)))
}

/** 样例里出现过、表里还没有的来源值（空键的占位行不算）。 */
const missing = computed(() => missingEnumKeys(realEnumMap(props.map), props.sampleField))

const sampleHint = computed(() => (missing.value.length ? `（补 ${missing.value.length} 个）` : ''))

function fillFromSamples() {
  emit('update:map', appendEnumSamples(realEnumMap(props.map), missing.value))
}
</script>

<template>
  <table class="wb-enum">
    <thead>
      <tr><th>来源值</th><th /><th>目标值</th><th /></tr>
    </thead>
    <tbody>
      <tr v-for="(pair, i) in pairs" :key="pair.id">
        <td>
          <el-input
            :model-value="pair.key"
            size="small"
            placeholder="来源值"
            @update:model-value="setPair(i, { key: $event })"
          />
        </td>
        <td class="wb-enum-arrow">→</td>
        <td>
          <el-input
            :model-value="pair.value"
            size="small"
            placeholder="目标值"
            @update:model-value="setPair(i, { value: $event })"
          />
        </td>
        <td>
          <el-button size="small" text type="danger" @click="removeRow(i)">删</el-button>
        </td>
      </tr>
      <tr v-if="!pairs.length">
        <td colspan="4" class="wb-enum-empty">还没有键值对，点「加一行」或「按样例补全」</td>
      </tr>
    </tbody>
  </table>
  <div class="wb-panel-row">
    <el-button size="small" @click="addRow">加一行</el-button>
    <el-button size="small" :disabled="!missing.length" @click="fillFromSamples">
      按样例补全{{ sampleHint }}
    </el-button>
  </div>
  <div class="wb-panel-note">
    查不到键的来源值不会出现在目标报文里（这个字段整个消失，不报错）——样例里出现过的值最好都在表里。
  </div>
</template>
