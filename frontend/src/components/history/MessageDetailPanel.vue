<template>
  <section class="hc-panel hc-detail-panel" aria-label="消息详情">
    <div class="hc-phead">
      <span class="hc-pt">消息详情</span>
      <!-- 头右端就是**选中了什么**：消息步检索模式下点一行，这里写清运行 / 消息 / 步骤（#136） -->
      <span class="hc-ps">{{ detailPath(selection) }}</span>
    </div>

    <div class="hc-scroll">
      <div v-if="runError" class="hc-errbox">
        <div>这条运行的详情没取到。</div>
        <div class="hc-ps">{{ runError }}</div>
      </div>

      <div v-else-if="!run" class="hc-list-empty">
        <div class="hc-empty-title">选一条运行看它的消息与消息步</div>
      </div>

      <div v-else class="hc-dbody">
        <div class="hc-dhead">
          <h2>运行 #{{ run.id }}</h2>
          <span class="hc-st" :class="`hc-st-${chip.kind}`">{{ chip.label }}</span>
          <span class="hc-ps">{{ jobTitle }}</span>
        </div>
        <div class="hc-dsub">
          <span>开始 <b>{{ stampOf(run.startedAt) }}</b></span>
          <span>结束 <b>{{ stampOf(run.finishedAt) }}</b></span>
        </div>
        <div v-if="run.error" class="hc-runerr">
          <span class="hc-mk">运行错误</span>
          <pre>{{ run.error }}</pre>
        </div>

        <!-- 消息步三态（#131）：这条运行的消息步是**另一次请求**，取数自己会慢半拍 -->
        <div v-if="stepsLoading" class="hc-skel" aria-label="消息步加载中">
          <i v-for="n in 3" :key="n" />
        </div>

        <div v-else-if="stepsError" class="hc-errbox">
          <div>这条运行的消息步没取到。</div>
          <div class="hc-ps">{{ stepsError }}</div>
          <div class="hc-err-act">
            <el-button size="small" @click="emit('retry-steps')">重试</el-button>
          </div>
        </div>

        <div v-else-if="!detail.messages.length" class="hc-list-empty">
          <!-- 空态说法留在模板里（与 #129 那两句同一个做法）：它只有一种说法、没有可判的档，
               没有 `listEmptyCopy` 那种「按模式 × 按筛没筛」的判决要锁 -->
          <div class="hc-empty-title">这条运行还没有消息步。</div>
          <div class="hc-empty-hint">任务跑过、链路走到动作之后，这里会出现它的消息与步骤链。</div>
        </div>

        <template v-else>
          <!-- 消息 chips（原型 `.msgs`）：短 id + 业务键 + 「全通 / 失败 N 步」。
               数量为 1 时**照旧显示**（票面明说）——一条消息的运行也可能要看它是哪条消息。 -->
          <div class="hc-msgs">
            <span class="hc-msgs-lbl">消息</span>
            <button
              v-for="(message, i) in detail.messages"
              :key="message.messageId"
              class="hc-mchip"
              type="button"
              :aria-pressed="i === detail.index"
              :aria-label="`消息 ${shortId(message.messageId)} · ${message.businessKey} · ${message.chip.label}`"
              @click="emit('select-step', message.steps[0])"
            >
              <span class="hc-mid">{{ shortId(message.messageId) }}</span>
              <span class="hc-mbk">{{ message.businessKey }}</span>
              <span class="hc-st" :class="`hc-st-${message.chip.kind}`">{{ message.chip.label }}</span>
            </button>
          </div>

          <!-- 步骤链（原型 `chain-wrap`）：入口 `context[0]` 一格打头，其后每一步一格 -->
          <div class="hc-chain-wrap">
            <div class="hc-chain-head">
              <span class="hc-chain-t">步骤链</span>
              <span class="hc-chain-s">点一个步骤看它的输入 / 数据快照落没落</span>
              <span class="hc-chain-s hc-chain-right">{{ chainSummary(detail.message) }}</span>
            </div>
            <div class="hc-chain">
              <div
                v-for="node in nodes"
                :key="node.n"
                class="hc-cnode"
              >
                <span v-if="node.n > 0" class="hc-carrow" aria-hidden="true">→</span>
                <!-- 入口那格是静态的（原型里它也不是按钮）：`context[0]` 是运行触发报文，不是某一步 -->
                <div v-if="!node.step" class="hc-cbtn hc-cbtn-entry">
                  <span class="hc-cn">{{ node.head }}</span>
                  <span class="hc-ca">{{ node.action }}</span>
                  <span class="hc-cl">{{ node.note }}</span>
                </div>
                <button
                  v-else
                  class="hc-cbtn"
                  :class="{ 'hc-cbtn-bad': node.chip.kind === 'bad' }"
                  type="button"
                  :aria-pressed="node.selected"
                  :aria-label="node.label"
                  @click="emit('select-step', node.step)"
                >
                  <span class="hc-cn">
                    {{ node.head }}
                    <span class="hc-snapdots" title="输入快照 / 数据快照">
                      <i class="hc-sdot" :class="node.hasInput ? 'hc-sdot-on' : 'hc-sdot-none'" />
                      <i class="hc-sdot" :class="node.hasData ? 'hc-sdot-on' : 'hc-sdot-none'" />
                    </span>
                  </span>
                  <span class="hc-ca">{{ node.action }}</span>
                  <span class="hc-cm">
                    <span class="hc-cstat" :class="`hc-cstat-${node.chip.kind}`">{{ node.chip.label }}</span>
                    <!-- 动作耗时（#134）：与单步卡那一格同源（都出自 console.ts 的 durationText），
                         老数据没测过时写 `—` 而不是 `0 ms`。灰色照原型（不是状态色：耗时本身没有好坏档） -->
                    <span class="hc-ccost">{{ node.cost }}</span>
                  </span>
                </button>
              </div>
            </div>
          </div>

          <StepDetailCard
            :step="detail.step"
            :step-row="stepRow"
            :step-row-loading="stepRowLoading"
            :step-row-error="stepRowError"
            :prev-step-row="prevStepRow"
            :prev-step-row-loading="prevStepRowLoading"
            :prev-step-row-error="prevStepRowError"
            :redeliver-pending="redeliverPending"
            @retry-step-row="emit('retry-step-row')"
            @retry-prev-row="emit('retry-prev-row')"
            @redeliver="emit('redeliver')"
          />
        </template>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StepDetailCard from './StepDetailCard.vue'
import {
  chainNodes, chainSummary, detailPath, jobTitleOf, shortId, stampOf, statusChip,
  type HistorySelection, type RunDetail,
} from '../../history/console'
import type { Job, JobRun, MessageStep } from '../../types'

const props = defineProps<{
  run: JobRun | null
  jobs: Job[]
  selection: HistorySelection
  /**
   * 「选中了什么」**归一之后**的那一份（消息 / 下标 / 当前步骤）：由页面算一次传下来，
   * 与它按 id 取整行用的是同一份——面板不再自己算第二遍（`runDetail` 里那次归一只有一处）。
   */
  detail: RunDetail
  stepsLoading: boolean
  stepsError: string
  /** 取这条运行失败时的说法（消息步模式下点一行会按需去取它所属的运行）。 */
  runError?: string
  /**
   * 归一之后那一步的**整行**（#132 的快照面板要的是它：索引那条路上没有快照原文）。
   * 三态：`stepRowLoading` / `stepRowError` / 两者都没有时的这一格。
   */
  stepRow: MessageStep | null
  stepRowLoading: boolean
  stepRowError: string
  /**
   * **上一步**的整行（#133 的「与上一步对照」要它的数据快照）：与 `stepRow` 走同一条按 id 取整行的
   * 路，三态也一样。当前这一步本来就是这条消息的第一步时给 `null`（页面不会为它发请求）。
   */
  prevStepRow: MessageStep | null
  prevStepRowLoading: boolean
  prevStepRowError: string
  /** 断点重投正在路上（#135）：入口按钮 pending 且不可连点。 */
  redeliverPending?: boolean
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
  'retry-steps': []
  'retry-step-row': []
  'retry-prev-row': []
  redeliver: []
}>()

const chip = computed(() => statusChip(props.run?.status))
const jobTitle = computed(() => (props.run ? jobTitleOf(props.jobs, props.run.jobId) : ''))
/** 步骤链的节点：入口那格 + 当前消息的每一步（归一之后的那一步判选中态）。 */
const nodes = computed(() => chainNodes(props.detail.message, props.detail.step))
</script>
