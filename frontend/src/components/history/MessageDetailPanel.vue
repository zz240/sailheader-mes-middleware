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
                v-for="entry in chain"
                :key="entry.node.n"
                class="hc-cnode"
              >
                <span v-if="entry.node.n > 0" class="hc-carrow" aria-hidden="true">→</span>
                <!-- 入口那格是静态的（原型里它也不是按钮）：`context[0]` 是运行触发报文，不是某一步 -->
                <div v-if="!entry.node.step" class="hc-cbtn hc-cbtn-entry">
                  <span class="hc-cn">{{ entry.node.head }}</span>
                  <span class="hc-ca">{{ entry.node.action }}</span>
                  <span class="hc-cl">{{ entry.node.note }}</span>
                </div>
                <button
                  v-else
                  class="hc-cbtn"
                  :class="{ 'hc-cbtn-bad': entry.node.chip.kind === 'bad', 'hc-cbtn-single': entry.tier === 'single' }"
                  type="button"
                  :aria-pressed="entry.node.selected"
                  :aria-label="entry.label"
                  @click="emit('select-step', entry.node.step)"
                >
                  <span class="hc-cn">
                    {{ entry.node.head }}
                    <span class="hc-snapdots" title="输入快照 / 数据快照">
                      <i class="hc-sdot" :class="entry.node.hasInput ? 'hc-sdot-on' : 'hc-sdot-none'" />
                      <i class="hc-sdot" :class="entry.node.hasData ? 'hc-sdot-on' : 'hc-sdot-none'" />
                    </span>
                    <!-- 小批徽标（#139）：**只标「这一步是一批」、不摊开成员**；点它展开/收起这一组。
                         它在节点按钮**里面**（链上那几格就这些盒子，另起一个盒子会把「点哪一格选哪一步」
                         的布局搅了），所以点击要拦住冒泡——不然点徽标会顺带选中这一步 -->
                    <span
                      v-if="entry.tier === 'batch'"
                      class="hc-bbadge"
                      role="button"
                      tabindex="0"
                      :aria-expanded="entry.open"
                      :aria-label="`这一步是一批（${entry.group?.total ?? 0} 条）：点开看这一批的消息`"
                      @click.stop="toggleBatch(entry.key)"
                      @keydown.enter.prevent.stop="toggleBatch(entry.key)"
                      @keydown.space.prevent.stop="toggleBatch(entry.key)"
                    >
                      {{ BATCH_BADGE }}
                    </span>
                  </span>
                  <span class="hc-ca">{{ entry.node.action }}</span>
                  <span class="hc-cm">
                    <span class="hc-cstat" :class="`hc-cstat-${entry.node.chip.kind}`">{{ entry.node.chip.label }}</span>
                    <!-- 动作耗时（#134）：与单步卡那一格同源（都出自 console.ts 的 durationText），
                         老数据没测过时写 `—` 而不是 `0 ms`。灰色照原型（不是状态色：耗时本身没有好坏档） -->
                    <span class="hc-ccost">{{ entry.node.cost }}</span>
                  </span>
                  <!-- 单条那档（#139）：某一步上这条消息自己一组，画虚线框写清楚——不给人
                       「这一批只有一条」的错觉（ADR-0017 决策 10） -->
                  <span v-if="entry.tier === 'single'" class="hc-cl hc-cl-single">{{ SINGLE_NOTE }}</span>
                </button>
              </div>
            </div>

            <!-- 点开的那一组：组头（读取批 / 发送批 · 一次请求 N 条 · 失败 M 条）+ 同批每组一颗 chip -->
            <BatchGroupChips
              v-if="openGroup"
              :group="openGroup"
              @select-step="emit('select-step', $event)"
            />
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
import { computed, ref } from 'vue'
import BatchGroupChips from './BatchGroupChips.vue'
import StepDetailCard from './StepDetailCard.vue'
import {
  BATCH_BADGE, SINGLE_NOTE, batchGroups, batchKey, nodeBatch, type BatchGroup,
} from '../../history/batch'
import {
  chainNodes, chainSummary, detailPath, jobTitleOf, shortId, stampOf, statusChip,
  type ChainNode, type HistorySelection, type RunDetail,
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
  /**
   * 这条运行**全部**消息步（索引接口那一份）：按批分组要同一步上**所有**消息的那几行，
   * 而 `detail` 里只有当前这一条消息的步骤。分组判据全在 `history/batch.ts`，这里只把它喂给它。
   */
  runSteps: MessageStep[]
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

/** 链上的一格 + 它属于哪一批（两样在这里对上；分组与「这一步画成什么」都判在 `history/batch.ts`）。 */
interface ChainEntry {
  node: ChainNode
  tier: 'batch' | 'single' | 'plain'
  group: BatchGroup | null
  /** 这一组的键（点开 / 收起按它认）。 */
  key: string
  /** 这一组现在开着吗（一次只开一组）。 */
  open: boolean
  /** 可访问名称：链那一份 + 单条那档多一句（虚线框上写的就是它）。 */
  label: string
}

/** 整条运行的消息步按批分组（同一步上同批的一组）。 */
const groups = computed(() => batchGroups(props.runSteps))

/**
 * 点开的那一组（一次只开一组）：按「步骤号 + 批号」记。
 *
 * 记的是**键**而不是那一组本身：列表刷新过之后（#135 重投完的双刷）分组会重算，
 * 拿旧对象会画出一份过期的东西。
 */
const openBatchKey = ref<string | null>(null)
const openGroup = computed(() => groups.value.find((group) => batchKey(group) === openBatchKey.value) ?? null)

function toggleBatch(key: string) {
  openBatchKey.value = openBatchKey.value === key ? null : key
}

/**
 * 步骤链的每一格：入口那格 + 当前消息的每一步（归一之后的那一步判选中态）。
 *
 * `previous` 是**链上的上一步**（入口那格不是某一步，所以它不参与）：批号与它不同才算「换了号」，
 * 只有那一格画小批徽标（ADR-0017 决策 2）。
 */
const chain = computed<ChainEntry[]>(() => {
  let previous: MessageStep | null = null
  return chainNodes(props.detail.message, props.detail.step).map((node) => {
    const batch = node.step ? nodeBatch(previous, node.step, groups.value) : { tier: 'plain' as const, group: null }
    if (node.step) previous = node.step
    const key = batch.group ? batchKey(batch.group) : ''
    return {
      node,
      tier: batch.tier,
      group: batch.group,
      key,
      open: !!key && key === openBatchKey.value,
      label: batch.tier === 'single' ? `${node.label} · ${SINGLE_NOTE}` : node.label,
    }
  })
})
</script>
