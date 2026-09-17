/**
 * 「试算」那一段（#116、#120，与 `useMappingDraft` 并列、互不依赖）。
 *
 * 它只做四件事：把请求发到那个只读接口（草稿 + 样例原文 + 目标字段清单）、把结果留在这一层、
 * **把那张逐字段证据表在跑完那一刻定下来**（连草稿指纹一起：草稿改了之后界面上要说明「下面还是上一次
 * 试算的结果」，而报告里那些规则文字也得是跑那一刻的）、以及**换了一边就把上一次的结果作废**
 * （逐行 ↔ 手写换过之后草稿已经不是那一份了）。判断、执行、归因全在后端——前端一条也不复制。
 *
 * 「点按钮才跑」是这一票的验收：这里没有 watch、没有 computed 触发请求，只有 `run()` 一个入口。
 */
import { computed, ref, type Ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { FieldMappingEditorContext } from '../types'
import { configFromRows } from './editor-model.ts'
import { expressionConfig, type EditorMode } from './expression.ts'
import { trialCanRun, trialEvidence, trialFingerprint, trialRequest, trialSampleOf,
  type TrialEvidenceRow, type TrialResult,
} from './trial.ts'
import type { WorkbenchState } from './useWorkbenchState.ts'

export interface MappingTrialOptions {
  /** 编辑器那一份共享状态（行、目标字段清单都在里面；草稿按它装）。 */
  state: WorkbenchState
  /** 编辑器上下文：左边那份样例是哪一步的、原文是什么。 */
  context: Ref<FieldMappingEditorContext | null>
  /** 现在在哪一边：逐行配置还是手写 JSONata（两条路的草稿不一样）。 */
  editorMode: Ref<EditorMode>
  /** 手写那一边的表达式正文。 */
  expressionText: Ref<string>
}

/** 试算这一层。 */
export function useMappingTrial(options: MappingTrialOptions) {
  const { rows, targets } = options.state
  const result = ref<TrialResult | null>(null)
  const running = ref(false)
  /** 请求本身没跑起来时那句话（网络 / 服务端错误）；「表达式跑不通」在 `result.error` 里。 */
  const requestError = ref('')
  /**
   * 跑那一刻的草稿指纹；还没跑过是空串。
   *
   * 报告里的字段路径、规则文字与那些数都是**跑那一刻的快照**，而草稿随时可能被改——留着它，
   * 界面才说得清「下面这些是不是现在这份草稿算出来的」。
   */
  const ranFingerprint = ref('')
  /**
   * 跑那一刻那张逐字段证据表（#120）。
   *
   * 它是**跑完就定下来**的一份快照，不是每次渲染现算的：报告里的字段路径与规则文字必须是**跑那一刻的**
   * （票里写明的「过期冻结」）——现算的话，改一行草稿，上一次报告里「产出它的规则」那列就会跟着变成
   * 新的说法，而右边那些值还是旧的，一份报告里前后对不上。草稿改了只该多挂一条横幅。
   */
  const evidence = ref<TrialEvidenceRow[]>([])

  /** 编辑器左边那份来源样例（原文在它的 `json` 里）。 */
  const source = computed(() => options.context.value?.source ?? null)
  /** 要发出去的那份原文；没有样例就是 null，那时按钮按不动。 */
  const sampleJson = computed(() => trialSampleOf(source.value))
  const canRun = computed(() => trialCanRun(source.value))

  /**
   * 现在这份草稿还是不是跑出来那份（#120 的过期冻结）。
   *
   * 指纹取自「保存会写出去的那一份草稿」，与请求里发的**同一个来源**——拿别的算（比方说直接看行）
   * 会出现「请求内容没变、界面却说变了」。还没有结果时一律 false：没东西可过期。
   */
  const stale = computed(() => {
    if (!result.value) return false
    return trialFingerprint(currentDraft.value) !== ranFingerprint.value
  })

  /** 现在这份草稿（与请求里发出去的是同一份）。 */
  const currentDraft = computed(() => options.editorMode.value === 'expression'
    ? expressionConfig(options.expressionText.value)
    : configFromRows(rows.value))

  /**
   * 点「试算」：把草稿、那份样例原文与目标字段清单发给后端真跑一遍。
   *
   * 草稿就是「按现在这样保存会写出去的那一份」（与校验、保存同一口径）：手写那一边发
   * `{mode:'EXPRESSION', expression, rules: []}`，后端按 mode 分岔。目标字段清单（#120）也带上——
   * 不带后端就不判「目标清单里有、草稿里没有这条规则」那一档。
   */
  async function run() {
    if (!canRun.value) {
      return
    }
    running.value = true
    requestError.value = ''
    const draft = currentDraft.value
    try {
      const response = await api<TrialResult>('/field-mapping-editor/trial', {
        method: 'POST',
        body: JSON.stringify(trialRequest({
          rows: rows.value,
          source: source.value,
          draft,
          targets: targets.value,
        })),
      })
      result.value = response
      // 证据表在这里就定下来：它读的是**发出去那一刻**的行，之后再改草稿也不跟着变
      evidence.value = trialEvidence(response, rows.value, options.state.actionNames.value)
      // 跑成功了才记指纹：结果不是这一次算出来的（请求没起来），就没什么可过期的
      ranFingerprint.value = trialFingerprint(draft)
    } catch (e) {
      // 请求没跑起来（网络 / 服务端错误）与「这份草稿跑不通」分开说：后者画在结果那一栏里
      result.value = null
      evidence.value = []
      ranFingerprint.value = ''
      requestError.value = (e as ApiError).message
    } finally {
      running.value = false
    }
  }

  /** 换了一边之后上一次的结果就不作数了：草稿已经不是那一份（与校验结果的处置一致）。 */
  function reset() {
    result.value = null
    evidence.value = []
    ranFingerprint.value = ''
    requestError.value = ''
  }

  return { result, running, requestError, stale, evidence, source, sampleJson, canRun, run, reset }
}
