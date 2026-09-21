<template>
  <!-- 断点重投弹窗（#135 / 原型 `#overlay` + `.modal[data-od-id=redeliver-dialog]`）。
       控件用组件库那一个（ADR-0016 决策 2：只有组件库没有的才自绘）——Esc 关闭、遮罩、焦点
       都由它管；本组件只负责「写清要投的是哪条消息的哪一步」与进行中 / 失败两档的样子。
       文案与判档全在 `history/redeliver.ts`（纯模块）：界面与真机脚本判的是同一句话。 -->
  <el-dialog
    v-model="open"
    :title="REDELIVER_TITLE"
    width="560px"
    :close-on-click-modal="closable"
    :close-on-press-escape="closable"
    :show-close="closable"
    append-to-body
  >
    <div v-if="facts" class="hc-rd" data-od-id="redeliver-dialog">
      <p class="hc-rd-lead">{{ redeliverHeadline(facts) }}</p>

      <!-- 票面要写清的五样（另加一条运行）：消息 id、业务键、步骤号、动作、状态。
           取值口径与列表那一行同源（`redeliverFacts` 用的是列表行那两句缺省写法） -->
      <dl class="hc-rd-facts">
        <div v-for="row in rows" :key="row.label" class="hc-rd-frow">
          <dt>{{ row.label }}</dt>
          <dd class="hc-rd-mono">{{ row.value }}</dd>
        </div>
      </dl>

      <p class="hc-rd-note">{{ redeliverExplain() }}</p>

      <!-- 失败那一档：标题 / 接口给的原因 / 「原步仍是 FAILED」（#108 的语义）。
           接口换了原因也照原样带上，不吞成一句「失败了」 -->
      <div v-if="failure" class="hc-rd-fail" data-od-id="redeliver-error">
        <div class="hc-rd-fail-h">{{ failure.title }}</div>
        <pre class="hc-rd-fail-d">{{ failure.detail }}</pre>
        <div class="hc-rd-fail-n">{{ failure.hint }}</div>
      </div>
    </div>

    <template #footer>
      <div class="hc-rd-foot">
        <span class="hc-rd-foot-note">{{ redeliverDialogNote() }}</span>
        <el-button :disabled="pending" @click="close">{{ REDELIVER_CANCEL_LABEL }}</el-button>
        <!-- 进行中：`loading` 自己就把按钮按住了（点不动），另加 `disabled` 是给键盘与无障碍一个明确的态 -->
        <el-button
          type="primary"
          :loading="pending"
          :disabled="pending"
          data-od-id="btn-confirm-redeliver"
          @click="emit('confirm')"
        >
          {{ redeliverConfirmLabel(pending) }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  redeliverClosable, redeliverConfirmLabel, redeliverDialogNote, redeliverExplain, redeliverFailure,
  redeliverFacts, redeliverHeadline, REDELIVER_CANCEL_LABEL, REDELIVER_TITLE,
} from '../../history/redeliver'
import type { MessageStep } from '../../types'

const props = defineProps<{
  visible: boolean
  /**
   * 要重投的那一步。**打开弹窗那一刻定下来**（页面把它捕获成一个常量）：请求在路上时列表可能已经刷新，
   * 弹窗里写的必须还是用户按下按钮时看到的那一步。
   */
  step: MessageStep | null
  /** 右详情手里那条消息的业务键（与列表行同一句缺省写法）。 */
  businessKey: string
  /** 请求在路上：确认按钮 pending、弹窗不许关。 */
  pending: boolean
  /** 接口那句错误（空串 = 这一投还没失败过）。 */
  error: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: []
}>()

const open = computed({
  get: () => props.visible,
  set: (value: boolean) => emit('update:visible', value),
})

const facts = computed(() => (props.step ? redeliverFacts(props.step, props.businessKey) : null))
const failure = computed(() => (props.error.trim() ? redeliverFailure(props.error) : null))

/** 进行中不许关：关了就不知道这一次重投到底成没成（`redeliverClosable` 一处判）。 */
const closable = computed(() => redeliverClosable(props.pending))

/** 弹窗里那几样事实（顺序照票面：哪条消息 → 哪一步 → 什么动作 / 什么状态；运行摆头一格）。 */
const rows = computed(() => {
  const f = facts.value
  if (!f) return []
  return [
    { label: '运行', value: `#${f.runId}` },
    { label: '消息 id', value: f.messageId },
    { label: '业务键', value: f.businessKey },
    { label: '步骤', value: String(f.step) },
    { label: '动作', value: f.action },
    { label: '状态', value: f.status },
  ]
})

function close() {
  if (!closable.value) return
  emit('update:visible', false)
}
</script>
