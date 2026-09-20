<template>
  <aside class="hc-panel hc-list-panel" :aria-label="`${listTitle(mode)}列表`">
    <div class="hc-phead">
      <span class="hc-pt">{{ listTitle(mode) }}</span>
      <span class="hc-ps">{{ listCountText(total) }}</span>
      <span class="hc-right">{{ listHint(mode) }}</span>
    </div>

    <div class="hc-scroll">
      <!-- 加载中：占位而不是一片空白（接口返回前用户要知道「在取」） -->
      <div v-if="loading" class="hc-skel" aria-label="加载中">
        <i v-for="n in 6" :key="n" />
      </div>

      <div v-else-if="error" class="hc-errbox">
        <div>{{ emptyCopy.title }}</div>
        <div>{{ emptyCopy.hint }}</div>
        <div class="hc-err-act">
          <el-button size="small" @click="emit('retry')">重试</el-button>
          <span class="hc-ps">{{ error }}</span>
        </div>
      </div>

      <div v-else-if="!rowCount" class="hc-list-empty">
        <div class="hc-empty-title">{{ emptyCopy.title }}</div>
        <div class="hc-empty-hint">{{ emptyCopy.hint }}</div>
      </div>

      <!-- 模式一：运行 -->
      <div v-else-if="mode === 'runs'" class="hc-rlist">
        <button
          v-for="row in runList"
          :key="row.run.id"
          class="hc-ritem"
          type="button"
          :aria-pressed="isRunSelected(selected, row.run.id)"
          @click="emit('select-run', row.run)"
        >
          <div class="hc-r-top">
            <span class="hc-r-id">#{{ row.run.id }}</span>
            <span class="hc-r-job" :title="row.jobTitle">{{ row.jobTitle }}</span>
            <span class="hc-st" :class="`hc-st-${row.chip.kind}`">{{ row.chip.label }}</span>
          </div>
          <div class="hc-r-sub">
            <span>开始 {{ row.started }}</span>
            <span>结束 {{ row.finished }}</span>
            <!-- 规模摘要（#130）：`N 消息 · M 步`，失败步为 0 时那一段整个不写（票面：不许有「失败 0」噪声）。
                 切段与「哪一段算失败」都在 console.ts 一处判完，这里只负责上色 -->
            <span class="hc-r-counts">
              <span
                v-for="(part, i) in row.counts"
                :key="i"
                :class="{ 'hc-r-bad': part.bad }"
              >{{ part.text }}</span>
            </span>
          </div>
          <!-- 命中的业务键（#130）：运行侧按业务键筛过时才有内容——「只筛不显示」是 #129 留下的缺口 -->
          <div v-if="row.keys.length" class="hc-r-sub hc-r-keys">
            <span v-for="key in row.keys" :key="key" class="hc-r-biz">{{ key }}</span>
          </div>
          <div v-if="row.errLine" class="hc-r-err" :title="row.run.error ?? ''">{{ row.errLine }}</div>
        </button>
      </div>

      <!-- 模式二：消息步检索（#136）——「运行 × 消息 × 步」拍平成一条条消息步，直接按步排查 -->
      <div v-else class="hc-rlist">
        <button
          v-for="row in stepList"
          :key="row.step.id"
          class="hc-ritem"
          type="button"
          :aria-pressed="isStepSelected(selected, row.step)"
          @click="emit('select-step', row.step)"
        >
          <div class="hc-r-top">
            <span class="hc-r-id">{{ row.head }}</span>
            <span class="hc-st" :class="`hc-st-${row.chip.kind}`">{{ row.chip.label }}</span>
          </div>
          <div class="hc-r-sub">
            <span>{{ row.action }}</span>
            <!-- 业务键单独一个 class 是给真机脚本读的抓手（样式照原型留在这一行里） -->
            <span class="hc-r-bk">{{ row.businessKey }}</span>
          </div>
          <div class="hc-r-sub">
            <span>{{ row.at }}</span>
            <span>尝试 {{ row.attempt }}</span>
            <span>{{ row.job }}</span>
          </div>
          <div v-if="row.errLine" class="hc-r-err" :title="row.step.error ?? ''">{{ row.errLine }}</div>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  isRunSelected, isStepSelected, listCountText, listEmptyCopy, listHint, listTitle, runRows, stepRows,
  type HistoryMode, type HistorySelection, type RunFilters,
} from '../../history/console'
import type { Job, JobRun, MessageStep, RunSummary } from '../../types'

const props = defineProps<{
  mode: HistoryMode
  runs: JobRun[]
  steps: MessageStep[]
  jobs: Job[]
  /** 每运行摘要（#130）：与 `runs` 同一组筛选算出来，按下标对到行上。 */
  summaries: RunSummary[]
  total: number
  selected: HistorySelection
  loading: boolean
  error: string
  filters: RunFilters
}>()

const emit = defineEmits<{
  'select-run': [run: JobRun]
  'select-step': [step: MessageStep]
  retry: []
}>()

const emptyCopy = computed(() => listEmptyCopy({ mode: props.mode, error: props.error, filters: props.filters }))

/** 每行的说法一次算好：模板里不再重复调 statusChip / 反复查任务名。两个模式各一份投影。 */
const runList = computed(() => runRows(props.runs, props.jobs, props.summaries))
const stepList = computed(() => stepRows(props.steps))

const rowCount = computed(() => (props.mode === 'runs' ? runList.value.length : stepList.value.length))
</script>
