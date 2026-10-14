<template>
  <!-- 一组 chip（#139）：组头 + 同批每一行一颗。组头那句与「能不能重跑任务」都由 `history/batch.ts` 判
       （组在这里不判口径）。「重跑任务」的落点：**一个批号只铺一颗**，落在「该批号里还带着打包消息的
       **最深**那一步」那一组（判据见 `history/batch.ts` 的 `BatchGroup#rerunStep` 与文件头第 5 条），
       且只在该批失败时铺（#147）。合并那一步那一组虽然长着打包消息那一行，但**不铺**——多出口也只有一颗
       （再往后的还原/写回那几组里，最深的那个带打包消息的步才是落点）。 -->
  <div class="hc-batch" :data-batch-step="group.step">
    <div class="hc-bhead">
      <span class="hc-bt">{{ group.head }}</span>
      <button
        v-if="rerun"
        class="hc-brerun"
        type="button"
        :disabled="pending"
        :aria-label="`${rerun.label}：从这一批未完成的位置整批重走，目标端会再收到一次该批请求`"
        @click="emit('rerun-job', rerun)"
      >{{ rerun.label }}</button>
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
import { computed } from 'vue'
import { batchRerun, type BatchGroup, type BatchRerun } from '../../history/batch'
import { NO_BUSINESS_KEY, shortId, statusChip } from '../../history/console'
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 链上点开的那一组（分组口径全在 `history/batch.ts`，这里只管画）。 */
  group: BatchGroup
  /** 这一批的重跑在路上（按钮按在原地，别让人连点两下发两遍）。 */
  pending?: boolean
  /** 当前这条运行的**任务标识**（`run.jobId`）；取不到就不铺按钮——重跑是任务级的动作，跑谁得说得出来。 */
  jobId?: number | null
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
  'rerun-job': [target: BatchRerun]
}>()

/** 能不能铺、铺什么、确认框里问什么，全在纯模块里判（这里不重写判据）。 */
const rerun = computed(() => batchRerun(props.group, props.jobId ?? null))

/**
 * 这一行是不是**打包消息**：与组头那颗按钮同一处判据算出来的那一行（按行 id 认，同组里只有它一颗）。
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
