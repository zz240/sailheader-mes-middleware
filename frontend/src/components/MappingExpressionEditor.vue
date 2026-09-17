<script setup lang="ts">
/**
 * 手写 JSONata 这一边（#106，从 `FieldMappingWorkbench.vue` 拆出来，见 #117）：一整段表达式 + 它的校验结果。
 *
 * 这一段与逐行配置读写的**是同一份数据格式**（`expression.ts`：`{mode:'EXPRESSION', expression, rules:[]}`），
 * 所以这里只放「这一段怎么显示、怎么改」，切换的那两句话与状态归工作台那边（它是唯一的装配层）。
 *
 * 它不知道后端：不发请求，也不自己判「这段表达式对不对」——问题列表是工作台从校验接口拿回来、
 * 剔掉「哪一行」那些信息之后传进来的（#98 D1）。
 */
import { computed } from 'vue'
import { expressionSkipNotice } from '../mapping/expression.ts'

const props = defineProps<{
  /** 表达式正文。唯一副本在工作台那边（保存与送校验读的都是它），所以这里是 `v-model`。 */
  modelValue: string
  /** 跑过一次校验没有（没跑过就什么都不显示）。 */
  hasValidation: boolean
  /** 这次校验里有没有错误（错误要在标题上标红）。 */
  hasErrors: boolean
  /** 「N 个错误 · M 个警告」那一句。 */
  summary: string
  /** 这一边的问题：没有「哪一行」可言，只有错误 / 警告两档。 */
  issues: { severity: 'error' | 'warning'; message: string }[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  /** 点了「切回逐行配置」；确认与切状态都由工作台做（那是唯一装配层的事）。 */
  (e: 'switch-back'): void
}>()

/** 那两句常驻说明只有 `expression.ts` 一处来源（切过去时确认框里说的也是它）。 */
const skipNotice = computed(() => expressionSkipNotice())

/** 输入框里的每一笔都原样交回工作台：这里不存第二份副本。 */
function onInput(value: string) {
  emit('update:modelValue', value)
}
</script>

<template>
  <section class="wb-expression">
    <el-alert
      class="wb-alert"
      type="warning"
      :closable="false"
      title="手写 JSONata：这条路跳过规则树校验"
      :description="skipNotice"
    />
    <div class="wb-expression-head">
      <span class="wb-panel-title">表达式</span>
      <!-- 这是界面上唯一会出现 `$` 的地方（#106 的决定，记在 ADR-0011 第 13 条）：
           这一段本来就是要写的 JSONata，变量名跟着写才有效，所以原样给出来。 -->
      <span class="wb-panel-tip">
        变量：<code>$s_&lt;动作序号&gt;</code> 是链上那个动作的输出（<code>$s_0</code> 是任务输入），
        <code>$dateFormat(值, "yyyy-MM-dd")</code> 格式化日期。
      </span>
      <span class="wb-spacer" />
      <el-button size="small" @click="emit('switch-back')">切回逐行配置</el-button>
    </div>
    <el-input
      :model-value="props.modelValue"
      type="textarea"
      class="wb-expression-input"
      :rows="18"
      resize="vertical"
      spellcheck="false"
      placeholder='例如 { "sheet_no": $s_1.bomNo }'
      @update:model-value="onInput"
    />
    <div class="wb-expression-note">
      保存写出去的就是这一整段（`mode: EXPRESSION`）：判断只看它跑不跑得起来。
    </div>

    <!-- 校验结果（#103）：手写这一边没有「哪一行」可言，所以只按错误 / 警告列原文 -->
    <div v-if="hasValidation" class="wb-expression-issues">
      <span class="wb-panel-title">校验结果</span>
      <span class="wb-issue-summary" :class="{ bad: hasErrors }">{{ summary }}</span>
      <div v-if="issues.length" class="wb-issue-list">
        <div v-for="(issue, i) in issues" :key="i" class="wb-issue" :class="issue.severity">
          <span class="wb-issue-tag">{{ issue.severity === 'error' ? '错误' : '警告' }}</span>
          <span class="wb-issue-message">{{ issue.message }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* 手写 JSONata 这一边（#106）：一段表达式占满中间，说明都摆在明面上 */
.wb-expression { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 10px 16px; }
.wb-expression .wb-alert { margin: 0 0 8px; }
.wb-expression-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wb-expression-head .wb-panel-tip { margin: 0; }
.wb-expression-input { flex: 1; min-height: 260px; margin-top: 6px; }
.wb-expression-input :deep(textarea) {
  height: 100%; font-family: var(--font-mono); font-size: var(--fs-body); line-height: 1.6;
}
.wb-expression-note { font-size: var(--fs-meta); color: var(--warn-ink); margin-top: 6px; }
.wb-expression-issues { margin-top: 8px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
/* 这一边的问题只是列出来，点不动（`.wb-issue` 的落点在共享的那份样式里，两处用量同一份） */
.wb-expression-issues .wb-issue { width: auto; cursor: default; }

@media (max-width: 820px) {
  /* 窄屏下文本域自己长高，不再撑满整列（#106 验收：不重叠、读得通） */
  .wb-expression-input :deep(textarea) { height: auto; }
}

@media (max-width: 640px) {
  .wb-expression { padding: 8px; }
}
</style>
