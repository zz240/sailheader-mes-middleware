<script setup lang="ts">
/**
 * 「粘贴 JSON」那个输入框（#115 来源侧 / #118 目标侧）：粘一份样例，推出一份字段清单。
 *
 * 它**不发请求、也不解析**：推断是后端做的（两条粘贴路共用同一份样例展开），这段文本原样交给工作台去发；
 * 收不下时后端那句话由工作台填进 `error`，原样显示在这里，粘的那段文本留着好照着改。
 *
 * 两边的**话**由调用方给（`hint` / `note`）：来源侧要说清这份样例归到哪一步，目标侧要说清必填这一栏
 * 推不出来。这个框自己只画，不说自己是哪一边（它连推的是来源还是目标都不知道）。
 */
const props = defineProps<{
  visible: boolean
  /** 粘进来的那段文本（工作台拿着，取消再打开时还在）。 */
  text: string
  /** 这个框在推什么：收什么、与哪条路并列。 */
  hint: string
  /** 这一次粘贴特有的那件事（归到哪一步 / 必填一律不当必填 / 数组按第 1 条推）。 */
  note: string
  /** 后端说这段文本收不下时的那句话。 */
  error: string
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'update:text', value: string): void
  /** 「推出字段清单」：把这段文本交给工作台（请求由它发，发往哪一边由它自己知道）。 */
  (e: 'submit', text: string): void
}>()
</script>

<template>
  <el-dialog
    :model-value="props.visible"
    title="粘贴 JSON"
    width="640px"
    :close-on-click-modal="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="wb-paste-tip">{{ props.hint }}</div>
    <div class="wb-paste-tip wb-paste-note">{{ props.note }}</div>
    <el-input
      :model-value="props.text"
      type="textarea"
      :rows="12"
      class="wb-paste-text"
      placeholder='例如 {"order_no":"PO-1","items":[{"qty":3}]}'
      @update:model-value="(value: string) => emit('update:text', value)"
    />
    <div v-if="props.error" class="wb-paste-error">{{ props.error }}</div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="props.loading" @click="emit('submit', props.text)">推出字段清单</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wb-paste-tip { font-size: var(--fs-body); color: var(--muted); margin-bottom: 6px; }
.wb-paste-note { color: var(--fg); }
.wb-paste-text :deep(textarea) { font-family: var(--font-mono); font-size: var(--fs-body); }
/* 后端说这段文本收不下：原样显示那句话，粘的那段留着好照着改 */
.wb-paste-error {
  margin-top: 6px; padding: 6px 8px; border-radius: var(--radius);
  background: var(--danger-soft); border: 1px solid color-mix(in oklch, var(--danger) 34%, var(--border)); color: var(--danger-ink); font-size: var(--fs-body);
}
</style>
