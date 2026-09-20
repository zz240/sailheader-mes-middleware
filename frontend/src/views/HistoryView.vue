<template>
  <div class="hc-page">
    <section class="hc-pagehead">
      <div class="hc-title-row">
        <div>
          <h1>消息历史</h1>
          <p class="hc-subline">
            <span>死信升维为「运行 + 消息步」<code>ADR-0009</code></span>
            <span>·</span>
            <span>失败即停、重试退避、从失败步断点续跑<code>ADR-0003</code></span>
          </p>
        </div>
        <HistoryCountsBar :counts="counts" :error="countsError" @retry="load" />
      </div>
    </section>

    <HistoryFilters
      v-model="filters"
      :mode="mode"
      :jobs="jobs"
      :jobs-error="jobsError"
      :busy="loading"
      @update:mode="onMode"
      @query="onQuery"
      @reset="onReset"
    />

    <main class="hc-workspace">
      <HistoryListPanel
        :mode="mode"
        :runs="runs"
        :steps="steps"
        :jobs="jobs"
        :summaries="summaries"
        :total="total"
        :selected="selection"
        :loading="loading"
        :error="error"
        :filters="filters"
        @select-run="onSelectRun"
        @select-step="onSelectStep"
        @retry="load"
      />
      <MessageDetailPanel :run="selectedRun" :jobs="jobs" :selection="selection" :run-error="runError" />
    </main>

    <!-- 分页条放在**页级**：左栏只有 322px，装不下 element-plus 那套完整分页（实测会被推到栏外）。
         只在接口出错时收起来——「筛没了」也要留着它，否则每页条数都没得改。两个模式共用同一套。 -->
    <div v-if="!error" class="hc-pfoot">
      <el-pagination
        :current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPage"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import HistoryFilters from '../components/history/HistoryFilters.vue'
import HistoryCountsBar from '../components/history/HistoryCountsBar.vue'
import HistoryListPanel from '../components/history/HistoryListPanel.vue'
import MessageDetailPanel from '../components/history/MessageDetailPanel.vue'
import { api, ApiError } from '../api/client'
import {
  DEFAULT_MODE, emptyFilters, isStepSelected, listQuery, noSelection, selectRun, selectStep,
  statusParam, summaryQuery,
  type HistoryMode, type HistorySelection, type RunFilters,
} from '../history/console'
import type { Job, JobRun, MessageStep, Page, RunsSummary, RunSummary } from '../types'

const jobs = ref<Job[]>([])
const jobsError = ref('')
const mode = ref<HistoryMode>(DEFAULT_MODE)
const runs = ref<JobRun[]>([])
const steps = ref<MessageStep[]>([])
const filters = ref<RunFilters>(emptyFilters())
/** 计数条与每运行摘要（#130）：与列表同一次加载取回来，行摘要不会比列表慢半拍。 */
const counts = ref<RunsSummary | null>(null)
const summaries = ref<RunSummary[]>([])
/** 聚合那条自己挂了时的那句话（列表那侧的错误另说；计数条空着与「真的是 0」得分得开）。 */
const countsError = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')
/** 左列表选中了什么（两个模式共用）；右栏的那条运行与它是两件事——消息步那一侧要按需去取。 */
const selection = ref<HistorySelection>(noSelection())
const selectedRun = ref<JobRun | null>(null)
const runError = ref('')
/** 取运行的响应可能乱序回来（连点两行）：token 一换，旧响应就作废。 */
let runToken = 0
/** 同一条：取列表的响应也可能乱序回来（连点两下模式开关）。 */
let loadToken = 0

async function loadJobs() {
  try {
    jobs.value = await api<Job[]>('/jobs')
    jobsError.value = ''
  } catch (e) {
    // 任务清单取不到**不该拖住列表**：行上的任务名退化成 `#id` 照样能看，但这件事要在界面上说出来
    jobsError.value = e instanceof ApiError ? e.message : String(e)
  }
}

async function load() {
  // token 一换，在路上的旧响应就作废：连点两下模式开关时，先发的那次回来得晚
  // 会把另一侧的 records 写进列表（界面就成了「模式与实际列表不是一回事」）
  const token = ++loadToken
  const asked = mode.value
  loading.value = true
  error.value = ''
  // 上一次的计数与摘要先收回来：它们属于上一组筛选，留着就是「数字与列表说的不是一回事」
  counts.value = null
  summaries.value = []
  countsError.value = ''
  try {
    const qs = listQuery(asked, filters.value, currentPage.value, pageSize.value)
    // 计数与运行摘要（#130）：与列表**同一组筛选、同一页**——四个计数是整个筛选集的（翻页不减），
    // 而每运行摘要跟着页走。与列表一起发（互不依赖），省一次往返；它自己挂了不许拖垮列表。
    const summaryStatus = statusParam(asked, filters.value)
    const summary = loadSummary(
      summaryQuery(filters.value, summaryStatus, currentPage.value, pageSize.value), token)
    if (asked === 'runs') {
      const page = await api<Page<JobRun>>(`/history/runs?${qs}`)
      if (token !== loadToken) return
      runs.value = page.records
      steps.value = []
      total.value = page.total
      syncRunSelection(page.records)
      await summary
    } else {
      const page = await api<Page<MessageStep>>(`/history/steps?${qs}`)
      if (token !== loadToken) return
      steps.value = page.records
      runs.value = []
      total.value = page.total
      syncStepSelection(page.records)
      await summary
    }
  } catch (e) {
    if (token !== loadToken) return
    error.value = e instanceof ApiError ? e.message : String(e)
    runs.value = []
    steps.value = []
    total.value = 0
    counts.value = null
    summaries.value = []
    clearSelection()
  } finally {
    if (token === loadToken) loading.value = false
  }
}

/**
 * 计数条与运行摘要那条只读聚合接口（#130）。
 *
 * **它自己挂了不抛**：列表已经拿到了，计数条空着比整页报错强（原型上它也只是页头那几个数字）。
 * 计数与摘要是「这次筛选有多大」的旁证，不是列表本身——列表那侧的错误由 `load()` 统一说。
 *
 * `token` 与列表同一个（连点两下筛选时，先发的那次回来得晚会把旧数字写进新筛选集）。
 */
async function loadSummary(qs: string, token: number) {
  try {
    const data = await api<RunsSummary>(`/history/runs/summary${qs ? `?${qs}` : ''}`)
    if (token !== loadToken) return
    counts.value = data
    summaries.value = data.summaries
  } catch {
    if (token !== loadToken) return
    counts.value = null
    summaries.value = []
  }
}

/** 选中的那条运行不在这一页了就把右栏收回去，免得详情与列表对不上。 */
function syncRunSelection(records: JobRun[]) {
  const current = selection.value
  if (current.runId === null) return
  const found = records.find((run) => run.id === current.runId)
  if (found) selectedRun.value = found
  else clearSelection()
}

/**
 * 选中的那一步不在这一页了：**运行留着**（右栏看它仍然有意义），只把消息与步骤收回。
 * 与运行那一侧不同（那里整条运行就是选中物，不在页上就没什么可看的了）。
 */
function syncStepSelection(records: MessageStep[]) {
  const current = selection.value
  if (current.runId === null || current.messageId === null) return
  if (!records.some((step) => isStepSelected(current, step))) {
    selection.value = { runId: current.runId, messageId: null, step: null }
  }
}

function clearSelection() {
  selection.value = noSelection()
  selectedRun.value = null
  runError.value = ''
  runToken++
}

function onQuery() {
  currentPage.value = 1
  void load()
}

function onReset() {
  // 重置 = **回到初始状态**：筛选清空、回第 1 页，模式也回到默认那一侧（原型 reset 里那句
  // `state.view = 'runs'`；#129 之前的老页面同样这么重置）。选中的那条运行留着。
  filters.value = emptyFilters()
  reloadFromFirstPage(DEFAULT_MODE)
}

/**
 * 切模式：留在同一条运行上（右栏不闪），消息与步骤收回——这一侧的行集合换了一套。
 * 回到第 1 页（行集合变了，停在第 2 页只会让人以为「切过去就空了」）。重置也走这一条。
 */
function onMode(next: HistoryMode) {
  if (next === mode.value) return
  reloadFromFirstPage(next)
}

/** 行集合换了之后要一起做的四件事：换模式、回第 1 页、收回消息与步骤（运行留着）、重新取数。 */
function reloadFromFirstPage(next: HistoryMode) {
  mode.value = next
  currentPage.value = 1
  selection.value = { runId: selection.value.runId, messageId: null, step: null }
  void load()
}

function onSelectRun(run: JobRun) {
  runToken++
  selection.value = selectRun(run.id)
  selectedRun.value = run
  runError.value = ''
}

function onSelectStep(step: MessageStep) {
  selection.value = selectStep(step)
  runError.value = ''
  void ensureRun(step.runId)
}

/**
 * 右栏要的是那条**运行**（状态 / 两个时刻 / 运行错误），而消息步行只带 runId：按需取一次运行详情
 * ——一次点击一次请求，不是按行补拉的 N+1（ADR-0016 决策 6 同一个理由；这一侧手里没有运行列表可查，
 * 行上的 `jobId` 投影也只够写「任务 #id」）。同一条运行里换个步骤不再重复取。
 */
async function ensureRun(runId: number) {
  if (selectedRun.value?.id === runId) return
  const token = ++runToken
  try {
    const run = await api<JobRun>(`/history/runs/${runId}`)
    if (token !== runToken) return
    selectedRun.value = run
  } catch (e) {
    if (token !== runToken) return
    selectedRun.value = null
    runError.value = e instanceof ApiError ? e.message : String(e)
  }
}

function onPage(page: number) {
  currentPage.value = page
  void load()
}

function onSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  void load()
}

onMounted(async () => {
  // 两件事一起发：任务清单挂掉也不影响列表自己把那句话说出来（别把「坏了」装成「没有」）
  await Promise.all([loadJobs(), load()])
})
</script>

<style src="../styles/history-console.css"></style>
