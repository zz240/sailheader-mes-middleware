<template>
  <!-- 一组 chip（#139）：组头 + 同批每一行一颗。组头那句由 `history/batch.ts` 判（组在这里不判口径）。
       「重跑任务」不在组头上（#157：入口搬到失败的运行上），这里只管画这一批。 -->
  <div class="hc-batch" :data-batch-step="group.step">
    <div class="hc-bhead">
      <span class="hc-bt">{{ group.head }}</span>
    </div>
    <div class="hc-bchips">
      <button
        v-for="member in group.members"
        :key="member.id"
        class="hc-mchip"
        :class="{ 'hc-mchip-packed': isPacked(member) }"
        type="button"
        :aria-label="`${isPacked(member) ? '打包消息（合并 N→1 打出来的那条信封）' : '消息'} ${shortId(member.messageId)} · ${businessKeyOf(member)} · ${statusChip(member.status).label}`"
        @click="emit('select-step', member)"
      >
        <span v-if="isPacked(member)" class="hc-mpacked">打包报文</span>
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

const props = defineProps<{
  /** 链上点开的那一组（分组口径全在 `history/batch.ts`，这里只管画）。 */
  group: BatchGroup
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
}>()

/**
 * 这一行是不是**打包消息**：与组头同源的那一行（按行 id 认，同组里只有它一颗）。
 */
function isPacked(member: MessageStep): boolean {
  return props.group.packed?.id === member.id
}

/**
 * 打包消息没有对应的业务键（它是整批的信封）——与消息 chip 同一句「无业务键」，不另立写法。
 */
function businessKeyOf(member: MessageStep): string {
  return member.businessKey?.trim() || NO_BUSINESS_KEY
}
</script>
