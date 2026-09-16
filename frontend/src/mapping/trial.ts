/**
 * 「试算」那几件纯逻辑（#116）：发出去的请求体、以及回来那份结果在界面上怎么说。
 *
 * 判断对错、跑表达式、逐行归因全在后端那个只读接口里（前端一条也不复制，沿用第一轮的决定）：
 * 这里只管三件不属于判断的事——**装请求**（草稿 + 要跑的那份样例原文，与保存/校验同一份草稿口径）、
 * **把结果涂成人话的两处文案**（整份跑通了没有、标红的是什么意思）、以及**把 JSON 排成给人看的样子**。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import type { EditorSourceSample } from '../types.ts'
import { configFromRows } from './editor-model.ts'
import { issueLocation, type DraftConfig } from './draft.ts'
import { stepLabel } from './labels.ts'
import type { MapRow } from './rows.ts'

/** 后端返回的一行失败（`MappingTrialResult.Failure`）：哪一行、为什么。 */
export interface TrialFailure {
  /** 出错那一行的目标字段（数组元素子行写的是相对路径）。 */
  target: string | null
  /** 这一行所属数组行的目标字段；顶层行为 null。 */
  parent: string | null
  message: string
}

/** 后端返回的试算结果（`MappingTrialResult`）。 */
export interface TrialResult {
  /** 整份跑通了没有。 */
  ran: boolean
  /** 目标报文（整份跑通时）；没跑通为 null。 */
  output: unknown
  /** 整份没跑通时那句话；跑通了为 null。 */
  error: string | null
  /** 失败归因：单独跑这一行时失败的行与原因（整份跑通、或手写表达式那一边，都是空）。 */
  failures: TrialFailure[]
}

/** 发出去的请求体（与后端 `MappingTrialRequest` 一一对应）。 */
export interface TrialRequestBody {
  draft: DraftConfig
  /** 要跑的那份来源样例的**原文**；没有样例时是 null（那时没什么可跑的）。 */
  sampleJson: string | null
  /** 这份样例算在哪一步（0 = 任务输入）。 */
  sampleStep: number | null
}

/** 装请求用的那些东西：编辑器里现成的状态。 */
export interface TrialRequestOptions {
  rows: MapRow[]
  /** 编辑器左边那份来源样例（原文在它的 `json` 里）。 */
  source: EditorSourceSample | null
  /** 草稿本体。不给就是「按现在这样保存会写出去的规则」；手写那一边给 `expressionConfig(...)`。 */
  draft?: DraftConfig
}

/** 编辑器状态 → 试算请求。草稿就是保存会写出去的那一份，与校验请求同一口径。 */
export function trialRequest(options: TrialRequestOptions): TrialRequestBody {
  return {
    draft: options.draft ?? configFromRows(options.rows),
    sampleJson: trialSampleOf(options.source),
    sampleStep: options.source?.step ?? null,
  }
}

/**
 * 要跑的那份样例原文：编辑器左边那份的 `json`。
 *
 * 粘的那份一定有（#119），运行快照那条路也给（后端把同一份数据写成一段 JSON 带回来）——两条路都有，
 * 试算才有数据可跑。空白串当没有，别把一个空文本发出去。
 */
export function trialSampleOf(source: EditorSourceSample | null): string | null {
  const json = source?.json
  return typeof json === 'string' && json.trim() !== '' ? json : null
}

/** 有样例可跑吗（没有时那个按钮按不动，并说明缺的是哪一样）。 */
export function trialCanRun(source: EditorSourceSample | null): boolean {
  return trialSampleOf(source) != null
}

/**
 * 这份样例是数组时的那句话：**字段清单与试算跑的都是第 1 条**。
 *
 * 左边那一栏显示的是整份原文（用户手里就是那份东西），而真正跑进去的只有第 1 条——这件事必须写着，
 * 不然「左边是个数组、右边只算了一条」对不上（与 `sampleNoteOf` 同一件事，只是那句只说清单）。
 */
export function trialSampleNote(source: EditorSourceSample | null): string {
  return source?.sampledFirstItem ? '这份样例是数组：字段清单与试算都按第 1 条算。' : ''
}

/**
 * 草稿里有行的来源步骤**不是这份样例那一步**时的那句话。
 *
 * <p>试算只拿得到一份样例，只能把它装到 `$s_<sampleStep>` 上：别的步骤那些行在这一跑里取到的是 null，
 * 而整份照样「跑通了」——不写这句话，试算就会把「跨步骤引用取不到值」这件事盖成一片全绿，而这正是
 * 这一票要照亮的那类问题（#116 问题陈述）。显示出来的是**这一跑的事实**，不是一条校验规则。</p>
 *
 * @param rows   编辑器里的行（按它算哪些行的来源步骤跟样例不一样；只算配了目标字段的行——
 *               没配的行不进草稿，也就不参与这一跑）
 * @param source 左边那份样例（步骤不明时这句话不说，因为无从比起）
 */
export function trialStepNotice(rows: MapRow[], source: EditorSourceSample | null): string {
  const sampleStep = source?.step
  if (sampleStep == null) return ''
  const outsiders = rows.filter(row => row.target.trim() !== '' && (row.sourceStep ?? 0) !== sampleStep)
  if (!outsiders.length) return ''
  const steps = [...new Set(outsiders.map(row => row.sourceStep ?? 0))]
  return `${outsiders.length} 行的来源步骤不是这份样例算的那一步（${steps.map(step => stepLabel(step)).join('、')}）`
    + '——这一跑它们取不到数据。'
}

/** 左边那一栏：样例原文排成人看的样子（不是 JSON 就原样显示——至少能看见手里是什么）。 */
export function trialSampleText(sampleJson: string | null): string {
  if (!sampleJson) return ''
  try {
    return JSON.stringify(JSON.parse(sampleJson), null, 2)
  } catch {
    return sampleJson
  }
}

/**
 * 右边那一栏：目标报文排成人看的样子。
 *
 * 数组映射在结果里就是目标数组的**多行**（一个元素一行）——JSON 的缩进排版天然就是这样，
 * 不需要另造一套渲染：界面上看到的与目标系统收到的是同一份东西。
 */
export function trialOutputText(output: unknown): string {
  return JSON.stringify(output ?? null, null, 2) ?? ''
}

/**
 * 整份没跑通时，标红那几行的意思（票里要求界面写明这一句，逐字）。
 *
 * 逐行跑只是定位手段：真实执行是整份跑那一次，标红的行与「真跑会怎样」不一定是一回事。
 */
export const TRIAL_ATTRIBUTION_NOTE = '整份没跑通，标红的是单独跑这一行的结果'

/** 上一条的补充：为什么不能把标红当成真话（ADR-0012 决策 6 的两段式）。 */
export const TRIAL_ATTRIBUTION_WHY = '整份跑那一次才是真话；逐行跑只是用来定位是哪一行。'

/** 整份没跑通、而逐行跑也没能指到某一行时的话（不硬指一行）。 */
export const TRIAL_NO_ROW_NOTE = '没有哪一行单独跑也失败——整份那句错就是原因。'

/** 手写表达式那一边跑不通时的话：那边没有「行」可归因，别拿逐行那套说法去说它。 */
export const TRIAL_NO_ROW_NOTE_EXPRESSION = '手写表达式这边没有「行」——上面那句就是它跑不通的原因。'

/** 一行失败在界面上写成「在哪一行」（与校验结果那份同一个定位口径）。 */
export function trialFailureLine(failure: TrialFailure): string {
  return issueLocation(failure)
}

/** 整份那一次跑得怎么样（顶部那句）。 */
export function trialSummary(result: TrialResult | null): string {
  if (!result) return ''
  return result.ran ? '整份跑通了：下面是这份草稿算出来的目标报文' : `整份没跑通：${result.error ?? ''}`
}
