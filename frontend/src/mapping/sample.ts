/**
 * 样例换来一份字段清单之后，编辑器这一侧的那几件纯逻辑（#115 来源 / #118 目标 / #119 落库）。
 *
 * 字段清单**怎么推**不在这里——那是后端复用样例展开做的（来源与目标两条粘贴路共用同一份实现）。
 * 这里只管界面上的那几句话、一件事、以及保存时那份「写到哪一格」的说明：
 * 清单取自哪儿（`sourceTipOf` / `targetWhereOf`）、换来源步骤时它归到哪一步（`attributeSample`）、
 * 是数组时那句「按第 1 条推的」（`sampleNoteOf`）、目标那一片粘出来时的那行小字（`targetNoteOf`）、
 * 「这份是不是粘的」（`isPastedSample`，认后端给的标记）、以及保存那一刻手里这两份该落到哪一格
 * （`sampleSavesFor`，带原文 = 存下来 / 不带 = 把库里那一格清掉）。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import type { EditorSourceSample, TargetFieldsSample } from '../types.ts'
import { stepLabel } from './labels.ts'

/**
 * 样例上那两个「粘的」记号，后端用 `sourceKey` 那一格给（探测那条路给 `sample`，连库那条路给 `db:<表名>`）。
 *
 * 分成两个是因为界面上要说清手里这份是**这一次刚粘的**（`paste`）还是**库里存过的那份**（`stored`）——
 * 而保存那一刻两者都算「手里是粘的」（#119 规则 2），所以认「粘不粘」的那一处两个都认。
 */
const PASTED_SAMPLE_KEY = 'paste'
const STORED_SAMPLE_KEY = 'stored'

/** 这份样例是粘进来的吗（这一次粘的、或库里存过的那份都算；探测那条路给的都不是）。 */
export function isPastedSample(source: { sourceKey?: string | null } | null): boolean {
  return source?.sourceKey === PASTED_SAMPLE_KEY || source?.sourceKey === STORED_SAMPLE_KEY
}

/** 这份样例是**库里存过**的那份吗（#119）：界面据此写明「粘的（已存）」，而不是运行记录那条路。 */
export function isStoredSample(source: { sourceKey?: string | null } | null): boolean {
  return source?.sourceKey === STORED_SAMPLE_KEY
}

/** 手里这份样例取自哪儿——三选一，`sourceKey` 那一格的全部含义都收在这里。 */
export type SampleProvenance = 'stored' | 'pasted' | 'probed'

/**
 * 命名这份样例的来历：**库里存过的**压过**这一次粘的**，两个都压过**探测的**。
 *
 * 界面上的说法（来源顶上那句、目标那一片那一段）与「保存时算不算粘的」都从这一处分流，不各自认一遍
 * `sourceKey`——「粘的（已存）」与「粘的」是两种来历，别的地方再写一遍就会走岔。
 */
export function provenanceOf(source: { sourceKey?: string | null } | null): SampleProvenance {
  if (isStoredSample(source)) return 'stored'
  if (isPastedSample(source)) return 'pasted'
  return 'probed'
}

/** 面板顶上那句：这份清单取自哪儿。 */
export function sourceTipOf(source: EditorSourceSample | null): string {
  if (!source) return '链上还没有更早的动作'
  const from = stepLabel(source.step, source.stepName)
  switch (provenanceOf(source)) {
    // 存过的那份（#119）：写明它是粘的、而且已经存下来了——它既不是运行记录，也不是这一次刚粘的
    case 'stored':
      return `粘的（已存）：按「${from}」的输出来算`
    case 'pasted':
      return `这份样例按「${from}」的输出来算`
    default:
      return source.fields.length ? `来自 ${from} 最近一次成功运行` : `${from} 还没有运行记录`
  }
}

/**
 * 目标那一片顶上那句里「清单取自哪儿」那一段：#118 的粘的、#119 的粘的（已存）、连库探测给的表名。
 *
 * 与来源那侧同一个口径（都走 {@link provenanceOf}）：粘的两种分开说，探测那条路照旧把表名摆出来。
 */
export function targetWhereOf(sample: { sourceKey?: string | null } | null): string {
  if (!sample) return ''
  switch (provenanceOf(sample)) {
    case 'stored':
      return '粘的（已存）'
    case 'pasted':
      return '按粘的样例推的'
    default:
      return sample.sourceKey ?? ''
  }
}

/**
 * 粘的样例是一整个数组、只按第 1 条推的时候那句话；整份就是一个对象时没有这句话。
 *
 * 界面必须把这件事写出来：一份几十条的数据，第 1 条里没有的字段按第 1 条推就不在清单上。
 * 来源样例（#115）与目标字段清单（#118）都认这一个入参形状——两边都是「一份样例 + 从它推出来的字段」。
 */
export function sampleNoteOf(source: SampledFields | null): string {
  return source?.sampledFirstItem ? '这份样例是数组，按第 1 条推的字段' : ''
}

/** `sampleNoteOf` 与 `targetNoteOf` 认的那一件事：这份清单是「整份数组里第 1 条」推出来的。 */
interface SampledFields {
  sampledFirstItem?: boolean
}

/**
 * **目标**字段清单是粘出来的时候，那一行小字；连库探测那条路传 `null`，没有这句话。
 *
 * 两件事都要写着：必填推不出来（NOT NULL 只有连库知道，推出来的一律当不必填——#118 验收四），
 * 以及是数组时只按第 1 条推。库里存过的那份也是 JSON，这两件事一模一样（#119）。
 */
export function targetNoteOf(sample: SampledFields | null): string {
  if (!sample) return ''
  const sampled = sampleNoteOf(sample)
  return `按粘的这份样例推的字段：必填推不出来，一律当不必填${sampled ? `；${sampled}` : ''}`
}

/**
 * 换来源步骤：粘进来的那份样例是**给定的**，换步骤只换它归到哪一步，字段清单不动。
 *
 * 探测那条路不能这么干——那边的清单是「这一步的运行输出」，换了步骤必须重新取，否则会把 A 的字段
 * 配到 B 的步骤上（`FieldMappingWorkbench.loadSourceFields`）。粘的这条路反过来：数据是用户手上那份，
 * 来源步骤只是它算在哪一步（#115 验收四）——所以只换归属，连带把「动作 N · 名字」换过来。
 * 库里存过的那份（#119）同样算「给定的」：它也是粘进来的，换步骤照样只换归属。
 */
export function attributeSample(
  source: EditorSourceSample,
  step: number,
  actionNames: Record<number, string> = {},
): EditorSourceSample {
  return { ...source, step, stepName: step > 0 ? actionNames[step] ?? null : null }
}

/** 一次保存要落到某一格上的东西：归属 + 手里那份**原文**（`null` = 手里是探测来的）。 */
export interface SampleSave {
  /** 存下来的是这段原文；`null` 表示手里那份是探测来的 → 把库里这一格删掉（#119 规则 2）。 */
  json: string | null
}

/** 保存请求的体：两边各自带上归属键（来源 = 步骤，目标 = 写目标动作 id）。 */
export interface MappingSampleSaves {
  source?: SampleSave & { step: number }
  target?: SampleSave & { actionId: number }
}

/**
 * 「保存什么，下次打开就是什么」（#119 规则 2）：手里这两份样例该写到哪一格、或把哪一格清掉。
 *
 * <ul>
 *   <li>手里是粘的（这一次粘的或库里存过的那份）→ 带上**原文**存下来，同一格覆盖；</li>
 *   <li>手里是探测来的 → 不带原文，把那一格删掉（下次打开就回到探测那条路）；</li>
 *   <li>这一边压根没有东西（链上没有更早的动作又没存过 / 没有写目标动作）→ 这一边不发说明，
 *       库里不动。</li>
 * </ul>
 *
 * 归属键取自**保存这一刻**的状态：来源是这份样例现在归到哪一步（换过默认来源就按换过之后的算）、
 * 目标是这个编辑器解析出来的写目标动作 id——与后端那两格的键一一对应。
 */
export function sampleSavesFor(
  source: EditorSourceSample | null,
  targetSample: TargetFieldsSample | null,
  targetActionId: number | null,
): MappingSampleSaves {
  const saves: MappingSampleSaves = {}
  if (source && source.step != null) {
    saves.source = { step: source.step, json: pastedTextOf(source) }
  }
  if (targetActionId != null) {
    saves.target = { actionId: targetActionId, json: pastedTextOf(targetSample) }
  }
  return saves
}

/** 手里那份的原文：只有「粘的」（含库里存过的那份）才有；探测来的（也含原文缺失那种）一律当没有。 */
function pastedTextOf(sample: { sourceKey?: string | null; json?: string | null } | null): string | null {
  return provenanceOf(sample) === 'probed' ? null : sample?.json ?? null
}

/**
 * 粘完把**手里那份的原文**补齐：后端会把它一起带回来（#119 之后响应里有 `json`）；万一没有（后端起得早，
 * 还是改动之前那一份），就用刚发出去的那段顶上——「保存时把原文写回库里」靠的是这一格，它不能是空的。
 */
export function withRawText<T extends { json?: string | null }>(sample: T, sent: string): T {
  return sample.json ? sample : { ...sample, json: sent }
}
