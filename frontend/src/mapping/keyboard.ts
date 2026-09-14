/**
 * 键盘操作（#106）：常用动作不用鼠标也能完成——换转换、选来源、清除当前行，加上下走行。
 *
 * 这里只回答一件事：**这个键在这个状态下要做什么**。按键怎么落到界面（选中哪一行、焦点给谁、
 * 弹什么提示）在组件里，这里不碰 DOM、不碰行模型，所以可以用 `node --experimental-strip-types`
 * 直接检查（见 `checks.ts`）。
 *
 * 键位与票里那张一致（#98 用户故事 37 与原型定下来的那一份）：
 *
 * | 键 | 做什么 |
 * |---|---|
 * | `↑` / `↓` | 在行之间走（换转换面板开着时，在选项之间走） |
 * | `/` | 换转换（打开这一行的转换面板） |
 * | `Enter` | 选来源（进「走清单」那一段）；在面板 / 清单里就是「用这一项」 |
 * | `Backspace` / `Delete` | 清除当前行 |
 * | `Esc` | 收起面板 / 退出选来源这一段 |
 *
 * 焦点在输入框、文本域里时一律不接管（`inTextEntry`）：那时 `/` 就是要打一个斜杠，
 * 手写表达式那一段更是每个键都得留给输入法。
 *
 * **`inTextEntry` 由调用方算，这里只认它给的结果**：有一处例外不由这个模块决定——「目标字段」那个
 * 单行输入框里、补全下拉没开着时，上下键算「不在输入里」（上下键在一个单行输入框里本来就没别的用处，
 * 而加完一行光标正好落在那里）。那条例外要看 DOM，所以留在组件里（`FieldMappingWorkbench.inTextEntryFor`），
 * 这里不假装知道它。
 */

/** 面板的种类（与组件里那几个就地参数面板一一对应，只有这一处定义）。 */
export type PanelKey = 'op' | 'date' | 'const' | 'enum' | 'concat'

/** 按下这个键时的界面状态。 */
export interface KeyContext {
  /**
   * 焦点算不算「在输入里」（算了就一个键都不抢）。
   *
   * 调用方给的答案，不是这个模块的判断——见文件头那条例外（目标字段那个单行输入框 + 补全下拉没开着）。
   */
  inTextEntry: boolean
  /** 当前选中了一行（清除、换转换、选来源都要先有这一行）。 */
  hasSelection: boolean
  /** 「选来源」这一段开着（回车进来的，正在清单里走）。 */
  pickingSource: boolean
  /** 当前开着的参数面板。 */
  panel: PanelKey | null
}

/** 一个键在这一刻要做的动作。 */
export type KeyIntent =
  /** 上下走行。 */
  | { kind: 'move'; delta: -1 | 1 }
  /** `/`：打开这一行的转换面板。 */
  | { kind: 'transform' }
  /** `Enter`：开始给这一行选来源。 */
  | { kind: 'pick-source' }
  /** `Backspace` / `Delete`：清除这一行的取值方式。 */
  | { kind: 'clear' }
  /** `Esc`：退出「选来源」这一段。 */
  | { kind: 'leave' }
  /** `Esc`：收起参数面板。 */
  | { kind: 'close-panel' }
  /** 选来源时上下走清单。 */
  | { kind: 'walk-item'; delta: -1 | 1 }
  /** 选来源时回车：把清单里这一项填进那一行。 */
  | { kind: 'confirm-pick' }
  /** 面板里上下走选项。 */
  | { kind: 'walk-option'; delta: -1 | 1 }
  /** 面板里回车：用这一个选项。 */
  | { kind: 'confirm-option' }

/** 这个面板是不是「一排选项」（转换面板、日期预设）——只有这种面板能用上下键选。 */
export function isPanelChoice(panel: PanelKey | null): boolean {
  return panel === 'op' || panel === 'date'
}

/** 一个键在这一刻要做什么；什么都不做的键返回 `null`。 */
export function keyIntent(key: string, ctx: KeyContext): KeyIntent | null {
  // 焦点在输入里：键盘归输入法，一个键都不抢
  if (ctx.inTextEntry) return null

  // 「选来源」这一段开着时它优先：这一段本来就是回车进来的，别的键在这里都不该插队
  if (ctx.pickingSource) {
    if (key === 'Escape') return { kind: 'leave' }
    if (key === 'Enter') return { kind: 'confirm-pick' }
    if (key === 'ArrowDown') return { kind: 'walk-item', delta: 1 }
    if (key === 'ArrowUp') return { kind: 'walk-item', delta: -1 }
    return null
  }

  if (ctx.panel) {
    if (key === 'Escape') return { kind: 'close-panel' }
    if (isPanelChoice(ctx.panel)) {
      if (key === 'Enter') return { kind: 'confirm-option' }
      if (key === 'ArrowDown') return { kind: 'walk-option', delta: 1 }
      if (key === 'ArrowUp') return { kind: 'walk-option', delta: -1 }
    }
    // 常量 / 枚举 / 拼接面板里是输入框和表格：上下键照旧走行（在输入框里时上面已经让开了）
  }

  if (key === 'ArrowDown') return { kind: 'move', delta: 1 }
  if (key === 'ArrowUp') return { kind: 'move', delta: -1 }
  if (!ctx.hasSelection) return null
  if (key === '/') return { kind: 'transform' }
  if (key === 'Enter') return { kind: 'pick-source' }
  if (key === 'Backspace' || key === 'Delete') return { kind: 'clear' }
  return null
}

/**
 * 在一排东西里走一格：跳过不能用的那一项，走到头就停在原地（不绕回开头——绕回去会让人以为
 * 按键没生效，少按一下反而走错了方向）。
 *
 * 来源清单（走清单）与转换面板（走选项）共用这一份：两处的走法本来就该一样。
 */
export function stepIndex(current: number, delta: -1 | 1, count: number, disabled?: (index: number) => boolean): number {
  if (count <= 0) return 0
  const from = Math.min(Math.max(current, 0), count - 1)
  for (let i = from + delta; i >= 0 && i < count; i += delta) {
    if (!disabled?.(i)) return i
  }
  return from
}

/**
 * 在行之间走一格（上下走行）：跟 `stepIndex` 同一套走法，只多一条——**还没选中任何一行时**
 * 从两头进（往下走是第一行、往上走是最后一行）。
 *
 * 单独一个函数是因为这条「从哪头进」的规矩只在行列表上有（清单与面板总有当前项），
 * 而它正是用户第一次按上下键时期待的那件事。
 */
export function rowStepIndex(current: number, delta: -1 | 1, count: number): number {
  if (count <= 0) return 0
  if (current < 0 || current >= count) return delta > 0 ? 0 : count - 1
  return stepIndex(current, delta, count)
}
