<template>
  <!-- 单步卡（#131 / 原型 `.card[data-od-id=step-detail]`）：票面要的五样——状态、动作、尝试次数、时刻、错误块；
       #132 把快照面板接进来（原型里工具栏与两块快照就排在错误块之前）。
       「与上一步对照」（#133）落在同一个工具栏上。 -->
  <div v-if="card" class="hc-card" :aria-label="`步骤 ${card.n} 的单步卡`">
    <div class="hc-sc-head">
      <span class="hc-sn">步骤 {{ card.n }}</span>
      <span class="hc-sa">{{ card.action }}</span>
      <span class="hc-st" :class="`hc-st-${card.chip.kind}`">{{ card.chip.label }}</span>
      <span class="hc-sc-right">
        <span v-for="fact in card.facts" :key="fact.label" class="hc-fact">
          {{ fact.label }} <b>{{ fact.value }}</b>
        </span>
      </span>
    </div>

    <!-- 快照面板（#132）：这一票只做两块快照的显示侧降级，不碰 #127（写库那条可靠性的路） -->
    <SnapshotPanel
      :step-row="stepRow"
      :loading="stepRowLoading"
      :error="stepRowError"
      @retry="emit('retry-step-row')"
    />

    <!-- 错误块：**原样保留换行**（堆栈直接贴给开发）；成功步没有错误 → 整块不铺，不留空块 -->
    <div v-if="card.error" class="hc-step-err">
      <div class="hc-eh">
        <span>错误</span>
        <span class="hc-eh-w">{{ card.action }}</span>
        <span class="hc-eh-at">{{ card.at }}</span>
      </div>
      <pre><b>{{ card.errorFirst }}</b>{{ card.errorRest }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import SnapshotPanel from './SnapshotPanel.vue'
import { stepCardView } from '../../history/console'
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 归一之后那一步（索引那条路上的行：有快照落没落两个布尔，没有快照原文）。`null` = 整张卡不铺。 */
  step: MessageStep | null
  /**
   * 这一步的**整行**（按 id 另取回来，带两份快照原文）：索引那条路上的行没有快照原文，
   * 所以它是一次单独的请求（#132），三态由下面两格管。与上面那个 `step` 不是同一个东西。
   */
  stepRow: MessageStep | null
  stepRowLoading: boolean
  stepRowError: string
}>()

const emit = defineEmits<{ 'retry-step-row': [] }>()

const card = computed(() => stepCardView(props.step))
</script>
