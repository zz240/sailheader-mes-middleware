/**
 * 「试算」那一段（#116，与 `useMappingDraft` 并列、互不依赖）。
 *
 * 它只做三件事：把请求发到那个只读接口、把结果留在这一层、以及**换了一边就把上一次的结果作废**
 * （逐行 ↔ 手写换过之后草稿已经不是那一份了）。判断、执行、归因全在后端——前端一条也不复制。
 *
 * 「点按钮才跑」是这一票的验收：这里没有 watch、没有 computed 触发请求，只有 `run()` 一个入口。
 */
import { computed, ref, type Ref } from 'vue'
import { api, ApiError } from '../api/client'
import type { FieldMappingEditorContext } from '../types'
import { expressionConfig, type EditorMode } from './expression.ts'
import { trialCanRun, trialRequest, trialSampleOf, type TrialResult } from './trial.ts'
import type { WorkbenchState } from './useWorkbenchState.ts'

export interface MappingTrialOptions {
  /** 编辑器那一份共享状态（行在里面；草稿按它装）。 */
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
  const { rows } = options.state
  const result = ref<TrialResult | null>(null)
  const running = ref(false)
  /** 请求本身没跑起来时那句话（网络 / 服务端错误）；「表达式跑不通」在 `result.error` 里。 */
  const requestError = ref('')

  /** 编辑器左边那份来源样例（原文在它的 `json` 里）。 */
  const source = computed(() => options.context.value?.source ?? null)
  /** 要发出去的那份原文；没有样例就是 null，那时按钮按不动。 */
  const sampleJson = computed(() => trialSampleOf(source.value))
  const canRun = computed(() => trialCanRun(source.value))

  /**
   * 点「试算」：把草稿与那份样例原文发给后端真跑一遍。
   *
   * 草稿就是「按现在这样保存会写出去的那一份」（与校验、保存同一口径）：手写那一边发
   * `{mode:'EXPRESSION', expression, rules: []}`，后端按 mode 分岔。
   */
  async function run() {
    if (!canRun.value) {
      return
    }
    running.value = true
    requestError.value = ''
    try {
      result.value = await api<TrialResult>('/field-mapping-editor/trial', {
        method: 'POST',
        body: JSON.stringify(trialRequest({
          rows: rows.value,
          source: source.value,
          draft: options.editorMode.value === 'expression'
            ? expressionConfig(options.expressionText.value)
            : undefined,
        })),
      })
    } catch (e) {
      // 请求没跑起来（网络 / 服务端错误）与「这份草稿跑不通」分开说：后者画在结果那一栏里
      result.value = null
      requestError.value = (e as ApiError).message
    } finally {
      running.value = false
    }
  }

  /** 换了一边之后上一次的结果就不作数了：草稿已经不是那一份（与校验结果的处置一致）。 */
  function reset() {
    result.value = null
    requestError.value = ''
  }

  return { result, running, requestError, source, sampleJson, canRun, run, reset }
}
