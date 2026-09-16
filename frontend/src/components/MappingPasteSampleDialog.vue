<script setup lang="ts">
/**
 * 「粘贴 JSON」那个输入框（#115）：粘一份客户给的来源数据，推出左边的来源字段清单。
 *
 * 它**不发请求、也不解析**：推断是后端复用样例展开做的（与探测那条路同一份实现），这段文本原样交给
 * 工作台去发；收不下时后端那句话由工作台填进 `error`，原样显示在这里，粘的那段文本留着好照着改。
 *
 * 它也说清两件事，因为都要在按「推出字段清单」之前看见：这份样例会归到哪一个来源、
 * 是数组的话只按第 1 条推。
 */
const props = defineProps<{
  visible: boolean
  /** 粘进来的那段文本（工作台拿着，取消再打开时还在）。 */
  text: string
  /** 这份样例会归到哪一个来源（「动作 1 · 读订单」/「任务输入」）。 */
  stepLabel: string
  /** 后端说这段文本收不下时的那句话。 */
  error: string
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'update:text', value: string): void
  /** 「推出字段清单」：把这段文本交给工作台（请求由它发）。 */
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
    <div class="wb-paste-tip">
      粘一份来源数据的 JSON，用它推出左边的来源字段清单——推出来的字段（名称、类型、可空、样例值、
      数组分组）与「从运行记录探测」那条路一模一样。
    </div>
    <div class="wb-paste-tip">收对象或数组；是数组就按第 1 条推，界面上会注明。</div>
    <div class="wb-paste-tip wb-paste-step">这份样例按「{{ props.stepLabel }}」的输出来算。</div>
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
.wb-paste-tip { font-size: 12px; color: #6b7280; margin-bottom: 6px; }
.wb-paste-step { color: #374151; }
.wb-paste-text :deep(textarea) { font-family: Consolas, monospace; font-size: 12px; }
/* 后端说这段文本收不下：原样显示那句话，粘的那段留着好照着改 */
.wb-paste-error {
  margin-top: 6px; padding: 6px 8px; border-radius: 4px;
  background: #fef2f2; border: 1px solid #fecaca; color: #b91c1c; font-size: 12px;
}
</style>
