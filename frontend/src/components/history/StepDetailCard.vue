<template>
  <!-- 单步卡（#131 / 原型 `.card[data-od-id=step-detail]`）：
       票面要的五样——状态、动作、尝试次数、时刻、错误块。
       快照面板（#132）与「与上一步对照」（#133）落在这张卡里，由那两票往下面接。 -->
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
import { stepCardView } from '../../history/console'
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 当前选中的那一步（`null` = 还没有可看的步骤，整张卡不铺）。 */
  step: MessageStep | null
}>()

const card = computed(() => stepCardView(props.step))
</script>
