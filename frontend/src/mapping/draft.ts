/**
 * 「校验配置」（#103）：把编辑器里的行装成后端那个只读接口要的请求，再把回来的问题按行放好。
 *
 * 这里**一条校验规则都没有**（#98 D1）——对错全部由后端判断，前端只做两件不属于判断的事：
 *
 * 1. 发出去的草稿 = 「按现在这样保存会写进 rules 的那份规则」（`configFromRows`，没配好的行不进草稿；
 *    这一点前端本来就要保住，否则后端会拿「缺少来源字段 source」把整份配置拒掉）；
 * 2. 回来的每条问题落在哪一行——靠「目标字段 + 它所属的数组行」对上，只做定位，不看内容。
 *
 * 纯逻辑，不碰 DOM 也不发请求，所以可以用 `node --experimental-strip-types` 直接检查（见 `checks.ts`）。
 */
import type { MapRow } from './rows.ts'
import { configFromRows, type FlatRow, type SourceField, type TargetField } from './editor-model.ts'

/** 后端返回的一条问题（`MappingDraftValidation.Issue`）。 */
export interface DraftIssue {
  /** 出错那条规则的目标字段；整份草稿级别的问题为 null。 */
  target: string | null
  /** 这条规则所属数组行的目标字段；顶层规则为 null。 */
  parent: string | null
  message: string
}

/** 编译产物文本（`MappingDraftValidation.Compiled`）：两个标签页各一份。 */
export interface DraftCompiled {
  jsonata: string | null
  rulesJson: string | null
}

/** 后端返回的校验结果（`MappingDraftValidation`）。 */
export interface DraftValidation {
  valid: boolean
  errors: DraftIssue[]
  warnings: DraftIssue[]
  compiled: DraftCompiled
}

/** 发出去的请求体（与后端 `MappingDraftRequest` 一一对应）。 */
export interface DraftRequestBody {
  draft: { rules: Record<string, any>[] }
  targets: TargetField[]
  sourceFields: SourceField[]
  sourceFieldsStep: number | null
  earlierActionIndexes: number[]
  defaultSourceStep: number | null
  actionIndex: number | null
}

/** 装请求用的那些东西：编辑器里现成的状态。 */
export interface DraftRequestOptions {
  rows: MapRow[]
  targets: TargetField[]
  sourceFields: SourceField[]
  /**
   * `sourceFields` 是哪个动作的探测结果。
   *
   * 后端只拿得到这一份样例，所以它只能判来源步骤跟这份一样的规则：别的步骤的字段不在这份清单里，
   * 拿它去判会把配好的行报成「来源字段不在样例里」（顶部一换默认来源，已配的行留在原步骤，就会整片误报）。
   */
  sourceFieldsStep: number | null
  /** 链上执行顺序更早的动作序号（`sourceStep` 只能指向这些）。 */
  earlierActionIndexes: number[]
  /** 编辑器顶部那个「默认来源」。 */
  defaultSourceStep: number | null
  /** 这个动作的序号（新建动作时是准备占用的那个）。 */
  actionIndex: number | null
}

/** 编辑器状态 → 校验请求。草稿就是保存会写出去的那一份，不另造一份。 */
export function draftRequest(options: DraftRequestOptions): DraftRequestBody {
  return {
    draft: configFromRows(options.rows),
    targets: options.targets,
    sourceFields: options.sourceFields,
    sourceFieldsStep: options.sourceFieldsStep,
    earlierActionIndexes: options.earlierActionIndexes,
    defaultSourceStep: options.defaultSourceStep,
    actionIndex: options.actionIndex,
  }
}

/** 一条问题 + 它落在哪一行（`rowId` 为 null = 这一条定位不到某一行）。 */
export interface LocatedIssue extends DraftIssue {
  severity: 'error' | 'warning'
  rowId: number | null
}

/**
 * 后端返回的问题 → 界面上的问题列表：错误在前、警告在后（各自保持后端给的顺序，那是规则树的顺序）。
 * `entries` 是编辑器当前的行树（`flattenRows`，含收起的子行——收起的行也要挂得上问题）。
 */
export function locateIssues(entries: FlatRow[], result: DraftValidation): LocatedIssue[] {
  const rowIdByKey = rowKeys(entries)
  const locate = (issue: DraftIssue, severity: 'error' | 'warning'): LocatedIssue => ({
    ...issue,
    severity,
    rowId: rowIdByKey.get(issueKey(issue.target, issue.parent)) ?? null,
  })
  return [
    ...result.errors.map(issue => locate(issue, 'error')),
    ...result.warnings.map(issue => locate(issue, 'warning')),
  ]
}

/** 每一行的问题（键是行 id）：行右侧那个「有错 / 有警告 / 没问题」用它。 */
export function issuesByRow(located: LocatedIssue[]): Map<number, LocatedIssue[]> {
  const out = new Map<number, LocatedIssue[]>()
  for (const issue of located) {
    if (issue.rowId == null) continue
    out.set(issue.rowId, [...(out.get(issue.rowId) ?? []), issue])
  }
  return out
}

/** 一行的问题状态（#103 的三种状态）。 */
export function rowIssueState(issues: LocatedIssue[] | undefined): 'error' | 'warning' | 'ok' {
  if (!issues?.length) return 'ok'
  return issues.some(issue => issue.severity === 'error') ? 'error' : 'warning'
}

/**
 * 保存被拦下时跳到哪一行：第一条**定位得到**的错误所在的行。
 * 定位不到行的那几条（整份草稿级别的）跳不过去，跳过它们接着找。
 */
export function firstErrorRowId(located: LocatedIssue[]): number | null {
  return located.find(issue => issue.severity === 'error' && issue.rowId != null)?.rowId ?? null
}

/** 一条问题在问题列表里显示成「在哪一行」。 */
export function issueLocation(issue: DraftIssue): string {
  if (!issue.target) return '整份配置'
  return issue.parent ? `${issue.parent} · ${issue.target}` : issue.target
}

/**
 * 行 → id 的索引：键是「所属数组行的目标字段 + 这一行的目标字段」。
 *
 * 没填目标字段的空行不进索引：它本来就不产出规则，`(null, null)` 那种整份草稿级别的问题
 * （「字段映射动作需要至少一条映射规则」）会被一头扣在它身上，看起来像这一行出的错。
 *
 * 两份行落到同一个键上时**后一行赢**：那就是「用户加了一行重复的目标字段」这种情况，
 * 而后端编译也是在后出现的那一条规则上报「目标字段重复映射」的（`JsonataRuleCompiler.insert`
 * 按规则顺序插入，撞上已经插进去的那一条时抛错）。
 */
function rowKeys(entries: FlatRow[]): Map<string, number> {
  const out = new Map<string, number>()
  for (const entry of entries) {
    const target = entry.row.target.trim()
    if (!target) continue
    out.set(issueKey(target, entry.parent?.target.trim() ?? null), entry.row.id)
  }
  return out
}

function issueKey(target: string | null, parent: string | null): string {
  return `${parent ?? ''}\u0000${target ?? ''}`
}
