<template>
  <div class="trial">
    <!-- 过期冻结（#120）：草稿改了之后报告里那些路径与数都是上一次的快照，这句话必须挂在结论之前 -->
    <div v-if="stale" class="trial-stale">
      <span class="trial-stale-text">草稿已改动 · 下面还是上一次试算的结果</span>
      <el-button class="trial-stale-run" size="small" type="primary" plain :loading="running" :disabled="!canRun"
        @click="emit('run')">重新试算</el-button>
    </div>

    <!-- 顶栏：左边那份样例算在哪一步 + 「试算」。点按钮才跑（改了行不会自己重算，#116 验收二） -->
    <div class="trial-bar">
      <span class="trial-where">{{ where }}</span>
      <el-button
        class="trial-run"
        size="small"
        type="primary"
        :loading="running"
        :disabled="!canRun"
        @click="emit('run')"
      >试算</el-button>
    </div>
    <!-- 这一跑的两个前提：样例是数组时跑的是第 1 条；草稿里别的来源步骤那些行取不到数据 -->
    <p v-if="sampleNote" class="trial-premise">{{ sampleNote }}</p>
    <p v-if="stepNotice" class="trial-premise">{{ stepNotice }}</p>

    <div class="trial-cols">
      <!-- 左：来源字段样例（跑的就是它，这一跑唯一的输入证据） -->
      <section class="trial-col">
        <header class="trial-col-head">来源字段样例</header>
        <pre v-if="sampleText" class="trial-text">{{ sampleText }}</pre>
        <p v-else class="trial-empty">
          左边还没有来源样例——用「从运行记录探测」或「粘贴 JSON」给一份，试算才有数据可跑。
        </p>
      </section>

      <!-- 右：一次带判决的执行——先说结论，再说数，最后才是证据（#120 的界面结构） -->
      <section class="trial-col">
        <header class="trial-col-head">逐字段试算结果</header>

        <p v-if="!result && !requestError" class="trial-empty">
          点「试算」跑一次。它什么都不改：不落库、不改配置。
        </p>

        <!-- 请求本身没跑起来（网络 / 服务端错误）：与「表达式跑不通」分开说 -->
        <p v-else-if="requestError" class="trial-bad">试算没跑起来：{{ requestError }}</p>

        <template v-else-if="result">
          <!-- 1. 结论条：整份跑通了没有。右边注明这一跑的口径（前面那些前提句在哪一步、按第几条算） -->
          <div class="trial-verdict" :class="result.ran ? 'ok' : 'bad'">
            <span class="trial-verdict-dot" />
            <span class="trial-verdict-text">{{ conclusionText }}</span>
            <span v-if="scopeNote" class="trial-verdict-scope">{{ scopeNote }}</span>
          </div>

          <!-- 2. 计量条：每个数都带 title 说明口径（「落值」含算出来是 null 的那些） -->
          <div class="trial-metrics">
            <span v-for="cell in metricsCells" :key="cell.label" class="trial-metric" :title="cell.title">
              <span class="trial-metric-label">{{ cell.label }}</span>
              <span class="trial-metric-value">{{ cell.value }}</span>
            </span>
          </div>

          <!-- 3. 证据表：每个目标字段一行，点一下跳到账本那一行 -->
          <div v-if="evidence.length" class="trial-evidence">
            <div class="trial-evidence-head">
              <span>输出路径</span>
              <span>试算值</span>
              <span>产出它的规则</span>
            </div>
            <div class="trial-evidence-body">
              <button
                v-for="row in evidence"
                :key="row.key"
                class="trial-evidence-row"
                :class="[row.stateTone, { group: row.isGroup, child: row.parent != null }]"
                :title="rowTitle(row)"
                @click="jump(row.rowId)"
              >
                <span class="trial-evidence-path">{{ row.path }}</span>
                <span class="trial-evidence-value">
                  <span class="trial-evidence-value-text">{{ row.valueText }}</span>
                  <span class="trial-evidence-state">{{ row.stateLabel }}</span>
                </span>
                <span class="trial-evidence-rule">{{ row.ruleText }}</span>
              </button>
            </div>
          </div>
          <p v-else-if="result.ran" class="trial-evidence-none">
            这一跑没带回逐字段状态（后端还没给这一段），下面只有原始结果。
          </p>

          <!-- 4. 归因块：整份抛错时那两段式原话（#116 的几句一个字不改） -->
          <template v-if="!result.ran">
            <template v-if="result.failures.length">
              <p class="trial-attribution">{{ attributionNote }}</p>
              <p class="trial-attribution-why">{{ attributionWhy }}</p>
              <ul class="trial-failures">
                <li v-for="(failure, index) in result.failures" :key="index" class="trial-failure">
                  <span class="trial-failure-row">{{ failureLine(failure) }}</span>
                  <span class="trial-failure-why">{{ failure.message }}</span>
                </li>
              </ul>
            </template>
            <p v-else class="trial-attribution">{{ noRowNote }}</p>
          </template>

          <!-- 5. 原始 JSON 视图：结构化那一份在上面，原文这一份默认收着（#120 验收五） -->
          <details class="trial-raw">
            <summary class="trial-raw-head">{{ rawHead }}</summary>
            <pre class="trial-text">{{ outputText }}</pre>
          </details>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 底部第三个页签「试算」（#116、#120）：左边来源字段样例，右边一次带判决的执行。
 *
 * 判断、执行、逐行归因全在后端那个只读接口里（`mapping/trial.ts` 只装请求与排文案），所以这个组件
 * 只有三件事：把那几层摆出来（**结论 → 数 → 证据 → 归因 → 原始 JSON**）、「试算」按钮、
 * 以及**没跑通时把标红的含义写在旁边**——两段式的区别不能靠用户猜（ADR-0012 决策 6）。
 *
 * 「点一下跳到账本那一行」自己也做不了（那是选中与收起集合的事），所以发一条 `jump` 出去；
 * 清了搜索词再跳也在工作台那一层（这里只管发那一行的 id）。
 */
import { computed } from 'vue'
import type { EditorSourceSample } from '../types'
import { sourceTipOf } from '../mapping/sample.ts'
import type { MapRow } from '../mapping/rows.ts'
import {
  TRIAL_ATTRIBUTION_NOTE, TRIAL_ATTRIBUTION_WHY, TRIAL_NO_ROW_NOTE, TRIAL_NO_ROW_NOTE_EXPRESSION,
  trialConclusion, trialFailureLine, trialMetricsCells, trialOutputText, trialSampleNote,
  trialSampleText, trialStepNotice, type TrialEvidenceRow, type TrialResult,
} from '../mapping/trial.ts'

const props = withDefaults(defineProps<{
  /** 编辑器左边那份来源样例（原文在它的 `json` 里；没有样例时是 null）。 */
  source: EditorSourceSample | null
  /** 请求体里要发出去的那份原文（== 左边这份的原文）；没有就是 null。 */
  sampleJson: string | null
  /** 有样例可跑吗（没有时按钮按不动）。 */
  canRun: boolean
  /** 上一次试算的结果；还没跑过是 null。 */
  result: TrialResult | null
  /** 这一次在跑（按钮转圈）。 */
  running: boolean
  /** 请求本身没跑起来时后端的说法（网络 / 服务端错误）；正常是空串。 */
  requestError: string
  /** 现在是不是逐行配置那一边：手写表达式没有「行」可归因，那句话要说另一种。 */
  rowsMode: boolean
  /** 编辑器里配好的行（只用来算「哪些行的来源步骤跟这份样例不一样」——那一句说的是当下这份草稿）。 */
  rows: MapRow[]
  /**
   * 逐字段证据表的行（`trialEvidence` 的结果），由工作台从试算那一层传进来。
   *
   * 它**不在这个组件里现算**：报告是跑那一刻的一份快照，规则文字必须停在跑那一刻（#120 的过期冻结）。
   */
  evidence: TrialEvidenceRow[]
  /** 跑完之后草稿又改了（#120 的过期冻结）：这时上面那些都是上一次的快照。 */
  stale?: boolean
  /** 这一跑带没带目标字段清单（没带时「未映射」那格显示破折号，不是 0）。 */
  targetsKnown?: boolean
}>(), {
  // 少给这两格的调用点照样画得出来（没过期就不挂横幅；按「带了清单」算）
  stale: false,
  targetsKnown: true,
})

const emit = defineEmits<{
  (e: 'run'): void
  /** 点了证据表里某一行：跳到账本那一行（定位不到行的那些点了也不动）。 */
  (e: 'jump', rowId: number | null): void
}>()

/** 左边这份样例取自哪儿、算在哪一步（与左边来源面板同一处口径）。 */
const where = computed(() => sourceTipOf(props.source))
const sampleNote = computed(() => trialSampleNote(props.source))
const stepNotice = computed(() => trialStepNotice(props.rows, props.source))
const sampleText = computed(() => trialSampleText(props.sampleJson))
const outputText = computed(() => (props.result ? trialOutputText(props.result.output) : ''))

/**
 * 结论条那句：整份跑通了／没跑通（引擎原话带上）。文案在 `trial.ts` 里（票里写死的说法，`checks.ts` 钉着）。
 */
const conclusionText = computed(() => trialConclusion(props.result))

/** 结论条右边那句口径：这一跑是按哪份样例、哪一种读法算的（前面那几句前提收成一句）。 */
const scopeNote = computed(() => [sampleNote.value, stepNotice.value].filter(Boolean).join(' '))

const metricsCells = computed(() =>
  props.result ? trialMetricsCells(props.result.metrics, props.targetsKnown) : [])

/** 原始 JSON 那一栏的标题：跑通了是报文，没跑通就是那句错（原文都摆出来）。 */
const rawHead = computed(() => (props.result?.ran ? '原始 JSON（目标报文）' : '原始 JSON（这一跑的输出）'))

/** 整份没跑通时那两句说明与「指不出某一行」时那一句（逐行 / 手写各说各的）。 */
const attributionNote = TRIAL_ATTRIBUTION_NOTE
const attributionWhy = TRIAL_ATTRIBUTION_WHY
const noRowNote = computed(() => (props.rowsMode ? TRIAL_NO_ROW_NOTE : TRIAL_NO_ROW_NOTE_EXPRESSION))
/** 标红的那一行写成「在哪一行」：与校验结果同一处口径（`trial.ts` 里那一层）。 */
const failureLine = trialFailureLine

/** 点证据表里的一行：跳行这件事在工作台那一层做（它才知道搜索词与收起集合）。 */
function jump(rowId: number | null) {
  emit('jump', rowId)
}

/**
 * 一行上的悬停提示：能跳的说能跳，跳不过去的说清为什么；后端给了原因（`DROPPED`）就把它一并说出来
 * ——「为什么这个字段没进目标报文」的答案在后端那句话里，界面别自己编一个。
 */
function rowTitle(row: TrialEvidenceRow): string {
  const where = row.rowId == null
    ? '账本里现在没有对应的一行（清单里有、还没配，或者那一行已经不在了），跳不过去'
    : '点一下跳到账本那一行'
  return row.reason ? `${where} · ${row.reason}` : where
}
</script>

<style scoped>
.trial { display: flex; flex-direction: column; gap: 6px; }
.trial-bar { display: flex; align-items: center; gap: 8px; }
.trial-where { font-size: 12px; color: #6b7280; }
.trial-premise { margin: 0; font-size: 12px; color: #b45309; }
.trial-run { margin-left: auto; }

/* 过期冻结那条横幅：报告是上一次的快照，得在结论之前就说清 */
.trial-stale {
  display: flex; align-items: center; gap: 8px; padding: 4px 8px;
  border: 1px solid #fde68a; background: #fffbeb; border-radius: 4px;
}
.trial-stale-text { font-size: 12px; color: #b45309; }
.trial-stale-run { margin-left: auto; }

.trial-cols { display: flex; gap: 12px; align-items: flex-start; }
.trial-col {
  flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 4px;
}
.trial-col-head { font-size: 12px; font-weight: 600; color: #374151; }

.trial-text {
  margin: 0; max-height: 200px; overflow: auto;
  font-family: Consolas, monospace; font-size: 12px; line-height: 1.5;
  background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 4px; padding: 8px;
  white-space: pre; color: #111827;
}
.trial-empty { margin: 0; font-size: 12px; color: #9ca3af; }

/* 1. 结论条：绿点 / 红点一眼分得出，右边那半句是这一跑的口径 */
.trial-verdict {
  display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap;
  padding: 4px 8px; border-radius: 4px; border: 1px solid;
}
.trial-verdict.ok { border-color: #bbf7d0; background: #f0fdf4; }
.trial-verdict.bad { border-color: #fecaca; background: #fef2f2; }
.trial-verdict-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; align-self: center;
}
.trial-verdict.ok .trial-verdict-dot { background: #16a34a; }
.trial-verdict.bad .trial-verdict-dot { background: #dc2626; }
.trial-verdict-text { font-size: 12px; font-weight: 600; }
.trial-verdict.ok .trial-verdict-text { color: #15803d; }
.trial-verdict.bad .trial-verdict-text { color: #b91c1c; }
.trial-verdict-scope { margin-left: auto; font-size: 11px; color: #6b7280; }

/* 2. 计量条 */
.trial-metrics { display: flex; flex-wrap: wrap; gap: 4px; }
.trial-metric {
  display: flex; align-items: baseline; gap: 4px; padding: 1px 6px;
  border: 1px solid #e5e7eb; border-radius: 4px; background: #fafafa;
}
.trial-metric-label { font-size: 11px; color: #6b7280; }
.trial-metric-value { font-size: 12px; font-weight: 600; color: #111827; }

/* 3. 证据表：三列（输出路径 / 试算值 / 产出它的规则），一行一个目标字段 */
.trial-evidence { border: 1px solid #e5e7eb; border-radius: 4px; overflow: hidden; }
.trial-evidence-head,
.trial-evidence-row {
  display: grid; grid-template-columns: minmax(140px, 1fr) minmax(120px, 1fr) minmax(140px, 1.2fr);
  gap: 8px; align-items: baseline;
}
.trial-evidence-head {
  font-size: 11px; color: #6b7280; background: #f3f4f6; padding: 3px 8px;
}
.trial-evidence-body { max-height: 260px; overflow: auto; }
.trial-evidence-row {
  width: 100%; text-align: left; font: inherit; font-size: 12px;
  background: none; border: 0; border-top: 1px solid #f3f4f6;
  padding: 3px 8px; cursor: pointer;
}
.trial-evidence-row:hover { background: #f3f4f6; }
.trial-evidence-path { font-family: Consolas, monospace; color: #374151; overflow-wrap: anywhere; }
/* 数组行的子字段缩一排：看得出它是那个数组里的一个字段 */
.trial-evidence-row.child .trial-evidence-path { padding-left: 14px; }
.trial-evidence-value { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.trial-evidence-value-text {
  font-family: Consolas, monospace; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.trial-evidence-state { flex-shrink: 0; font-size: 11px; border-radius: 3px; padding: 0 4px; border: 1px solid; }
.trial-evidence-rule { color: #6b7280; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 值那一列的四档：VALUED 正常、NULL 黄、DROPPED 红、UNMAPPED 灰（#120 的核心区分） */
.trial-evidence-row.valued .trial-evidence-state { color: #15803d; border-color: #bbf7d0; background: #f0fdf4; }
.trial-evidence-row.valued .trial-evidence-value-text { color: #111827; }
.trial-evidence-row.null .trial-evidence-state { color: #b45309; border-color: #fde68a; background: #fffbeb; }
.trial-evidence-row.null .trial-evidence-value-text { color: #b45309; }
.trial-evidence-row.dropped .trial-evidence-state { color: #b91c1c; border-color: #fecaca; background: #fef2f2; }
.trial-evidence-row.dropped .trial-evidence-value-text { color: #b91c1c; }
.trial-evidence-row.unmapped .trial-evidence-state { color: #6b7280; border-color: #e5e7eb; background: #f9fafb; }
.trial-evidence-row.unmapped .trial-evidence-value-text { color: #9ca3af; }
/* 数组组行：它自己不是值，是「下面那几个子字段属于这个数组」 */
.trial-evidence-row.group .trial-evidence-path { font-weight: 600; }
.trial-evidence-none { margin: 0; font-size: 12px; color: #9ca3af; }

.trial-bad { margin: 0; font-size: 12px; color: #b91c1c; }
.trial-attribution { margin: 0; font-size: 12px; color: #b91c1c; }
.trial-attribution-why { margin: 0; font-size: 12px; color: #9ca3af; }

/* 5. 原始 JSON：默认收着，展开就是跑出来那份原文 */
.trial-raw-head { font-size: 12px; color: #6b7280; cursor: pointer; padding: 2px 0; }
.trial-raw[open] .trial-raw-head { margin-bottom: 4px; }

.trial-failures { margin: 0; padding-left: 16px; max-height: 120px; overflow: auto; }
.trial-failure { font-size: 12px; color: #b91c1c; }
.trial-failure-row { font-weight: 600; margin-right: 6px; }
.trial-failure-why { color: #7f1d1d; }

/* 窄屏：两栏竖起来（与工作台其它几档同一个断点） */
@media (max-width: 1024px) {
  .trial-cols { flex-direction: column; }
}
/* 更窄时证据表那三列横着放不下：改成一路排下来（标签省掉，状态与规则跟在值后面） */
@media (max-width: 640px) {
  .trial-evidence-head { display: none; }
  .trial-evidence-row { grid-template-columns: 1fr; gap: 2px; }
  .trial-evidence-row.child .trial-evidence-path { padding-left: 12px; }
}
</style>
