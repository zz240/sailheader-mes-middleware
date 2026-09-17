/**
 * 「试算」那几件纯逻辑（#116、#120）：发出去的请求体、以及回来那份结果在界面上怎么说。
 *
 * 判断对错、跑表达式、逐行归因全在后端那个只读接口里（前端一条也不复制，沿用第一轮的决定）：
 * 这里只管四件不属于判断的事——**装请求**（草稿 + 要跑的那份样例原文 + 目标字段清单，与保存/校验同一份
 * 草稿口径）、**把结果涂成人话的几处文案**（整份跑通了没有、标红的是什么意思、逐字段那一档是什么意思）、
 * **把逐字段状态摆成一张证据表**（哪一行、什么值、哪条规则产出的，以及点一下跳到账本哪一行）、
 * 以及**把 JSON 排成给人看的样子**。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import type { EditorSourceSample } from '../types.ts'
import { configFromRows, flattenRows, rowReferenceLabel, type TargetField } from './editor-model.ts'
import { issueLocation, issueWhere, rowIdByTargetKey, rowTargetKey, type DraftConfig } from './draft.ts'
import { emptyRowLabel, stepLabel } from './labels.ts'
import { transformLabelOf } from './transforms.ts'
import type { MapRow } from './rows.ts'

/** 后端返回的一行失败（`MappingTrialResult.Failure`）：哪一行、为什么。 */
export interface TrialFailure {
  /** 出错那一行的目标字段（数组元素子行写的是相对路径）。 */
  target: string | null
  /** 这一行所属数组行的目标字段；顶层行为 null。 */
  parent: string | null
  message: string
}

/**
 * 一个目标字段这一跑的结局（#120 的四档）。
 *
 * `NULL` 与 `DROPPED` 必须分开，这是这一票的核心：键在结果里而值是 null，说明规则跑到了、就是没值；
 * 键不在结果里，才是「配了但取不到值 / 被丢掉」。
 */
export type TrialFieldState = 'VALUED' | 'DROPPED' | 'NULL' | 'UNMAPPED'

/** 后端返回的一个目标字段的状态（`MappingTrialResult.FieldStatus`）。 */
export interface TrialFieldStatus {
  /** 目标字段路径（数组元素子行写相对路径，与校验问题同一口径）。 */
  target: string
  /** 这一行所属数组行的目标字段；顶层行为 null。 */
  parent: string | null
  /** 结果里那个值：数组组行是 null（行数看 `arrayRows`）；元素字段是按元素分开的取值数组。 */
  value: unknown
  status: TrialFieldState
  /** 为什么被丢掉（只有 DROPPED 有）；其余为 null。 */
  reason: string | null
  /** 数组组行的行数；元素字段行为 null。 */
  arrayRows: number | null
}

/** 后端返回的计量（`MappingTrialResult.Metrics`）：目标字段总数与四档各有多少。 */
export interface TrialMetrics {
  /** 叶子字段总数（目标清单里的 + 这一跑真算出来的；数组组行不算一个字段）。 */
  targets: number
  /** 结果里有这个键的字段数（含算出来是 null 的，即 `valued + null`）。 */
  valued: number
  dropped: number
  unmapped: number
  /** 所有数组组的行数合计。 */
  arrayRows: number
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
  /** 逐字段状态（#120）；整份没跑通时是空数组。 */
  fields: TrialFieldStatus[]
  /** 这一跑的计量（#120）；整份没跑通时全是 0。 */
  metrics: TrialMetrics
}

/** 发出去的请求体（与后端 `MappingTrialRequest` 一一对应）。 */
export interface TrialRequestBody {
  draft: DraftConfig
  /** 要跑的那份来源样例的**原文**；没有样例时是 null（那时没什么可跑的）。 */
  sampleJson: string | null
  /** 这份样例算在哪一步（0 = 任务输入）。 */
  sampleStep: number | null
  /**
   * 编辑器里那份目标字段清单；没有清单时是 null。
   *
   * 不带这一格后端就不判 `UNMAPPED`——「目标清单里有、草稿里没有这条规则」这件事只有拿清单比才看得出来。
   */
  targetFields: TargetField[] | null
}

/** 装请求用的那些东西：编辑器里现成的状态。 */
export interface TrialRequestOptions {
  rows: MapRow[]
  /** 编辑器左边那份来源样例（原文在它的 `json` 里）。 */
  source: EditorSourceSample | null
  /** 草稿本体。不给就是「按现在这样保存会写出去的规则」；手写那一边给 `expressionConfig(...)`。 */
  draft?: DraftConfig
  /** 编辑器里那份目标字段清单（#120）；不给就是不判 `UNMAPPED`。 */
  targets?: TargetField[]
}

/** 编辑器状态 → 试算请求。草稿就是保存会写出去的那一份，与校验请求同一口径。 */
export function trialRequest(options: TrialRequestOptions): TrialRequestBody {
  return {
    draft: options.draft ?? configFromRows(options.rows),
    sampleJson: trialSampleOf(options.source),
    sampleStep: options.source?.step ?? null,
    targetFields: options.targets ?? null,
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

/**
 * 结论条那一句（#120 三层里的第一层）：整份那一次跑得怎么样。
 *
 * 不复用 #116 那句「下面是这份草稿算出来的目标报文」：这一版把报文收进了原始 JSON 里，上面摆的是
 * 逐字段那一张表，照抄会指错地方。跑通了那句是票里写死的说法——**这就是真实执行会发出的目标报文**。
 */
export function trialConclusion(result: TrialResult | null): string {
  if (!result) return ''
  return result.ran
    ? '整份跑通 · 这就是真实执行会发出的目标报文'
    : `整份没跑通 · ${result.error ?? ''}`
}

// ---------------------------------------------------------------- 逐字段状态（#120）

/**
 * 四档判决在界面上的两种说法：值那一列的颜色档，与状态那一格的人话。
 *
 * 两种说法放在同一张表里，是因为它们判的是同一件事（`CONTEXT.md` 的「逐字段状态」）——分成两处
 * 各自展开一个四臂 switch，加一档时漏改一处，界面上就会出现「落值」配着红颜色这种自相矛盾。
 * 色调名与状态名故意不同名：那是 CSS 那一侧的词（`.trial-evidence-row.valued`），不是接口契约。
 */
const FIELD_STATE_TEXT: Record<TrialFieldState, { label: string; tone: TrialEvidenceRow['stateTone'] }> = {
  VALUED: { label: '落值', tone: 'valued' },
  NULL: { label: '算出来是 null', tone: 'null' },
  DROPPED: { label: '配了但取不到值', tone: 'dropped' },
  UNMAPPED: { label: '还没配', tone: 'unmapped' },
}

/** 一个字段状态在界面上显示成人话（界面上不出现英文状态名）。 */
export function trialFieldStateLabel(status: TrialFieldState): string {
  return FIELD_STATE_TEXT[status].label
}

/** 计量条里的一格：显示的短标签、那个数、以及说明口径的悬停提示。 */
export interface TrialMetricsCell {
  label: string
  /** 要显示的格子内容。判不了的那一档给一个破折号：「没法判」与「一个都没有」不是一回事。 */
  value: number | string
  title: string
}

/**
 * 计量条的四格（#120），顺序就是界面上的顺序。
 *
 * 每格都带一句 `title`：这四个数各自是什么口径不写清楚，`落值 7 / 7` 会让人以为「字段全都有值」——
 * 而它数的是「结果里有这个键」，算出来是 null 的也在里面（`valued` 含 `NULL` 那一档）。
 *
 * @param targetsKnown 这一跑带没带目标字段清单（编辑器里探到过或粘过目标才有）。
 *                     **没带时「未映射」那一格不给 0**：后端没东西可比，`0` 会被读成「一个都不缺」，
 *                     而事实是「这一档压根没判」（票里的待拍板项一：不加清单那一格只能空着）。
 */
export function trialMetricsCells(metrics: TrialMetrics, targetsKnown = true): TrialMetricsCell[] {
  return [
    {
      // 「落值 N / M」：那个分母（目标叶子字段总数）是这一格的非说不可的一半——没有它，「落值 7」
      // 看不出是「七个都有值」还是「一共就七个字段」（票里的计量条就是这么写的）
      label: '落值字段',
      value: `${metrics.valued} / ${metrics.targets}`,
      title: `结果里有这个键的字段数（含算出来是 null 的）：${metrics.valued} / ${metrics.targets}`,
    },
    {
      label: '目标数组',
      value: metrics.arrayRows,
      title: `所有目标数组这一跑各跑出多少行，合计 ${metrics.arrayRows} 行`,
    },
    targetsKnown
      ? { label: '未映射', value: metrics.unmapped, title: '目标清单里有、草稿里还没有这条规则的字段数' }
      : {
          label: '未映射',
          value: '—',
          title: '这一跑没带目标字段清单（左边还没探到、也没粘过目标），这一档不判',
        },
    { label: '静默省略', value: metrics.dropped, title: '配了规则、但这一跑结果里没有这个键的字段数' },
  ]
}

/** 试算值里的占位说法：这几种都不是值本身，摆在那里只是让人一眼看出「这里没有值」。 */
const TRIAL_NULL_TEXT = '(null)'
const TRIAL_MISSING_TEXT = '(没有这个键)'
const TRIAL_EMPTY_TEXT = '(没有值)'
/** 数组组行那一格里的话（它的值不是标量，能看的只有行数）。 */
const TRIAL_GROUP_TEXT = '数组'
/** 这一列没有东西可显示时的那一格。 */
const TRIAL_BLANK_TEXT = '—'

/**
 * 证据表里的一行。字段是**预先算好的文字**（不是原始数据）：界面上要摆成「先说结论、再说数、
 * 最后才是证据」那一套，格式化属于这一层，模板只管排。
 */
export interface TrialEvidenceRow {
  /** 列表用的唯一键（后端一条字段一行，`target + parent` 就是它的身份）。 */
  key: string
  /** 后端给的目标字段路径（数组元素子行是相对路径）。 */
  target: string
  /** 这一行所属数组行的目标字段；顶层行是 null。 */
  parent: string | null
  /** 这一行在界面上读成「在哪一行」：与校验问题列表**完全同一处口径**（`draft.ts` 的 `issueWhere`）。 */
  path: string
  /** 试算值摆成人看的样子。 */
  valueText: string
  /** 四档的人话（界面上不出现英文状态名）。 */
  stateLabel: string
  /** 后端说的原因（只有 DROPPED 有）——为什么这个字段被丢掉了，界面挂在整行的提示里。 */
  reason: string | null
  /** 产出这个值的那条规则的人话；`UNMAPPED` 的行写「草稿里没有这个字段的规则」。 */
  ruleText: string
  /** 账本（行列表）里对应哪一行；对不上（比方说清单里有而草稿里没有规则）时为 null。 */
  rowId: number | null
  /** 数组组行的行数；元素字段行与普通行都是 null。 */
  arrayRows: number | null
  /** 数组所在的那一行（界面上把它的子字段缩一排摆出来）。 */
  isGroup: boolean
  /** 值这一列是哪一档（模板据此上色；与人话标签是一份判据的两种说法）。 */
  stateTone: 'valued' | 'null' | 'dropped' | 'unmapped'
}

/**
 * 逐字段状态 → 证据表的行（#120）。
 *
 * 三件事在这一层做掉，模板一个判断都不做：
 * 1. `path` 走 `issueWhere` 那份口径（顶层写完整路径、数组元素写「数组行 · 元素字段」）；
 * 2. `rowId` 走 `rowIdByTargetKey` 那份口径（「目标字段 + 所属数组行」→ 账本那一行）——与校验问题
 *    列表**同一份定位代码**，所以「点一下跳到账本那一行」两处不会有第二种对法；行本身再从同一趟
 *    摊平结果里按 id 取回来，不另写一遍匹配；
 * 3. `ruleText` 从**传进来的那份行**上取（`source` / `transform` 用 `labels.ts` 与 `transforms.ts`
 *    现成的那几句人话）。
 *
 * 第 3 条是这一层的关键：**这里读的是调用方给的行，不是「现在编辑器里的行」**。试算那一层在跑完
 * 那一刻就把结果传进来定死（`useMappingTrial`），所以草稿改了之后，报告里那些规则文字仍然是**跑
 * 那一刻的快照**（票里的「过期冻结」）——改了草稿只该多一条横幅，不该让一份旧报告前后对不上。
 *
 * 结果里没有 `fields`（整份没跑通、或后端还没带这两段）时返回空数组，界面上那张表就整块不铺。
 */
export function trialEvidence(
  result: TrialResult | null,
  rows: MapRow[],
  actionNames: Record<number, string> = {},
): TrialEvidenceRow[] {
  if (!result?.fields.length) return []
  const entries = flattenRows(rows)
  const rowIdByKey = rowIdByTargetKey(entries)
  const rowById = new Map(entries.map(entry => [entry.row.id, entry.row]))
  return result.fields.map(field => {
    const rowId = rowIdByKey.get(rowTargetKey(field.target, field.parent)) ?? null
    const row = rowId == null ? null : rowById.get(rowId) ?? null
    return {
      key: rowTargetKey(field.target, field.parent),
      target: field.target,
      parent: field.parent,
      path: issueWhere(field.target, field.parent),
      valueText: trialValueText(field),
      stateLabel: trialFieldStateLabel(field.status),
      reason: field.reason,
      ruleText: row ? ruleTextOf(row, actionNames) : '草稿里没有这个字段的规则',
      rowId,
      arrayRows: field.arrayRows,
      isGroup: field.arrayRows != null,
      stateTone: FIELD_STATE_TEXT[field.status].tone,
    }
  })
}

/** 后端给的那个值摆成人看的样子：组行说清它是数组，元素字段按元素分开摆，null 与「没有这个键」分开说。 */
function trialValueText(field: TrialFieldStatus): string {
  if (field.arrayRows != null) return `${field.arrayRows} 行 · ${TRIAL_GROUP_TEXT}`
  if (field.status === 'UNMAPPED') return TRIAL_EMPTY_TEXT
  if (field.status === 'DROPPED') return TRIAL_MISSING_TEXT
  if (Array.isArray(field.value)) {
    return field.value.length ? field.value.map(v => trialScalarText(v)).join('、') : TRIAL_EMPTY_TEXT
  }
  return trialScalarText(field.value)
}

/** 一个标量值摆成人看的样子（null 就是 null——它和「键不在」是两档，别混成同一个空）。 */
function trialScalarText(value: unknown): string {
  if (value == null) return TRIAL_NULL_TEXT
  if (typeof value === 'object') return JSON.stringify(value) ?? TRIAL_BLANK_TEXT
  return String(value)
}

/**
 * 这一行现在配的规则读成人话。
 *
 * 来源字段的读法与行列表上那一份**共用 `rowReferenceLabel`**（含「数组元素内用相对路径、
 * `$root.` 读成外层」这两条口径）：这里自己拼一遍的话，同一行在两处会读成两种说法。
 * 证据表是按后端给的元素字段行铺的，所以这一行的来源按相对路径念。
 *
 * 来源那一格空着时（界面上那一行还没配取值方式）不留一个空格子：照行列表的说法写「还没选来源」。
 */
function ruleTextOf(row: MapRow, actionNames: Record<number, string>): string {
  const sourceText = rowReferenceLabel(row, actionNames, true)
  return transformLabelOf(row, () => sourceText) || emptyRowLabel()
}

/** 草稿的稳定指纹（#120 的过期冻结）：同一份草稿算出来同一个串，改一行就变。 */
export function trialFingerprint(draft: DraftConfig | null | undefined): string {
  return JSON.stringify(draft ?? null)
}

