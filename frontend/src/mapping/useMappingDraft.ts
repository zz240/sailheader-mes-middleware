/**
 * 「校验配置」与保存那一段（#103 / #105 / #106，从 `FieldMappingWorkbench.vue` 拆出来，见 #117）。
 *
 * 它把编辑器里的行装成后端那个只读接口要的请求、把回来的问题按行放好、保存前拦一道——**一条校验规则
 * 都没有**（#98 D1）：对错全部由后端判断，这里只做三件不属于判断的事：
 *
 * 1. 发出去的草稿 = 「按现在这样保存会写出去的那一份」（`draft.ts` 的 `draftRequest`）；
 * 2. 回来的每条问题落在哪一行（`draft.ts` 的 `locateIssues`，按「目标字段 + 所属数组行」对）；
 * 3. 保存前先跑一次校验，**有错**就拦下并跳到第一条错误所在的行（警告不拦，#113）。
 *
 * 另有两句提醒是编辑器里的本地事实，后端那份草稿里没有也不该有（「自动匹配同名填上的行」#105、
 * 「清掉的行但目标字段必填」#113），它们挂进同一个问题列表，但不改后端返回的内容。
 *
 * 保存那一步还多一件事（#119）：手里这两份样例跟着一起落库——粘的存下来、探测来的把库里那一格清掉
 * （`sampleSavesFor`）。它排在动作本身写库**之前**：配置没存成时那份粘的也不丢。
 *
 * 它自己不发动作的保存请求：`onSaved` 把那份配置交回工作台，由工作台（唯一的入口那一层）去 `emit`。
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { FieldMappingEditorContext, TargetFieldsSample } from '../types'
import { configFromRows, flattenRows } from './editor-model.ts'
import { autoSourceNotices, clearedRequiredNotices } from './fast-config.ts'
import { isRowConfigured } from './rows.ts'
import { sampleSavesFor } from './sample.ts'
import {
  draftRequest, firstErrorRowId, issuesByRow, locateIssues,
  type DraftValidation,
} from './draft.ts'
import { expressionConfig, expressionReplacedNotice, hasExpression, type EditorMode } from './expression.ts'
import type { WorkbenchState } from './useWorkbenchState.ts'

/** 手写那一边的问题：没有「哪一行」可言，只有错误 / 警告两档。 */
export interface ExpressionIssue {
  severity: 'error' | 'warning'
  message: string
}

export interface MappingDraftOptions {
  /** 编辑器那一份共享状态（行、选中、清单都在里面）。 */
  state: WorkbenchState
  /** 编辑器上下文：来源样例是哪一步的（后端只判得了跟这份样例同步骤的规则）。 */
  context: Ref<FieldMappingEditorContext | null>
  /** 链上执行顺序更早的动作序号（顶部那个下拉去掉「任务输入」）：sourceStep 只能指向这些。 */
  earlierActionIndexes: ComputedRef<number[]>
  /** 这个动作的序号（新建动作时是准备占用的那个）。 */
  actionIndex: () => number | null
  /** 这个编辑器属于哪个任务（样例落库那条路要用，#119）。 */
  jobId: () => number
  /** 现在在哪一边：逐行配置还是手写 JSONata（两条路的草稿不一样）。 */
  editorMode: Ref<EditorMode>
  /** 手写那一边的表达式正文。 */
  expressionText: Ref<string>
  /** 目标字段清单**是粘的**时候的那一份（#118 / #119）；连库探测那条路是 null。 */
  targetSample: Ref<TargetFieldsSample | null>
  /** 已存配置里本来就有一段手写表达式（从手写切回逐行后再保存，要再确认一次会替换）。 */
  storedExpression: ComputedRef<boolean>
  /** 校验通过、确认也过了：把保存会写出去的那一份交回工作台。 */
  onSaved: (config: Record<string, any>) => void
}

/** 校验 + 保存这一层。 */
export function useMappingDraft(options: MappingDraftOptions) {
  const {
    state, context, earlierActionIndexes, actionIndex, jobId, editorMode, expressionText, targetSample,
    storedExpression, onSaved,
  } = options
  const { rows, targets, sourceFields, defaultStep, clearedRows, validation, jumpToRowId } = state

  const validating = ref(false)
  const saving = ref(false)
  /** 现在在逐行配置这一边（保存前那几个确认框只在那边问）。 */
  const rowsMode = computed(() => editorMode.value === 'rows')

  /**
   * 点「校验配置」：把草稿交给后端那个只读接口判。
   *
   * 判断对错全在那边，这里一条校验规则都没有——发出去的是「按现在这样保存会写出去的那一份」，
   * 收回来的是问题列表和编译产物，前端只负责把每条问题挂到对应的那一行上（`draft.ts`）。
   * 手写表达式那一边（#106）发的是 `{mode: 'EXPRESSION', expression, rules: []}`：后端按 `mode`
   * 分岔，EXPRESSION 下不校验规则树——这正是界面上写着的那句「这条路跳过规则树校验」。
   * 返回 null 表示这次没跑起来（网络或服务端错误），调用方据此决定要不要继续。
   */
  async function validateConfig(): Promise<DraftValidation | null> {
    validating.value = true
    try {
      const result = await api<DraftValidation>('/field-mapping-editor/validate', {
        method: 'POST',
        body: JSON.stringify(draftRequest({
          rows: rows.value,
          targets: targets.value,
          sourceFields: sourceFields.value,
          // 左边清单是哪一步的探测结果，后端就拿它判哪一步的来源路径
          sourceFieldsStep: context.value?.source?.step ?? null,
          earlierActionIndexes: earlierActionIndexes.value,
          defaultSourceStep: defaultStep.value,
          actionIndex: actionIndex(),
          draft: editorMode.value === 'expression' ? expressionConfig(expressionText.value) : undefined,
        })),
      })
      validation.value = result
      return result
    } catch (e) {
      ElMessage.error(`校验没跑起来：${(e as ApiError).message}`)
      return null
    } finally {
      validating.value = false
    }
  }

  /**
   * 「自动匹配同名」填上的那些行，校验时要给的那条提示（#105）。
   *
   * 由前端自己出：后端那份草稿里没有「这一行是自动填的」这个信息，也不该有——自动与否是编辑器里的
   * 本地事实。所以这些提示挂进同一个问题列表，但**不改后端返回的内容**（`validation` 还是原来那份）。
   */
  const autoNotices = computed(() => autoSourceNotices(rows.value))

  /**
   * 被清除掉、而目标清单里标着必填的那些行的提醒（#113 验收五）。
   *
   * 后端点「校验配置」时也会报同一句话（那一行没进草稿，清单里的必填字段就没着落），但那要等人点一次；
   * 清完那一刻就该看见「这个必填字段是空的」——所以由前端出这条提醒，措辞与后端那条**逐字一致**。
   *
   * 每次重算都换一份新的对象：它要挂进合并后的问题列表，与后端返回的那些混在一起——共用同一份对象
   * 会让两条通路悄悄互相影响（`checks.ts` 那组用例按值断言，也是同一个道理）。
   */
  const clearedNotices = computed(() =>
    clearedRequiredNotices(rows.value, targets.value, new Set(clearedRows.value.keys()))
      .map(notice => ({ ...notice })))

  /**
   * 后端的问题 + 前端那几条提醒：界面上一视同仁地列出来、按同一套口径定位到行。
   *
   * **点过校验之后，后端说的才算**：清单没配全的行后端同样报「必填的目标字段没有配置来源」，两边一起
   * 挂上去只会让同一行上出现两条一样的话。校验之前没有后端那份结果，那几条提醒就是这一行唯一的说法。
   */
  const mergedValidation = computed<DraftValidation | null>(() => {
    const result = validation.value
    // 手写表达式那一边没有行，「自动匹配填上的行」「清除掉的行」那几条提醒也不该跟过去
    if (editorMode.value === 'expression') return result
    if (!result) return null
    return {
      ...result,
      warnings: [...result.warnings, ...autoNotices.value],
    }
  })

  /**
   * 界面上要显示的问题：校验之前只有编辑器里那几条提醒（清掉的行），校验之后是后端那一份。
   *
   * 校验之前那份**不是一次校验结果**（`valid` 真不真、编译产物是什么，后端都还没说过），所以它只拿来
   * 列那几条提醒，界面上不会因为它给出一行「没问题」——那个绿色标签仍然只由后端那次结果给。
   */
  const displayedValidation = computed<DraftValidation | null>(() => {
    if (mergedValidation.value) return mergedValidation.value
    if (editorMode.value === 'expression') return null
    const notices = clearedNotices.value
    if (!notices.length) return null
    return {
      valid: true,
      errors: [],
      warnings: notices,
      compiled: { jsonata: null, rulesJson: null },
    }
  })

  /** 后端真跑过一次校验没有（行右侧那个状态标签只认它）。 */
  const hasValidation = computed(() => validation.value != null)

  /** 手写这一边的问题列表：没有「哪一行」可言，只按错误在前列原文。 */
  const expressionIssues = computed<ExpressionIssue[]>(() => {
    const result = mergedValidation.value
    if (!result) return []
    return [
      ...result.errors.map(issue => ({ severity: 'error' as const, message: issue.message })),
      ...result.warnings.map(issue => ({ severity: 'warning' as const, message: issue.message })),
    ]
  })

  /** 后端返回的问题 + 前端提醒 → 界面上的问题列表（每条带上它落在哪一行）。 */
  const locatedIssues = computed(() => (displayedValidation.value
    ? locateIssues(flattenRows(rows.value), displayedValidation.value)
    : []))

  /** 每一行的问题（键是行 id）：行右侧那三种状态由行列表那份取词算出来。 */
  const rowIssues = computed(() => issuesByRow(locatedIssues.value))

  /**
   * 问题列表顶上那句汇总。
   *
   * 后端真跑过时按它的结果报数；还没跑过、只是编辑器里那几条提醒（清掉的行）时**不说「0 个错误」**
   * ——那会让人以为这份草稿已经被判过一遍了（#113 走查）。那几句提醒自己就说清了是什么。
   *
   * （「改了行而结果还是上一次那份」这条不在这里治：校验本来就是点一次跑一次的（#98 决定，README
   * 待办里也写着）。这里只保证**没跑过**的时候不冒充跑过。）
   */
  const issueSummary = computed(() => {
    const result = displayedValidation.value
    if (!result) return ''
    if (!hasValidation.value) {
      return `${result.warnings.length} 条提醒（还没校验过这份草稿）`
    }
    if (!result.errors.length && !result.warnings.length) return '没有发现问题'
    return `${result.errors.length} 个错误 · ${result.warnings.length} 个警告`
  })

  /**
   * 配置 → 保存会写出去的那一份（编辑器保存时用）。
   *
   * 逐行这一边：一行一条规则，只管配好的行。手写那一边：整段表达式就是这份配置
   * （与后端 `mode=EXPRESSION` 的约定一致，规则树不参与）。
   */
  function getConfig(): Record<string, any> {
    return editorMode.value === 'expression'
      ? expressionConfig(expressionText.value)
      : configFromRows(rows.value)
  }

  /** 配好的行数：没配好的行留在编辑器里继续填，但不进规则（进了后端会整份拒掉）。 */
  const configuredCount = computed(() => rows.value.filter(isRowConfigured).length)
  /** 有目标字段、但参数还差一口的行（常量没填值、枚举表空、拼接没片段、没选来源）。 */
  const untouchedCount = computed(() =>
    rows.value.filter(row => row.target.trim() !== '' && !isRowConfigured(row)).length)

  /** 「自动匹配同名」填上、还没被人碰过的行数（保存前要人确认一遍）。 */
  const autoFilledCount = computed(() => flattenRows(rows.value).filter(e => e.row.auto).length)

  /**
   * 保存时把手里那两份样例交给后端（#119 规则 2）：粘的存下来、探测来的把库里那一格清掉。
   *
   * <p>没有要写 / 要清的格子时一个请求都不发（来源侧压根没有样例、或没有写目标动作）。归属键与原文
   * 都由 `sampleSavesFor` 从**保存这一刻**的状态算（换过默认来源就按换过之后的步骤落格）。</p>
   *
   * <p>失败时**不往下走**（这次就当没保存）：这一份粘的文本是用户手上唯一那份东西（响应回来之后浏览器里
   * 就没别的副本了），它没存住就不该假装配置存好了——报一句、留在抽屉里让人重试（重试安全：这个端点是
   * 幂等的）。反过来，样例存住而动作那一步失败时，库里那一格已经写下了：抽屉还开着，重试会覆盖它，
   * 用户取消就走人——留下一格，比丢掉他手上唯一那份东西好。</p>
   */
  async function saveSamples(): Promise<boolean> {
    const saves = sampleSavesFor(
      context.value?.source ?? null, targetSample.value, context.value?.writeTarget?.actionId ?? null)
    if (!saves.source && !saves.target) return true
    try {
      await api(`/jobs/${jobId()}/field-mapping-editor/samples`, {
        method: 'PUT',
        body: JSON.stringify(saves),
      })
      return true
    } catch (e) {
      ElMessage.error(`样例没存下来，这次没有保存：${(e as ApiError).message}`)
      return false
    }
  }


  /**
   * 保存：手写那一边只说清「写了没有」，逐行这一边先把没配好的行 / 自动填上的行问一遍，
   * 两边的草稿都要**先过一遍后端那个只读接口**——这份草稿后端收不收，只有后端说了算（前端不复制
   * 任何一条校验规则）。
   */
  async function save() {
    // 手写这一边：先把「写了没有」说清楚（编辑器里的本地事实），对错仍然交给后端那个只读接口判
    if (editorMode.value === 'expression' && !hasExpression(expressionText.value)) {
      ElMessage.warning('还没有写表达式——这一整段就是这次映射要执行的东西')
      return
    }
    // 从手写切回逐行之后再保存：那一刻说过一次「规则树会替换掉表达式」，这里再确认一次（保存是最后一道）
    if (rowsMode.value && storedExpression.value) {
      try {
        await ElMessageBox.confirm(
          `${expressionReplacedNotice()}`,
          '会替换手写表达式',
          { type: 'warning', confirmButtonText: '替换并保存', cancelButtonText: '取消' },
        )
      } catch {
        return
      }
    }
    if (rowsMode.value && !configuredCount.value) {
      ElMessage.warning(rows.value.some(r => r.target.trim())
        ? '还没有配好的字段映射——每个目标字段至少要选一个来源，常量要填一个值，枚举表要有一条，拼接要有片段'
        : '还没有任何目标字段——改一个写目标动作，或者先点「重新探测目标字段」，也可以直接手输目标字段路径')
      return
    }
    saving.value = true
    try {
      // 校验排在「还有几行没配好」前面：有错的时候保存整份都进不去，先把这个说清楚
      //（那份确认框问的是「要不要少存几行」，问早了会把真正的拦下原因压到后面）。
      // 保存前一定校验一次：这份草稿后端收不收，只有后端说了算（前端不复制任何一条校验规则）。
      // 没跑起来（网络/服务端错误）时不拦——真正的兜底还是保存时那个 400。
      const result = await validateConfig()
      if (result?.errors.length) {
        jumpToRowId(firstErrorRowId(locatedIssues.value))
        ElMessage.error(`还有 ${result.errors.length} 个错误，先改掉再保存`)
        return
      }
      // 手写这一边没有「哪几行没配好」这回事：那些确认框只在逐行配置那边问
      if (rowsMode.value && untouchedCount.value) {
        const ok = await ElMessageBox.confirm(
          `还有 ${untouchedCount.value} 行没配好（没选来源 / 常量没填值 / 枚举表空 / 拼接没片段）。这些行不会被保存。`,
          '有没配好的行',
          { type: 'warning', confirmButtonText: '只保存配好的', cancelButtonText: '回去补' },
        ).then(() => true).catch(() => false)
        if (!ok) return
      }
      // 「自动匹配同名」填上的行：保存前要人认一遍（票里：提醒确认后才算人工结果）。
      // 确认过就照常保存——只是提醒，不是拦截（#98 D5：问题只提示，不阻止保存）。
      if (rowsMode.value && autoFilledCount.value) {
        const ok = await ElMessageBox.confirm(
          `有 ${autoFilledCount.value} 行的来源是「自动匹配同名」填上的，不是你手点的。`
          + '确认这些字段对得上再保存。',
          '自动填上的行还没确认',
          { type: 'warning', confirmButtonText: '确认过了，保存', cancelButtonText: '回去看看' },
        ).then(() => true).catch(() => false)
        if (!ok) return
      }
      // 样例跟着这次保存一起落库（#119 规则 2）。排在 onSaved 前面：动作写库那一步失败时（抽屉还开着）
      // 这份粘的也已经存住了，重试不会把它弄丢。
      if (!await saveSamples()) return
      onSaved(getConfig())
    } finally {
      saving.value = false
    }
  }

  return {
    validating, saving, hasValidation,
    expressionIssues, locatedIssues, rowIssues, issueSummary,
    validateConfig, save, getConfig,
  }
}
