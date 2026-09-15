/**
 * 字段映射编辑器的共享状态（#117）：行、选中、参数面板、收起集合、来源与目标清单，只有这一处。
 *
 * 为什么要有它：左边来源清单、行列表、问题面板与工作台那一段键盘共用同一份编辑状态
 * （`rows` / `selectedId` / `panel` / `collapsedIds` / `clearedRows` …）。靠一层层 props / emit 传下去，
 * 接缝会长在每个组件上（改一处要顺着链子改三处）；这里把「状态 + 挂在状态上的那些操作」放成一处
 * （选中、展开收起集合、参数面板与它的键盘走位、清除 / 撤销、新增行、自动匹配、点一下来源、跳行），
 * 组件只读它、调它。**只在一个组件里用的取词与文案不放这里**（那些跟着组件走）。
 *
 * 它不碰后端：不发请求，也不判对错（#98 D1）。`load()` 拿回来的东西由工作台填进来，对错仍然只在
 * 「校验配置」那个只读接口里判——这个模块里连 `api` 都没引。
 *
 * 状态由工作台 create 之后 provide 下去（一次打开一个实例）：子组件用 `useWorkbenchState()` 取同一份。
 */
import { computed, inject, nextTick, provide, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  appendArrayRow, appendLeafRow, flattenRows, nextEmptyRow, targetCandidatesFor,
  type FlatRow, type SourceField, type TargetField,
} from './editor-model.ts'
import { autoMatchSameName, targetRowsView, type TargetRowsView } from './fast-config.ts'
import { clearRowMapping, hasMapping, markHumanEdited, restoreRowMapping, type MapRow, type RowMapping } from './rows.ts'
import {
  applyTransform, datePatternFor, transformOptions, DATE_PRESETS, type TransformOption,
} from './transforms.ts'
import {
  appendChildRow, appendIndexChildRow, ancestorRowIds, emptyPickResult, fillRowFromPick,
  visibleRows, type PickableItem,
} from './source-picker.ts'
import type { DraftValidation } from './draft.ts'
import { stepIndex, type PanelKey } from './keyboard.ts'

/** 行列表铺出来的一项：树形结构 + 界面要的那几格。 */
export interface DisplayRow extends FlatRow {
  /** 铺出来的每一项的唯一键：分组标题与它上面那一行共用同一个 MapRow，键不能重。 */
  key: string
  /** 这一行的目标字段是清单给的那一个（而不是手输的）。 */
  fromTargets: boolean
  /** 数组行的「数组元素内」分组标题（铺在数组行与它的子行之间）。 */
  arrayHeader: boolean
}

/** 有参数要填的转换 → 它的面板；没有的（直接映射、数字转换、行号）不在这里。 */
const PANEL_KEYS: Partial<Record<string, PanelKey>> = {
  DATE: 'date',
  CONSTANT: 'const',
  ENUM: 'enum',
  CONCAT: 'concat',
}

export interface WorkbenchState {
  // ---------------------------------------------------------------- 数据（工作台 load 时填）
  /** 目标字段的行树（编辑器里唯一的那一份）。 */
  rows: Ref<MapRow[]>
  /** 目标字段清单（写目标动作探测出来的）。 */
  targets: Ref<TargetField[]>
  /** 当前来源步骤的字段清单（探测结果）。 */
  sourceFields: Ref<SourceField[]>
  /** 顶部那个「默认来源」。 */
  defaultStep: Ref<number>
  /** 动作序号 → 动作名（行里的人话要用）。 */
  actionNames: Ref<Record<number, string>>
  /** 上一次「校验配置」的结果；没点过就是 null，界面上什么都不显示。 */
  validation: Ref<DraftValidation | null>

  // ---------------------------------------------------------------- 编辑界面状态
  selectedId: Ref<number | null>
  /** 收起子行的数组行（默认全部展开）。 */
  collapsedIds: Ref<Set<number>>
  /** 清掉的行 → 撤销用的快照（只活在编辑器里，不进规则）。 */
  clearedRows: Ref<Map<number, RowMapping>>
  /** 现在开着的参数面板（种类与它所在的那一行）。 */
  panel: Ref<PanelKey | null>
  panelRowId: Ref<number | null>
  /** 面板里键盘走到第几个选项（换转换、日期预设）。 */
  optionIndex: Ref<number>
  /** 目标字段的搜索词（#105：回车跳到命中的那一行）。 */
  targetQuery: Ref<string>
  /** 这一行的目标字段输入框里用户是不是正在打字（决定补全要不要按查询词筛）。 */
  typingTargetRowId: Ref<number | null>

  // ---------------------------------------------------------------- 派生（键盘那一段与不止一个组件要看）
  displayRows: ComputedRef<DisplayRow[]>
  rowView: ComputedRef<TargetRowsView>
  selectedEntry: ComputedRef<DisplayRow | null>
  panelEntry: ComputedRef<DisplayRow | null>

  // ---------------------------------------------------------------- 行上的操作
  select(row: MapRow): void
  closePanel(): void
  openPanel(entry: DisplayRow, kind: PanelKey): void
  chooseTransform(entry: DisplayRow, option: TransformOption): void
  chooseDatePattern(row: MapRow, pattern: string): void
  walkPanelOption(delta: -1 | 1): void
  confirmPanelOption(): void
  clearRow(entry: DisplayRow): void
  undoClear(row: MapRow): void
  canUndo(row: MapRow): boolean
  clearHint(entry: FlatRow): string
  jumpToRowId(rowId: number | null): void
  focusRow(row: MapRow): void
  addLeafRow(): void
  addArrayRow(): void
  addChild(row: MapRow): void
  addIndexChild(row: MapRow): void
  autoMatch(): void
  pick(item: PickableItem): void
}

/** 建一份工作台共享状态；同一棵组件树上只建一次（工作台 provide，子组件 inject）。 */
export function createWorkbenchState(): WorkbenchState {
  const rows = ref<MapRow[]>([])
  const targets = ref<TargetField[]>([])
  const sourceFields = ref<SourceField[]>([])
  const defaultStep = ref<number>(0)
  const actionNames = ref<Record<number, string>>({})
  const validation = ref<DraftValidation | null>(null)

  const selectedId = ref<number | null>(null)
  const collapsedIds = ref<Set<number>>(new Set())
  const clearedRows = ref<Map<number, RowMapping>>(new Map())
  const panel = ref<PanelKey | null>(null)
  const panelRowId = ref<number | null>(null)
  const optionIndex = ref(0)
  const targetQuery = ref('')
  const typingTargetRowId = ref<number | null>(null)

  // ---------------------------------------------------------------- 铺出来的行

  /**
   * 搜索之后行列表该铺出哪些行（含收起的子行——搜索要能搜到它们）。
   *
   * 这里**只有搜索、没有过滤档**：票里那三档（全部 / 只看未配置 / 只看必填）说的是左边那份来源字段
   * 列表（与开工前确认的口径一致）。右边再加一排同名按钮，两处的「必填」却不是一回事
   * （来源那份是「还没配好的必填字段要用的来源」，行列表这份只能是「清单里标了 required 的行」），
   * 摆在一起只会让人以为它们是一回事。
   */
  const rowView = computed(() => targetRowsView(flattenRows(rows.value), targetQuery.value, 'all', targets.value))

  /** 行在界面上按层级铺开：数组行后面紧跟「数组元素内」标题与它的子行（收起时只剩数组行）。 */
  const displayRows = computed<DisplayRow[]>(() => {
    // 行过滤与搜索（#105）只决定「铺不铺这一行」，行的树形结构照旧：留在列表里的子行前面，
    // 它所属的数组行那把「数组元素内」标题也留着（标题跟着子行走，不单独过滤）。
    const view = rowView.value
    const visible = visibleRows(rows.value, collapsedIds.value)
    const kept = new Set(view.rowIds)
    const out: DisplayRow[] = []
    for (const entry of visible) {
      if (!kept.has(entry.row.id)) continue
      out.push({
        ...entry,
        key: `r${entry.row.id}`,
        // 「手写」是拿这一行的补全清单比出来的，不是拿整份目标字段比：数组元素里的相对路径
        // （`lineNo` 这种）也在清单里，顶层行手输一个同名的路径不该因此摘掉「手写」标记。
        fromTargets: isFromTargets(entry),
        arrayHeader: false,
      })
      // 有子行就补一条分组标题（#101：子行的来源默认按相对路径解析，标题上写明「数组元素内」）。
      // 子行被过滤掉时标题不铺：一条「0 个子字段」的标题只会让人以为行丢了。
      const children = entry.row.children.filter(child => kept.has(child.id))
      if (children.length) {
        out.push({
          row: entry.row, depth: entry.depth, parent: entry.parent,
          key: `h${entry.row.id}`, fromTargets: false, arrayHeader: true,
        })
      }
    }
    return out
  })

  /** 这一行的目标字段是不是清单给的（空行既不是清单给的、也不算手输，所以不标）。 */
  function isFromTargets(entry: FlatRow): boolean {
    const target = entry.row.target.trim()
    return target !== '' && targetCandidatesFor(targets.value, entry).includes(target)
  }

  /** 选中的那一行（清单与参数面板都看它）。 */
  const selectedEntry = computed<DisplayRow | null>(() =>
    displayRows.value.find(e => e.row.id === selectedId.value) ?? null)

  /** 当前面板所在的那一行（行 + 层级 + 父行），脚本里的操作都从这里取。 */
  const panelEntry = computed<DisplayRow | null>(() =>
    displayRows.value.find(e => e.row.id === panelRowId.value) ?? null)

  // ---------------------------------------------------------------- 选中

  function select(row: MapRow) {
    selectedId.value = row.id
    // 换行就把上一行的参数面板收起来，免得面板留在别的行下面看着像它的。
    if (panelRowId.value !== null && panelRowId.value !== row.id) closePanel()
  }

  // ---------------------------------------------------------------- 手工新增行（#102）

  /** 「+ 字段映射」：加一行空的字段映射，加完就选中它并把光标放进目标字段（清单里选一个，或者手输）。 */
  function addLeafRow() {
    focusRow(appendLeafRow(rows.value, defaultStep.value))
  }

  /** 「+ 数组映射」：加一行空的数组映射（目标写数组路径，来源在左边清单里选一个数组）。 */
  function addArrayRow() {
    focusRow(appendArrayRow(rows.value, defaultStep.value))
  }

  /** 「+ 子字段」：往数组行下面加一个子行（目标字段写相对路径）。 */
  function addChild(row: MapRow) {
    appendChildRow(row)
    focusNewChild(row)
  }

  /** 「+ 行号」：往数组行下面加一个行号子行（来源就是行号，起始值在行里改）。 */
  function addIndexChild(row: MapRow) {
    appendIndexChildRow(row)
    focusNewChild(row)
  }

  /** 新加的子行：数组行先展开（否则新行根本不铺出来），再选中它。 */
  function focusNewChild(arrayRow: MapRow) {
    collapsedIds.value = new Set([...collapsedIds.value].filter(id => id !== arrayRow.id))
    const added = arrayRow.children[arrayRow.children.length - 1]
    if (added) focusRow(added)
  }

  /** 新加的行：选中它、滚到看得见、把光标放进目标字段里（补全清单跟着出来）。 */
  function focusRow(row: MapRow) {
    closePanel()
    selectedId.value = row.id
    nextTick(() => {
      const el = document.querySelector<HTMLElement>(`[data-row-id="${row.id}"]`)
      el?.scrollIntoView({ block: 'nearest' })
      el?.querySelector('input')?.focus()
    })
  }

  // ---------------------------------------------------------------- 自动匹配同名（#105）

  /**
   * 一键「自动匹配同名」：把空白的行按同名字段填上。
   *
   * 整件事在 `fast-config.ts` 里做完（纯逻辑，**一个请求都不发**，票里那条验收就是这么保住的）：
   * 只填空行、不覆盖已经配好的行。这里只负责把结果画出来并标上「自动」——从这一刻起，
   * 校验时会给这些行一条提醒确认的提示（`autoSourceNotices`）。
   */
  function autoMatch() {
    const result = autoMatchSameName(rows.value, sourceFields.value, defaultStep.value)
    if (!result.rowIds.length) {
      ElMessage.warning(sourceFields.value.length
        ? '没有空白的行能按同名对上——已经配好的行不动，数组映射行也要你自己选来源数组'
        : '左边还没有来源字段清单，先在上面选一个默认来源')
      return
    }
    // 标上「自动」：这一行的来源不是手点的，校验时要提醒确认（用户一碰这一行标记就掉）。
    const filled = new Set(result.rowIds)
    for (const entry of flattenRows(rows.value)) {
      if (filled.has(entry.row.id)) entry.row.auto = true
    }
    ElMessage.success(`已按同名填上 ${result.rowIds.length} 行（只补空行，来源在界面上标成了「自动」）`)
  }

  // ---------------------------------------------------------------- 清除 / 撤销（#102）

  /** 这一行现在能撤销吗：刚清除过、并且还没重新配（重新配了就不该再退回清除前那一份）。 */
  function canUndo(row: MapRow): boolean {
    return clearedRows.value.has(row.id) && !hasMapping(row)
  }

  /**
   * 「清除」按钮上的说法。
   *
   * 数组行要说清后果：清除之后这一行没有来源数组，而**数组行没有来源数组就整行不发**
   * （`isRowConfigured`／`configFromRows`），子行的映射会跟着一起不进保存——界面上子行还在，
   * 所以这句话得先讲明白（保存时还会再确认一次「只保存配好的」）。
   */
  function clearHint(entry: FlatRow): string {
    if (!hasMapping(entry.row)) return '这一行还没配，没什么可清除的'
    if (entry.row.iterate === 'FOR_EACH') {
      return '清除这一行的来源数组（子行留着；没有来源数组这一行就不发出去，子行的映射也跟着不保存）'
    }
    return '清除这一行的取值方式（目标字段留着，清除后能撤销）'
  }

  function clearRow(entry: DisplayRow) {
    const snapshot = clearRowMapping(entry.row)
    if (!snapshot) return
    clearedRows.value = new Map(clearedRows.value).set(entry.row.id, snapshot)
    // 参数面板开在这一行上时收起来：转换已经回到默认，面板里显示的是上一份参数。
    if (panelRowId.value === entry.row.id) closePanel()
    ElMessage.success(`已清除「${entry.row.target.trim() || '这一行'}」的映射——行里的「撤销」能还原`)
  }

  function undoClear(row: MapRow) {
    const snapshot = clearedRows.value.get(row.id)
    if (!snapshot) return
    restoreRowMapping(row, snapshot)
    const next = new Map(clearedRows.value)
    next.delete(row.id)
    clearedRows.value = next
    select(row)
  }

  // ---------------------------------------------------------------- 就地参数编辑（#100）

  /**
   * 打开某一行的参数面板。
   *
   * 顺手把键盘停在第几个选项上（#106）：`/` 进来时停在它现在用的那一种上，接着按 ↑↓ 就是换。
   */
  function openPanel(entry: DisplayRow, kind: PanelKey) {
    select(entry.row)
    panel.value = kind
    panelRowId.value = entry.row.id
    const presets: readonly string[] = DATE_PRESETS
    const start = kind === 'date'
      ? presets.indexOf(datePatternFor(entry.row))
      : transformOptions(entry.row, entry.depth).findIndex(o => o.value === entry.row.transform)
    optionIndex.value = Math.max(start, 0)
  }

  function closePanel() {
    panel.value = null
    panelRowId.value = null
  }

  /** 选一个转换：参数跟着换；有参数可填的转换顺手把它的面板打开。 */
  function chooseTransform(entry: DisplayRow, option: TransformOption) {
    if (option.disabled) return
    applyTransform(entry.row, option.value)
    // 有参数要填的转换（PANEL_KEYS 里那几个）顺手把它的面板打开；其余选完就收起。
    const next = PANEL_KEYS[option.value]
    if (next) openPanel(entry, next)
    else closePanel()
  }

  function chooseDatePattern(row: MapRow, pattern: string) {
    applyTransform(row, 'DATE', { datePattern: pattern })
  }

  /** 面板里走选项（换转换、日期预设）：跳过置灰的那一项。 */
  function walkPanelOption(delta: -1 | 1) {
    const count = panelOptionCount()
    optionIndex.value = stepIndex(optionIndex.value, delta, count, index => panelOptionDisabled(index))
  }

  /** 面板里回车：用键盘停着的那一个选项。 */
  function confirmPanelOption() {
    const entry = panelEntry.value
    if (!entry) return
    if (panel.value === 'op') {
      const option = transformOptions(entry.row, entry.depth)[optionIndex.value]
      if (option) chooseTransform(entry, option)
      return
    }
    if (panel.value === 'date') {
      const pattern = DATE_PRESETS[optionIndex.value]
      if (pattern) chooseDatePattern(entry.row, pattern)
    }
  }

  /** 当前面板里有几个选项（只有「一排选项」的两个面板有得走）。 */
  function panelOptionCount(): number {
    if (panel.value === 'op') {
      const entry = panelEntry.value
      return entry ? transformOptions(entry.row, entry.depth).length : 0
    }
    if (panel.value === 'date') return DATE_PRESETS.length
    return 0
  }

  function panelOptionDisabled(index: number): boolean {
    const entry = panelEntry.value
    if (!entry || panel.value !== 'op') return false
    return transformOptions(entry.row, entry.depth)[index]?.disabled === true
  }

  // ---------------------------------------------------------------- 点一下来源 / 跳到某一行

  /**
   * 点左边清单里的一个字段：填进当前行，然后自动走到下一个空行。
   *
   * 「填不进去」的三种情况只说形状接不上（数组行要数组、数组要进数组行、相对路径要进子行），
   * 对不对仍然由后端那个只读接口判（#98 D1/D4）。
   */
  function pick(item: PickableItem) {
    const entry = selectedEntry.value
    const result = fillRowFromPick(entry?.row ?? null, item, entry != null && entry.depth > 0)
    if (!result.row || !entry) {
      ElMessage.warning(emptyPickResult(result).message)
      return
    }
    const picked = result.row
    // 手点了一次来源：这一行不再是「自动匹配填上的那一行」（#105 那条提醒只对没碰过的行有用）。
    markHumanEdited(picked)
    selectedId.value = picked.id
    // 数组行选完来源数组后，接着去填这个数组里的子字段（第一个空子行）。
    if (picked.iterate === 'FOR_EACH') {
      const child = picked.children.find(c => c.target.trim() && !c.source.trim()) ?? picked.children[0]
      if (child) {
        selectedId.value = child.id
        collapsedIds.value = new Set([...collapsedIds.value].filter(id => id !== picked.id))
      }
      return
    }
    const next = nextEmptyRow(rows.value, picked)
    if (next && next.id !== picked.id) selectedId.value = next.id
  }

  /** 跳到某一行：先把上面收起的数组行展开（收着的子行根本没铺出来），再选中它、滚到看得见。 */
  function jumpToRowId(rowId: number | null) {
    if (rowId == null) return
    const ancestors = ancestorRowIds(rows.value, rowId)
    if (ancestors.length) {
      collapsedIds.value = new Set([...collapsedIds.value].filter(id => !ancestors.includes(id)))
    }
    closePanel()
    selectedId.value = rowId
    nextTick(() => {
      document.querySelector<HTMLElement>(`[data-row-id="${rowId}"]`)?.scrollIntoView({ block: 'center' })
    })
  }

  return {
    rows, targets, sourceFields, defaultStep, actionNames, validation,
    selectedId, collapsedIds, clearedRows, panel, panelRowId, optionIndex, targetQuery, typingTargetRowId,
    displayRows, rowView, selectedEntry, panelEntry,
    select,
    closePanel, openPanel, chooseTransform, chooseDatePattern,
    walkPanelOption, confirmPanelOption,
    clearRow, undoClear, canUndo, clearHint, jumpToRowId,
    focusRow, addLeafRow, addArrayRow, addChild, addIndexChild, autoMatch, pick,
  }
}

const STATE_KEY = Symbol('field-mapping-workbench-state')

/** 工作台建好状态之后 provide 下去。 */
export function provideWorkbenchState(state: WorkbenchState): void {
  provide(STATE_KEY, state)
}

/** 子组件取同一份状态；不在工作台这棵树上时直接报错（免得悄悄各用各的副本）。 */
export function useWorkbenchState(): WorkbenchState {
  const state = inject<WorkbenchState | null>(STATE_KEY, null)
  if (!state) throw new Error('useWorkbenchState 只能在字段映射编辑器这棵树上用：状态由 FieldMappingWorkbench 提供')
  return state
}
