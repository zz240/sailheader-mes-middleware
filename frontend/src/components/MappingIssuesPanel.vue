<script setup lang="ts">
/**
 * 校验结果面板（#103，从 `FieldMappingWorkbench.vue` 拆出来，见 #117）：问题列表 + 点一下跳到那一行。
 *
 * 它只画出来：问题列表与那句统计都是工作台从后端那个只读接口的结果里算好的（`draft.ts` 定位到行），
 * 这里一条判断都没有（#98 D1）。行右侧那三种状态（有错 / 有警告 / 没问题）**不在这里**——那是行列表
 * 按行画的（`issuesByRow` 的结果传给它，判据仍然是 `draft.ts` 的 `rowIssueState`）。
 * 「跳行」自己也做不了（那是选中与收起集合的事），所以发一条 `jump` 出去。
 */
import type { LocatedIssue } from '../mapping/draft.ts'

defineProps<{
  /** 这一次校验的问题：错误在前、警告在后，每条都带着它落在哪一行（`rowId` 为 null = 定位不到）。 */
  issues: LocatedIssue[]
  /** 「没有发现问题」或者「N 个错误 · M 个警告」。 */
  summary: string
  /** 这次校验里有错误吗（有错时那句统计标红）。 */
  hasErrors: boolean
}>()

const emit = defineEmits<{
  /** 点了某一条问题：跳到它那一行（定位不到行的那些点了也不动）。 */
  (e: 'jump', rowId: number | null): void
}>()

/** 一条问题在列表里显示成「在哪一行」。 */
function where(issue: LocatedIssue): string {
  if (!issue.target) return '整份配置'
  return issue.parent ? `${issue.parent} · ${issue.target}` : issue.target
}
</script>

<template>
  <div class="wb-issues">
    <div class="wb-issues-head">
      <span class="wb-panel-title">校验结果</span>
      <span class="wb-issue-summary" :class="{ bad: hasErrors }">{{ summary }}</span>
      <span class="wb-spacer" />
      <span class="wb-issues-note">改完再点一次「校验配置」</span>
    </div>
    <div v-if="!issues.length" class="wb-issues-ok">没有发现问题。</div>
    <div v-else class="wb-issue-list">
      <button
        v-for="(issue, i) in issues"
        :key="i"
        class="wb-issue"
        :class="issue.severity"
        :title="issue.rowId == null ? '这条问题定位不到某一行' : '点一下跳到这一行'"
        @click="emit('jump', issue.rowId)"
      >
        <span class="wb-issue-tag">{{ issue.severity === 'error' ? '错误' : '警告' }}</span>
        <span class="wb-issue-where">{{ where(issue) }}</span>
        <span class="wb-issue-message">{{ issue.message }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* 那个带边框的盒子（问题的落点、条目、标签与统计在 `styles/workbench-issues.css`：
   手写那一边的结果列表也要用同一份） */
.wb-issues { border: 1px solid #e5e7eb; border-radius: 6px; padding: 6px 8px; margin-top: 6px; background: #fafafa; }
.wb-issues-head { display: flex; align-items: center; gap: 10px; }
.wb-issues-note { font-size: 11px; color: #9ca3af; }
.wb-issues-ok { font-size: 12px; color: #15803d; padding: 4px 0; }
.wb-issue-where { flex-shrink: 0; font-family: Consolas, monospace; color: #374151; }
</style>
