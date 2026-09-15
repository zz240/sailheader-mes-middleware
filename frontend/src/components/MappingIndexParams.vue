<script setup lang="ts">
/**
 * 行号起始值那个小输入框（#112 起行级与片段级共用这一份）。
 *
 * 「空 → 1」这条规矩只写在这里：行级 `startAt` 与片段级 `startAt` 的默认值是同一个
 * （编译器 `indexExpr` 也按 1 算），三处各写一遍迟早会走散。
 *
 * 两边的措辞不一样（行里读作「从 N 起」、片段里读作「起始值 N」），所以标签留在各自那一侧，
 * 这里只出这个输入框。
 */
const props = defineProps<{
  /** 当前起始值；null / undefined 按 1 显示。 */
  startAt?: number | null
}>()

const emit = defineEmits<{
  /** 归一后的起始值（空就是 1），调用方直接写进行或片段。 */
  (e: 'update:startAt', value: number): void
}>()

function onChange(value: number | undefined) {
  emit('update:startAt', value == null ? 1 : Number(value))
}
</script>

<template>
  <el-input-number
    :model-value="props.startAt ?? 1"
    size="small"
    controls-position="right"
    class="wb-start-at"
    @update:model-value="onChange"
  />
</template>
