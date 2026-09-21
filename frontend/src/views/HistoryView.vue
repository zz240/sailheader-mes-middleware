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
      <MessageDetailPanel
        :run="selectedRun"
        :jobs="jobs"
        :selection="selection"
        :detail="runDetailView"
        :steps-loading="runStepsLoading"
        :steps-error="runStepsError"
        :run-error="runError"
        :step-row="stepRow"
        :step-row-loading="stepRowLoading"
        :step-row-error="stepRowError"
        :prev-step-row="prevStepRow"
        :prev-step-row-loading="prevStepRowLoading"
        :prev-step-row-error="prevStepRowError"
        :redeliver-pending="redeliverPending"
        @select-step="onSelectStep"
        @retry-steps="retryRunSteps"
        @retry-step-row="retryStepRow"
        @retry-prev-row="retryPrevStepRow"
        @redeliver="openRedeliver"
      />
    </main>

    <!-- 断点重投弹窗（#135）：打开的只是这一步；请求在路上时 `pending` 把它按在原地 -->
    <RedeliverDialog
      v-model:visible="redeliverVisible"
      :step="redeliverTarget?.step ?? null"
      :business-key="redeliverTarget?.businessKey ?? ''"
      :pending="redeliverPending"
      :error="redeliverError"
      @confirm="confirmRedeliver"
    />

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
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import HistoryFilters from '../components/history/HistoryFilters.vue'
import HistoryCountsBar from '../components/history/HistoryCountsBar.vue'
import HistoryListPanel from '../components/history/HistoryListPanel.vue'
import MessageDetailPanel from '../components/history/MessageDetailPanel.vue'
import RedeliverDialog from '../components/history/RedeliverDialog.vue'
import { api, ApiError } from '../api/client'
import {
  DEFAULT_MODE, emptyFilters, isStepSelected, listQuery, noSelection, runDetail, runStepsPages,
  runStepsPath, selectRun, selectStep, statusParam, summaryQuery,
  type HistoryMode, type HistorySelection, type RunFilters,
} from '../history/console'
import {
  canRedeliver, redeliverDoneMessage, redeliverErrorText, redeliverFacts, redeliverPath,
  REDELIVER_TIMEOUT_MS,
} from '../history/redeliver'
import { previousStepOf } from '../history/diff'
import type { Job, JobRun, MessageStep, Page, RunsSummary, RunSummary } from '../types'

/** 断点重投接口的应答（后端只回这两格：原步的新状态与它的 id）。 */
interface RedeliverResult {
  status: string
  stepId: number
}

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
/**
 * 选中那条运行的**消息步**（#131）：右详情的消息 chips、步骤链与单步卡都从它长出来。
 *
 * 两个模式共用同一份：运行模式下点一条运行取它，消息步模式下点一行也取它所属运行的那一份
 * （右栏不区分「我是在哪个模式里点开的」）。同一运行内换步骤**不重复取**（`stepsRunId`）。
 */
const runSteps = ref<MessageStep[]>([])
/** 接口说的这条运行一共多少步：只用来算要追几页（没有上限，界面也不拿它说「截断」）。 */
const runStepsTotal = ref(0)
const runStepsLoading = ref(false)
const runStepsError = ref('')
/** 这份 `runSteps` 是哪条运行的（`null` = 还没取）；同一条运行换个步骤不再重复取。 */
const stepsRunId = ref<number | null>(null)
/** 取运行的响应可能乱序回来（连点两行）：token 一换，旧响应就作废。 */
let runToken = 0
/** 取消息步的响应也可能乱序回来（连点两行）。 */
let stepsToken = 0
/** 同一条：取列表的响应也可能乱序回来（连点两下模式开关）。 */
let loadToken = 0

/**
 * 选中那一步的**整行**（#132 的快照面板要的是它）。
 *
 * 索引那条路（`/history/runs/{id}/steps`）一行不带两个 TEXT 快照——那是它便宜的原因；
 * 快照本身按 id 另取整行（`/history/steps/{id}`），#131 的 ADR 补记把这一刀切在这儿：
 * 「列表要宽、详情要深」。**只在「手里这行不是要的那一行」时才取**：同一步不重复取，
 * 连点两下链上同一格不会发两次请求。
 */
const stepRow = ref<MessageStep | null>(null)
const stepRowError = ref('')
const stepRowLoading = ref(false)
/**
 * 手里这份整行是**哪一行**（`消息 id#步骤 id`；`null` = 还没取）。
 *
 * 键带上消息 id 是有原因的：消息的步骤号都从 1 起、步骤行的 id 也各不相同，但「同一个步骤号
 * 出现在两条消息里」是常态——只按步骤号去重的话，从 A 消息的第 1 步换到 B 消息的第 1 步时
 * 会被当成「还是那一步」而不重取，右栏继续铺 A 那份快照。真机脚本第一版就是这么抓到的。
 */
const stepRowKey = ref<string | null>(null)
/** 取整行的响应也可能乱序回来（连着点两格）。 */
let stepToken = 0

/** 一行（或「该取哪一行」）的键：消息 + 步骤行 id。两处调用同一个写法，避免口径分家。 */
function rowKeyOf(row: Pick<MessageStep, 'id' | 'messageId'>): string {
  return `${row.messageId}#${row.id}`
}

/**
 * **上一步**的整行（#133 的「与上一步对照」要它的数据快照）。
 *
 * 与 `stepRow` 完全对称的一套（同一个接口、同一个去重位、同一套乱序 token）：上一步的快照不在
 * 索引那条路里，所以它是**第二个**按 id 取整行的请求——选中一格最多两次请求（ADR-0016 决策 6
 * 那套「一次点击一次请求，不按行补拉」在这里仍然守着：不随消息步数增长）。
 * 当前这一步是这条消息的第一步时这一套是空的（`prevStepRow === null` = 没有上一步，不是取数失败）。
 */
const prevStepRow = ref<MessageStep | null>(null)
const prevStepRowError = ref('')
const prevStepRowLoading = ref(false)
const prevStepRowKey = ref<string | null>(null)
let prevStepToken = 0

/**
 * 断点重投弹窗（#135，原型 `#overlay`）。
 *
 * `redeliverTarget` 在**按下按钮那一刻**把「哪一步 + 那条消息的业务键」捕获下来：这一投在路上时
 * 列表与详情就会刷新（这一步可能已经不在当前筛选里了），弹窗里写的必须还是用户当时看到的那一步。
 * 业务键跟着一起捕获，理由相同——它由右详情的分组算出来，刷新之后可能已经取不到那一条消息了。
 */
const redeliverVisible = ref(false)
const redeliverTarget = ref<{ step: MessageStep; businessKey: string } | null>(null)
/** 这一投正在路上：入口与确认按钮 pending 且不可连点，弹窗也不许关。 */
const redeliverPending = ref(false)
/** 接口那句错误（空串 = 这一投还没失败过）；成功一次就清掉。 */
const redeliverError = ref('')
/**
 * 这次刷新要不要保住选中态（见 {@link refreshAfterRedeliver}）。
 *
 * 只有重投成功后的那次**自己发起的**双刷会把它立起来：那一趟的选中态由它自己管，
 * 不该被 `syncStepSelection` 中途收走。
 */
let keepSelectionOnLoad = false

/**
 * 归一之后的右详情（消息 / 下标 / 当前步骤）：**只算这一处**，右栏渲染与「按 id 取整行」
 * 都从它取——`runDetail` 里那次归一（选中的不在就落到第 1 条 / 第 1 步）因此只有一份说法。
 */
const runDetailView = computed(() => runDetail(runSteps.value, selection.value))
const currentStep = computed(() => runDetailView.value.step)

/**
 * 换一步（或者换消息）就该重取整行。
 *
 * **键是「哪条消息 + 哪一步」**，不是单独一个步骤号：消息的步骤号从 1 起，两条消息的第 1 步
 * 步骤号一模一样——只盯步骤号的话，从一条消息的第 1 步换到另一条消息的第 1 步时这个 watch 不响，
 * 右栏会继续铺上一条消息那一行（快照、对照全错位）。`/history/steps/{id}` 那一行是按 id 取的，
 * 所以 id 变了才是真的换了东西。
 */
watch(() => {
  const step = currentStep.value
  return step ? rowKeyOf(step) : null
}, (key) => {
  if (key === null) {
    stepRow.value = null
    stepRowError.value = ''
    stepRowKey.value = null
    prevStepRow.value = null
    prevStepRowError.value = ''
    prevStepRowLoading.value = false
    prevStepRowKey.value = null
    return
  }
  const step = currentStep.value
  if (!step) return
  if (stepRowKey.value !== key) void loadStepRow(step)
  const prev = previousStepOf(runDetailView.value.message, step)
  if (!prev) {
    // 这条消息的第一步：**没有上一步**，连请求都不发（界面上那一档说的是「没有上一步可对照」）
    prevStepRow.value = null
    prevStepRowError.value = ''
    prevStepRowLoading.value = false
    prevStepRowKey.value = null
    return
  }
  if (prevStepRowKey.value !== rowKeyOf(prev)) void loadPrevStepRow(prev)
})

/**
 * 选中那一步的整行（带两份快照原文）：404 / 接口挂了都在这里说话，界面那侧给「重试」。
 *
 * 失败时**把上一次那一行留着**（不清空）：清掉的话「与上一步对照」（#133）会当场变成
 * 「没有数据快照，没法对照」——把一次取数失败说成「这一步本来就没有快照」，
 * 那是这个页面最不该犯的错（快照那一侧本来就有自己的取数三态在说这件事）。
 */
async function loadStepRow(row: Pick<MessageStep, 'id' | 'messageId'>) {
  const key = rowKeyOf(row)
  const token = ++stepToken
  stepRowLoading.value = true
  stepRowError.value = ''
  stepRowKey.value = key
  try {
    const step = await api<MessageStep>(`/history/steps/${row.id}`)
    if (token !== stepToken) return
    stepRow.value = step
  } catch (e) {
    if (token !== stepToken) return
    stepRowError.value = e instanceof ApiError ? e.message : String(e)
    // 没取到就当没取过：按「重试」或者下次再点到这一步会重来一次（与消息步那条同一个做法）
    stepRowKey.value = null
  } finally {
    if (token === stepToken) stepRowLoading.value = false
  }
}

/**
 * **上一步**的整行（#133 的对照要它的数据快照）：与 {@link loadStepRow} 走同一条按 id 取整行的路
 * ——索引那条路上没有快照原文，而上一步与上上步都是「按 id 取一次」。
 *
 * 「上一步是谁」由 `diff` 那两个纯函数判（同一条消息、步骤号升序里紧邻的前一个），
 * 这里只负责取；同一行不重复取（`prevStepRowKey` 就是那个去重位），响应乱序由 token 作废。
 */
async function loadPrevStepRow(row: Pick<MessageStep, 'id' | 'messageId'>) {
  const key = rowKeyOf(row)
  const token = ++prevStepToken
  prevStepRowLoading.value = true
  prevStepRowError.value = ''
  prevStepRowKey.value = key
  try {
    const step = await api<MessageStep>(`/history/steps/${row.id}`)
    if (token !== prevStepToken) return
    prevStepRow.value = step
  } catch (e) {
    if (token !== prevStepToken) return
    prevStepRow.value = null
    prevStepRowError.value = e instanceof ApiError ? e.message : String(e)
    prevStepRowKey.value = null
  } finally {
    if (token === prevStepToken) prevStepRowLoading.value = false
  }
}

/** 快照那块上的重试：重取当前这一步的整行。 */
function retryStepRow() {
  const step = currentStep.value
  if (step) void loadStepRow(step)
}

/** 对照那一档上的重试：重取**上一步**的整行（当前那一步没坏，不连累它一起重取）。 */
function retryPrevStepRow() {
  const prev = previousStepOf(runDetailView.value.message, currentStep.value)
  if (prev) void loadPrevStepRow(prev)
}

/* ── 断点重投（#135） ─────────────────────────────────────────────── */

/**
 * 打开重投弹窗：**只有 `FAILED` 那一步能开**（票面第一条验收项）。
 *
 * 单步卡底部那一格本来就只在 `FAILED` 时铺按钮（其余状态摆的是一句「为什么不能投」），
 * 这里再挡一道是同一条判据的第二处落点——判据本身只有 `redeliver.ts` 一处。
 */
function openRedeliver() {
  const step = currentStep.value
  if (!step || !canRedeliver(step)) return
  redeliverTarget.value = { step, businessKey: runDetailView.value.message?.businessKey ?? '' }
  redeliverError.value = ''
  redeliverVisible.value = true
}

/**
 * 确认重投（弹窗上那一下就是**二次确认**）：投递 → 成功后关弹窗、说一句、**双刷**。
 *
 * 失败时弹窗**留着**并把接口那句原因铺出来（票面验收项五：界面给出错误、按钮恢复可点）——
 * 关掉弹窗等于把「没投上」这件事藏起来，#108 修的就是这类谎报。
 */
async function confirmRedeliver() {
  const target = redeliverTarget.value
  if (!target || redeliverPending.value) return
  redeliverPending.value = true
  redeliverError.value = ''
  try {
    const done = await api<RedeliverResult>(redeliverPath(target.step.id), {
      method: 'POST',
      // 「界面不停留在 pending」对**挂住不回**的请求同样成立：到点当成一次失败
      // （文案见 `redeliverTimeoutText`）——否则进行中弹窗不许关 + 请求永不回来 = 只能刷页面
      signal: AbortSignal.timeout(REDELIVER_TIMEOUT_MS),
    })
    redeliverVisible.value = false
    // 说「已置 XX」用的是接口回的那一格（`{status, stepId}`），界面这一侧不写死 REDELIVERED
    ElMessage.success(redeliverDoneMessage(redeliverFacts(target.step, target.businessKey), done.status))
    await refreshAfterRedeliver(target)
  } catch (e) {
    redeliverError.value = redeliverErrorText(e)
  } finally {
    redeliverPending.value = false
  }
}

/**
 * 重投成功后的**双刷**（票面验收项三：左侧列表与右侧详情同时是刷新后的内容）。
 *
 * 三件事要一起做，少一件就是「一半新一半旧」：
 *   ① `load()` 重取列表（连同计数条与每运行摘要）——`/history/runs` 与 `/history/steps` 两条路
 *      都由它按当前模式走，所以两个模式都不用另写一遍；
 *   ② `loadRunSteps()` 重取这条运行的消息步：消息 chips、步骤链、单步卡都从它长出来，
 *      原步那一格的芯片因此当场变成 `REDELIVERED`；
 *   ③ 按 id 再取**整行**与**上一步的整行**——同一步的行 id 没变，`stepRowKey` 那道去重键不响，
 *      只靠那个 watch 会把重投前那份快照一直铺着。
 *
 * 还有一处不显眼但必须做：列表刷新时 `syncStepSelection` 会把「不在这一页了」的选中态收回，
 * 而重投成功之后原步不再是 `FAILED`——默认只看 FAILED 的那一侧它就从列表里消失了（那一行没了
 * **就是**刷新后的内容）。票面要的是「原步仍显示 `REDELIVERED`」，所以这一趟把
 * {@link keepSelectionOnLoad} 立起来（别让 `load()` 中途把它收走），末尾再按原样钉一次
 * （`load()` 的失败分支会走 `clearSelection()`，那一趟选中态是真被清掉的）。
 */
async function refreshAfterRedeliver(target: { step: MessageStep; businessKey: string }) {
  keepSelectionOnLoad = true
  try {
    await Promise.all([load(), loadRunSteps(target.step.runId)])
  } finally {
    keepSelectionOnLoad = false
  }
  selection.value = selectStep(target.step)
  retryStepRow()
  retryPrevStepRow()
}

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
  if (keepSelectionOnLoad) return
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
  runSteps.value = []
  runStepsTotal.value = 0
  runStepsError.value = ''
  stepsRunId.value = null
  runToken++
  stepsToken++
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
  // 已经拿着这条运行的消息步就不再取（连点两下同一行不该重打十页）——与 onSelectStep 同一道门
  if (stepsRunId.value !== run.id) void loadRunSteps(run.id)
}

/**
 * 链上点一步、或点一颗消息 chip（chip 选的是那条消息的**第 1 步**，原型 `open-msg` 同一套）。
 *
 * 两件事都可能要补：运行详情（消息步模式下手里只有行上的 runId）、这条运行的消息步。
 * 同一条运行里换步骤两样都不重复取——一次点击最多一次请求，不是按行补拉的 N+1。
 */
function onSelectStep(step: MessageStep) {
  selection.value = selectStep(step)
  runError.value = ''
  void ensureRun(step.runId)
  if (stepsRunId.value !== step.runId) void loadRunSteps(step.runId)
}

/** 右栏那句「消息步没取到」上的重试：重取当前这条运行的。 */
function retryRunSteps() {
  if (selection.value.runId !== null) void loadRunSteps(selection.value.runId)
}

/**
 * 取一条运行的**全部**消息步（#131）。
 *
 * 票面把「展开一条运行只取前 100 个消息步」这个隐藏上限判了死刑：要么给提示、要么去掉。
 * 这里是**去掉**——走的是消息步索引接口（`/history/runs/{id}/steps`，一行不带那两个 TEXT 快照），
 * 先取一页（500），接口回 `total` 之后再按 {@link runStepsPages} 把剩下的页追回来，**没有上限**。
 * 一条 720 步的运行（实测库里最大的一条）整条索引 100 KB 上下、两次往返拿完；右栏因此永远显示
 * 完整的消息与步骤链，「失败 N 步」也就不可能撒谎。
 *
 * 要快照本身是另一条路：点开某一步再按 id 取整行（`/history/steps/{id}`），#132 的快照面板接那一头。
 */
async function loadRunSteps(runId: number) {
  const token = ++stepsToken
  runStepsLoading.value = true
  runStepsError.value = ''
  runSteps.value = []
  runStepsTotal.value = 0
  stepsRunId.value = runId
  try {
    const first = await api<Page<MessageStep>>(runStepsPath(runId, 1))
    if (token !== stepsToken) return
    runStepsTotal.value = first.total
    const all = [...first.records]
    for (let page = 2; page <= runStepsPages(first.total) && all.length < first.total; page += 1) {
      const next = await api<Page<MessageStep>>(runStepsPath(runId, page))
      if (token !== stepsToken) return
      if (!next.records.length) break
      all.push(...next.records)
    }
    runSteps.value = all
  } catch (e) {
    if (token !== stepsToken) return
    runSteps.value = []
    runStepsError.value = e instanceof ApiError ? e.message : String(e)
    // 没取到就当没取过：右栏那个「重试」按下去之外，下次再点到这条运行也会重来一次
    stepsRunId.value = null
  } finally {
    if (token === stepsToken) runStepsLoading.value = false
  }
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
