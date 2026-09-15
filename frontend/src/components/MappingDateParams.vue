<script setup lang="ts">
/**
 * 日期格式化的参数编辑器（#111 手输 + 四个预设，#112 起行级与片段级共用这一份）。
 *
 * 它只管「这个格式串怎么改」：写回哪里由调用方决定（行上是 `row.extra`，片段上是 `part.datePattern`）。
 * 判断对错一条都不在这里（#98 D1）——前端只装请求，格式串合不合法由「校验配置」那条只读接口判。
 */
import { DATE_PRESETS, DEFAULT_DATE_PATTERN, normalizeDatePattern } from '../mapping/transforms.ts'

const props = defineProps<{
  /** 当前生效的格式串（空串表示没写，按默认值算）。 */
  pattern: string
  /** 键盘停在哪个预设上（行级面板用 ↑↓ 走选项时给；片段级不给）。 */
  optionIndex?: number
}>()

const emit = defineEmits<{
  /** 手输框里每打一个字。 */
  (e: 'update:pattern', value: string): void
  /** 手输框失焦 / 回车：该归一了（清空回落到默认格式）。 */
  (e: 'commit'): void
  /** 点了某个预设。 */
  (e: 'pick', pattern: string): void
}>()

/** 这一行/这个片段现在生效的格式串（没写就是默认值）——与摘要里显示的是同一个。 */
function current(): string {
  return normalizeDatePattern(props.pattern)
}

/** 预设的样例：拿一个真日期格式化一遍，让人一眼看出这个模式长什么样。 */
function datePatternExample(pattern: string): string {
  return formatExample(new Date(2026, 7, 21, 9, 5, 7), pattern)
}

/** 只支持后端那几个预设与常见日期记号；认不出来就原样返回模式串。 */
function formatExample(date: Date, pattern: string): string {
  const pad = (n: number, width = 2) => String(n).padStart(width, '0')
  const parts: Record<string, string> = {
    yyyy: String(date.getFullYear()),
    yy: String(date.getFullYear()).slice(-2),
    MM: pad(date.getMonth() + 1),
    dd: pad(date.getDate()),
    HH: pad(date.getHours()),
    mm: pad(date.getMinutes()),
    ss: pad(date.getSeconds()),
  }
  return pattern.replace(/yyyy|yy|MM|dd|HH|mm|ss/g, token => parts[token] ?? token)
}
</script>

<template>
  <div class="wb-ops">
    <button
      v-for="(p, i) in DATE_PRESETS"
      :key="p"
      class="wb-opbtn"
      :class="{ current: p === current(), 'kb-current': i === optionIndex }"
      @click="emit('pick', p)"
    >
      <span>{{ p }}</span>
      <small>{{ datePatternExample(p) }}</small>
    </button>
  </div>
  <div class="wb-panel-row">
    <el-input
      :model-value="pattern"
      size="small"
      class="wb-grow"
      placeholder="客户数据的格式不在这四个里？直接手输一个，例如 yyyyMMddHHmmss"
      @update:model-value="emit('update:pattern', $event)"
      @change="emit('commit')"
    />
    <span class="wb-panel-note">
      清空就回落到默认的 {{ DEFAULT_DATE_PATTERN }}；格式串对不对由「校验配置」判。
    </span>
  </div>
  <div class="wb-panel-note">目标系统收到的就是按这个格式串格式化后的文本。</div>
</template>
