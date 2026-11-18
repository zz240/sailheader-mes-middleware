/**
 * 「重跑任务」的口径（#157）：失败恢复只剩这一条路，所以入口挂在**失败的运行**上，不再依赖批分组。
 *
 * 为什么搬： #147 把「整批重发」删掉后，只剩这一颗按钮；它原来长在**发送批**组头上，而发送批要靠
 * 「成员与信封共用同一个批号」才认得出来。批量链如果不用还原档（字段映射表达式 + 拆分），拆分之后
 * 的失败里成员在新批号、信封留在旧批号，两个批号都认不出打包消息，按钮就不出现——唯一的恢复入口
 * 丢了。重跑本来就是任务级动作，挂运行上与批分组解耦。
 *
 * 纯模块：不碰 DOM、不发请求；检查在 `history/rerun-checks.ts`。
 */
import type { JobRun } from '../types'

export const RERUN_LABEL = '重跑任务'
export const RERUN_CONFIRM_TITLE = '重跑任务'
export const RERUN_CONFIRM_BUTTON = '重跑任务'
export const RERUN_CONFIRM_CANCEL = '取消'

/**
 * 「重跑任务」那一档里要的三样：跑哪个任务、按钮写什么、确认框里问什么。
 *
 * 类型名跟着按钮走：#147 起重跑的是**任务**，不是哪一批（旧的整批重发已删除）。
 */
export interface JobRerun {
  /** 要重跑的那个任务（运行行上带 `jobId`，不用再单独喂）。 */
  jobId: number
  /** 按钮上的字（{@link RERUN_LABEL}）。 */
  label: string
  /** 二次确认框里那句话（见 {@link rerunConfirmText}）。 */
  confirm: string
}

/**
 * 这条运行能不能重跑：**失败 + 拿得到任务标识**。
 *
 * 全成了就没什么可重跑的；正在跑的也不给这个入口（#146 的任务串行会拒，但界面不该先给这个错觉）。
 * 运行还没取回来（`null`）时同样不铺。
 */
export function runRerun(run: Pick<JobRun, 'status'> & { jobId?: number | null } | null): JobRerun | null {
  if (!run || run.status !== 'FAILED' || run.jobId == null) return null
  return { jobId: run.jobId, label: RERUN_LABEL, confirm: rerunConfirmText(run.jobId) }
}

/**
 * 二次确认框里那句话：**从未完成的那一批继续**，并且明说**目标端会再收到一次该批请求**。
 *
 * 水位只在一批全部成功之后才推进，所以重跑 = 从未完成的那一批整批重走；代价是目标端会再收到一次
 * 该批请求——与「至少一次交付 + 目标端按业务键去重」这条既有契约一致，但必须让按按钮的人知道，
 * 所以写成一次确认，而不是点一下就发。
 */
export function rerunConfirmText(jobId: number): string {
  return `重跑任务 #${jobId}：任务会从「未完成的那一批」重新开始——重新读、重新打包，`
    + `再把这一批请求发给目标端一次，然后跑完它后面的步骤。`
    + `目标端会再收到一次该批请求：请确认它按业务键去重（至少一次交付）。`
}
