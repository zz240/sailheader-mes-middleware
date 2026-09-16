<template>
  <div class="trial">
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
      <!-- 左：来源字段样例（跑的就是它） -->
      <section class="trial-col">
        <header class="trial-col-head">来源字段样例</header>
        <pre v-if="sampleText" class="trial-text">{{ sampleText }}</pre>
        <p v-else class="trial-empty">
          左边还没有来源样例——用「从运行记录探测」或「粘贴 JSON」给一份，试算才有数据可跑。
        </p>
      </section>

      <!-- 右：这份草稿算出来的目标报文 -->
      <section class="trial-col">
        <header class="trial-col-head">目标报文</header>

        <p v-if="!result && !requestError" class="trial-empty">
          点「试算」跑一次。它什么都不改：不落库、不改配置。
        </p>

        <!-- 请求本身没跑起来（网络 / 服务端错误）：与「表达式跑不通」分开说 -->
        <p v-else-if="requestError" class="trial-bad">试算没跑起来：{{ requestError }}</p>

        <template v-else-if="result?.ran">
          <p class="trial-ok">{{ summary }}</p>
          <pre class="trial-text">{{ outputText }}</pre>
        </template>

        <template v-else>
          <p class="trial-bad">{{ summary }}</p>
          <!-- 「标红的是单独跑这一行的结果」这句话只在真有标红的行时写：一行都没有时不拿它去说 -->
          <template v-if="result?.failures.length">
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
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 底部第三个页签「试算」（#116）：左边来源字段样例、右边这份草稿算出来的目标报文。
 *
 * 判断、执行、逐行归因全在后端那个只读接口里（`mapping/trial.ts` 只装请求与排文案），所以这个组件
 * 只有三件事：把那两栏画出来、「试算」按钮、以及**没跑通时把标红的含义写在旁边**——两段式的区别
 * 不能靠用户猜（ADR-0012 决策 6）。
 */
import { computed } from 'vue'
import type { EditorSourceSample } from '../types'
import { sourceTipOf } from '../mapping/sample.ts'
import type { MapRow } from '../mapping/rows.ts'
import {
  TRIAL_ATTRIBUTION_NOTE, TRIAL_ATTRIBUTION_WHY, TRIAL_NO_ROW_NOTE, TRIAL_NO_ROW_NOTE_EXPRESSION,
  trialFailureLine, trialOutputText, trialSampleNote, trialSampleText, trialStepNotice, trialSummary,
  type TrialResult,
} from '../mapping/trial.ts'

const props = defineProps<{
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
  /** 编辑器里配好的行（算「哪些行的来源步骤跟这份样例不一样」用；手写那一边是空数组）。 */
  rows: MapRow[]
}>()

const emit = defineEmits<{ (e: 'run'): void }>()

/** 左边这份样例取自哪儿、算在哪一步（与左边来源面板同一处口径）。 */
const where = computed(() => sourceTipOf(props.source))
const sampleNote = computed(() => trialSampleNote(props.source))
const stepNotice = computed(() => trialStepNotice(props.rows, props.source))
const sampleText = computed(() => trialSampleText(props.sampleJson))
const outputText = computed(() => (props.result ? trialOutputText(props.result.output) : ''))
const summary = computed(() => trialSummary(props.result))
/** 整份没跑通时那两句说明与「指不出某一行」时那一句（逐行 / 手写各说各的）。 */
const attributionNote = TRIAL_ATTRIBUTION_NOTE
const attributionWhy = TRIAL_ATTRIBUTION_WHY
const noRowNote = computed(() => (props.rowsMode ? TRIAL_NO_ROW_NOTE : TRIAL_NO_ROW_NOTE_EXPRESSION))
/** 标红的那一行写成「在哪一行」：与校验结果同一处口径（`trial.ts` 里那一层）。 */
const failureLine = trialFailureLine
</script>

<style scoped>
.trial { display: flex; flex-direction: column; gap: 6px; }
.trial-bar { display: flex; align-items: center; gap: 8px; }
.trial-where { font-size: 12px; color: #6b7280; }
.trial-premise { margin: 0; font-size: 12px; color: #b45309; }
.trial-run { margin-left: auto; }

.trial-cols { display: flex; gap: 12px; align-items: stretch; }
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

.trial-ok { margin: 0; font-size: 12px; color: #15803d; }
.trial-bad { margin: 0; font-size: 12px; color: #b91c1c; }
.trial-attribution { margin: 0; font-size: 12px; color: #b91c1c; }
.trial-attribution-why { margin: 0; font-size: 12px; color: #9ca3af; }

.trial-failures { margin: 0; padding-left: 16px; max-height: 120px; overflow: auto; }
.trial-failure { font-size: 12px; color: #b91c1c; }
.trial-failure-row { font-weight: 600; margin-right: 6px; }
.trial-failure-why { color: #7f1d1d; }

/* 窄屏：两栏竖起来（与工作台其它几档同一个断点） */
@media (max-width: 1024px) {
  .trial-cols { flex-direction: column; }
}
</style>
