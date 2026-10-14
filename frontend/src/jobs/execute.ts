/**
 * 「执行任务」这一次调用的**共用处**（#147 复查抽出来的）：发 `POST /jobs/{id}/execute`，
 * 再把三种结果分开告诉调用方。
 *
 * <p>两个视图都要按这颗按钮两处（`views/JobsView.vue` 的任务列表、`views/HistoryView.vue` 批组头上的
 * 「重跑任务」），逐行同形的只有「发请求 + 认错误码」这一半；**成功文案与刷新各自留在各自的视图里**
 * （`执行完成：` vs `重跑完成：`，刷新的东西也不一样）——所以这里返回一个判别式的结果，
 * 而不是在这里弹消息、发请求之外的副作用一个都不做。</p>
 *
 * <p>三种结果分得开是因为它们的**界面口径不同**（#146）：`JOB_ALREADY_RUNNING` 是「这次没跑」的
 * 提示（`info`，不是告警），别的失败才是告警（`error`）。这个分辨只有一处，两个视图不再各抄一遍。</p>
 */
import { ApiError, api } from '../api/client'
import type { Job } from '../types'

/** 一次「执行任务」的结果：跑成了（带运行那一行）／没跑（任务正在执行）／失败了。 */
export type ExecuteJobResult =
  | { kind: 'ok'; run: Job }
  | { kind: 'running'; message: string }
  | { kind: 'error'; message: string }

/**
 * 执行（或重跑）任务 `jobId`：**不抛**——失败也当一种结果返回，调用方按用例决定说什么、刷什么。
 *
 * <p>成功时返回接口那一行（`{lastRunStatus, lastError}` 由调用方自己措辞），失败时返回接口那句原因
 * （`ApiError.message`，与 `api()` 同一个口径），其余意外也收成 `error` 一种，调用方因此不必写 catch。</p>
 */
export async function executeJob(jobId: number): Promise<ExecuteJobResult> {
  try {
    return { kind: 'ok', run: await api<Job>(`/jobs/${jobId}/execute`, { method: 'POST', body: '{}' }) }
  } catch (e) {
    const err = e as ApiError
    // #146：任务正在执行是「这次没跑」的提示，不是失败告警——两个视图共用这一处判据
    if (err.code === 'JOB_ALREADY_RUNNING') return { kind: 'running', message: err.message }
    return { kind: 'error', message: err instanceof Error ? err.message : String(e) }
  }
}
