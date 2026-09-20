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
                  </span>
                </button>
              </div>
            </div>
          </div>

          <!-- 取步上限（#131）：只在「拿到的比接口说的少」时才出现——静默截断在这一票里消失 -->
          <div v-if="stepsNote" class="hc-chain-note" role="note">{{ stepsNote }}</div>

          <StepDetailCard :step="detail.step" />
        </template>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import StepDetailCard from './StepDetailCard.vue'
import {
  chainNodes, chainSummary, detailPath, jobTitleOf, runDetail, runStepsNote, shortId, stampOf, statusChip,
  type HistorySelection,
} from '../../history/console'
import type { Job, JobRun, MessageStep } from '../../types'

const props = defineProps<{
  run: JobRun | null
  jobs: Job[]
  selection: HistorySelection
  /** 这条运行的消息步（#131 由页面在选中运行后取回；两个模式共用同一份）。 */
  steps: MessageStep[]
  stepsLoading: boolean
  stepsError: string
  /** 接口说的这条运行一共多少步——比 `steps.length` 大就是撞上了取步上限。 */
  stepsTotal: number
  /** 取这条运行失败时的说法（消息步模式下点一行会按需去取它所属的运行）。 */
  runError?: string
}>()

const emit = defineEmits<{
  'select-step': [step: MessageStep]
  'retry-steps': []
}>()

const chip = computed(() => statusChip(props.run?.status))
const jobTitle = computed(() => (props.run ? jobTitleOf(props.jobs, props.run.jobId) : ''))
/** 当前消息与当前步骤的归一（选中的对不上就落到第 1 条 / 第 1 步）只在 console.ts 里判一次。 */
const detail = computed(() => runDetail(props.steps, props.selection))
const nodes = computed(() => chainNodes(detail.value.message, detail.value.step))
const stepsNote = computed(() => runStepsNote(props.steps.length, props.stepsTotal))
</script>
