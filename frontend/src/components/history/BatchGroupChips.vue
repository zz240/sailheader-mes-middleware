<template>
  <!-- 一组 chip（#139）：组头 + 同批每一行一颗。组头那句由 `history/batch.ts` 判（组在这里不判口径） -->
  <div class="hc-batch" :data-batch-step="group.step">
    <div class="hc-bhead">
      <span class="hc-bt">{{ group.head }}</span>
    </div>
    <div class="hc-bchips">
      <button
        v-for="member in group.members"
        :key="member.id"
        class="hc-mchip"
        type="button"
        :aria-label="`消息 ${shortId(member.messageId)} · ${businessKeyOf(member)} · ${statusChip(member.status).label}`"
        @click="emit('select-step', member)"
      >
        <span class="hc-mid">{{ shortId(member.messageId) }}</span>
        <span class="hc-mbk">{{ businessKeyOf(member) }}</span>
        <span class="hc-st" :class="`hc-st-${statusChip(member.status).kind}`">{{ statusChip(member.status).label }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { type BatchGroup } from '../../history/batch'
import { NO_BUSINESS_KEY, shortId, statusChip } from '../../history/console'
import type { MessageStep } from '../../types'

defineProps<{
  /** 链上点开的那一组（分组口径全在 `history/batch.ts`，这里只管画）。 */
  group: BatchGroup
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
}>()

/**
 * 摘要行没有对应的消息、业务键也常常是空的——与消息 chip 同一句「无业务键」，不另立写法。
 *
 * 摘要行自己那条 chip（写「批次」+ 短号，ADR-0017 决策 6）等**合并**那一票（#142）铺：
 * 这一票的库里产不出摘要行，铺出来也永远走不到。
 */
function businessKeyOf(member: MessageStep): string {
  return member.businessKey?.trim() || NO_BUSINESS_KEY
}
</script>
