/**
 * 样例换来一份字段清单之后，编辑器这一侧的那几件纯逻辑（#115 来源 / #118 目标）。
 *
 * 字段清单**怎么推**不在这里——那是后端复用样例展开做的（来源与目标两条粘贴路共用同一份实现）。
 * 这里只管：这份清单该显示成什么（`sourceTipOf`）、换来源步骤时它归到哪一步（`attributeSample`）、
 * 是数组时那句「按第 1 条推的」（`sampleNoteOf`），以及目标那一片粘出来时的那行小字（`targetNoteOf`）。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import type { EditorSourceSample } from '../types.ts'
import { stepLabel } from './labels.ts'

/**
 * 现在左边这份来源清单是怎么来的。
 *
 * 两条路是**并列**的（#115），不是谁兜底谁：`probe` 拿的是「这一步最近一次成功运行的输出」，
 * `paste` 拿的是手上那份来源数据。它决定换来源步骤时干什么（见 `attributeSample`）与顶上那句话怎么说。
 */
export type SampleOrigin = 'probe' | 'paste'

/** 面板顶上那句：这份清单取自哪儿。 */
export function sourceTipOf(source: EditorSourceSample | null, origin: SampleOrigin): string {
  if (!source) return '链上还没有更早的动作'
  if (origin === 'paste') return `这份样例按「${stepLabel(source.step, source.stepName)}」的输出来算`
  if (!source.fields.length) return `${stepLabel(source.step, source.stepName)} 还没有运行记录`
  return `来自 ${stepLabel(source.step, source.stepName)} 最近一次成功运行`
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

/** `sampleNoteOf` 认的那一件事：这份清单是「整份数组里第 1 条」推出来的。 */
export interface SampledFields {
  sampledFirstItem?: boolean
}

/**
 * **目标**字段清单是粘出来的时候，那一行小字；连库探测那条路传 `null`，没有这句话。
 *
 * 两件事都要写着：必填推不出来（NOT NULL 只有连库知道，推出来的一律当不必填——#118 验收四），
 * 以及是数组时只按第 1 条推。
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
 */
export function attributeSample(
  source: EditorSourceSample,
  step: number,
  actionNames: Record<number, string> = {},
): EditorSourceSample {
  return { ...source, step, stepName: step > 0 ? actionNames[step] ?? null : null }
}
