<template>
  <!-- 一组 chip（#139）：组头 + 同批每一行一颗。组头那句与「能不能重发」都由 `history/batch.ts` 判
       （组在这里不判口径）；「重发这一批（N 条）」只长在**发送批**的组头上、且只在该批失败时铺（#142） -->
  <div class="hc-batch" :data-batch-step="group.step">
    <div class="hc-bhead">
      <span class="hc-bt">{{ group.head }}</span>
      <button
        v-if="redeliverBatch"
        class="hc-bredeliver"
        type="button"
        :disabled="pending"
        :aria-label="`${redeliverBatch.label}：一次点击发一次请求，整批同成同败`"
        @click="emit('redeliver-batch', group)"
      >{{ redeliverBatch.label }}</button>
    </div>
    <div class="hc-bchips">
      <button
        v-for="member in group.members"
        :key="member.id"
        class="hc-mchip"
        :class="{ 'hc-mchip-summary': isSummaryRow(member) }"
        type="button"
        :aria-label="`${isSummaryRow(member) ? '摘要行（这一批的整份报文）' : '消息'} ${shortId(member.messageId)} · ${businessKeyOf(member)} · ${statusChip(member.status).label}`"
        @click="emit('select-step', member)"
      >
        <span v-if="isSummaryRow(member)" class="hc-msum">批次</span>
        <span class="hc-mid">{{ shortId(member.messageId) }}</span>
        <span class="hc-mbk">{{ businessKeyOf(member) }}</span>
        <span class="hc-st" :class="`hc-st-${statusChip(member.status).kind}`">{{ statusChip(member.status).label }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { batchRedeliver, isSummaryRow, type BatchGroup } from '../../history/batch'
import { NO_BUSINESS_KEY, shortId, statusChip } from '../../history/console'
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 链上点开的那一组（分组口径全在 `history/batch.ts`，这里只管画）。 */
  group: BatchGroup
  /** 这一批的重发在路上（按钮按在原地，别让人连点两下发两遍）。 */
  pending?: boolean
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
  'redeliver-batch': [group: BatchGroup]
}>()

/** 能不能重发、按钮写什么，全在纯模块里判（这里不重写判据）。 */
const redeliverBatch = computed(() => batchRedeliver(props.group))

/**
 * 摘要行没有对应的消息、业务键也常常是空的——与消息 chip 同一句「无业务键」，不另立写法。
 */
function businessKeyOf(member: MessageStep): string {
  return member.businessKey?.trim() || NO_BUSINESS_KEY
}
</script>
