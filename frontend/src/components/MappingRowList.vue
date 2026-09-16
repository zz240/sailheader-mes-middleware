<script setup lang="ts">
/**
 * 行列表（#117 从 `FieldMappingWorkbench.vue` 拆出来）：行头、四列行渲染、就地参数面板。
 *
 * 「每个目标字段一行、一行一个转换」是这次改版的形态（ADR-0011）：行头加行与搜索，行里
 * 目标字段 / 取值方式 / 问题 / 行操作四列，参数就地展开在行下面（不开弹窗）。
 *
 * 行自己不带编辑状态：行树、选中、参数面板、收起集合都在 `useWorkbenchState()` 那一处（#117），
 * 这里只把状态画出来、把点击接到那些操作上。**行列表专属的取词与文案留在这一份里**（怎么把一行读成
 * 人话、面板标题、三档状态的落点），不往共享状态里塞。它也不发请求——「重新探测目标字段」是个例外
 * （那是后端的事），发一条 `reload-targets` 出去交给工作台。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  flattenRows, rowReferenceFullLabel, rowReferenceLabel, targetCandidatesFor,
  type FlatRow, type SourceField,
} from '../mapping/editor-model.ts'
import { nextMatchRowId } from '../mapping/fast-config.ts'
import { hasMapping, markHumanEdited, type ConcatPart, type MapRow } from '../mapping/rows.ts'
import { emptyRowLabel, fieldReferenceLabel } from '../mapping/labels.ts'
import {
  datePatternFor, normalizeDatePattern, transformLabelFor, transformLabelOf, transformName,
  transformOptions, TRANSFORM_HINTS,
} from '../mapping/transforms.ts'
import { ELEMENT_GROUP_TITLE, toggledRowIds } from '../mapping/source-picker.ts'
import { applyPartKind, canUsePartKind, partEmpty, partKindLabel, partKindOf, partLabelSpec, PART_KINDS } from '../mapping/parts.ts'
import { rowIssueState, type LocatedIssue } from '../mapping/draft.ts'
import type { PanelKey } from '../mapping/keyboard.ts'
import { useWorkbenchState } from '../mapping/useWorkbenchState.ts'
import MappingDateParams from './MappingDateParams.vue'
import MappingEnumParams from './MappingEnumParams.vue'
import MappingIndexParams from './MappingIndexParams.vue'

const props = defineProps<{
  /** 这一批目标是哪个动作的（写目标那一句说明）。 */
  targetTip: string
  /** 后端真跑过一次校验没有（那个三档状态标签只认它）。 */
  hasValidation: boolean
  /** 每一行的问题（键是行 id）：行右侧那三种状态用它算。 */
  rowIssues: Map<number, LocatedIssue[]>
}>()

const emit = defineEmits<{
  /** 点了「重新探测目标字段」：那是后端的事（工作台去发请求）。 */
  (e: 'reload-targets'): void
}>()

const {
  rows, targets, sourceFields, defaultStep, actionNames, selectedId, collapsedIds, panel, panelRowId,
  optionIndex, targetQuery, typingTargetRowId, displayRows, rowView, panelEntry,
  select, closePanel, openPanel, chooseTransform, chooseDatePattern,
  clearRow, undoClear, canUndo, clearHint, jumpToRowId,
  addLeafRow, addArrayRow, addChild, addIndexChild, autoMatch,
} = useWorkbenchState()

/** 面板标题（就地参数面板那一条）。 */
const PANEL_TITLES: Record<PanelKey, string> = {
  op: '这一行的值怎么取',
  date: '日期格式化',
  const: '常量',
  enum: '枚举映射',
  concat: '拼接',
}

const panelTitle = computed(() => (panel.value ? PANEL_TITLES[panel.value] : ''))

/** 转换下拉里每一项的一句说明（与 `transforms.ts` 里那份共用一处定义）。 */
function transformHint(kind: string): string {
  return TRANSFORM_HINTS[kind] ?? ''
}

/** 行上那些人话：这一行的来源读成「动作 1 · 读 BOM · createdTime」这种。 */
function rowLabel(entry: FlatRow, isChild: boolean): string {
  return rowReferenceLabel(entry.row, actionNames.value, isChild)
}

/** 一行当前来源的完整说法（悬停提示）。子行自己不存 sourceStep，用组节点那个。 */
function rowFullLabel(entry: FlatRow): string {
  return rowReferenceFullLabel(entry.row, actionNames.value, entry.parent?.sourceStep ?? null)
}

/** 把一个来源路径按这一行的上下文显示成人话（数组子行里就是相对路径本身）。 */
function fieldLabelOf(entry: FlatRow, path: string): string {
  const step = entry.row.sourceStep ?? entry.parent?.sourceStep ?? defaultStep.value
  return fieldReferenceLabel({ step, path }, actionNames.value)
}

/** 收起 / 展开一个数组行；「展开全部 / 收起全部」一次处理所有数组行。 */
function toggleCollapse(row: MapRow) {
  collapsedIds.value = toggledRowIds(rows.value, collapsedIds.value, row.id)
}

function isCollapsed(row: MapRow): boolean {
  return collapsedIds.value.has(row.id)
}

/** 所有带子行的数组行都收起了吗（决定按钮上写「展开全部」还是「收起全部」）。 */
const allCollapsed = computed(() => {
  const arrays = flattenRows(rows.value).filter(entry => entry.row.children.length)
  return arrays.length > 0 && arrays.every(entry => collapsedIds.value.has(entry.row.id))
})

function toggleAllArrays() {
  collapsedIds.value = allCollapsed.value ? new Set() : toggledRowIds(rows.value, new Set())
}

/** 搜索框回车：跳到下一个命中的行（一路回车就是在命中项之间走一圈）。 */
function jumpToNextMatch() {
  const rowId = nextMatchRowId(rowView.value, selectedId.value)
  if (rowId == null) {
    ElMessage.warning(targetQuery.value.trim() ? '没有目标字段跟这个词对得上' : '先输一个要搜的目标字段')
    return
  }
  jumpToRowId(rowId)
}

/** 「有错 / 有警告 / 没问题」——#103 要求界面上分得开这三种。 */
const STATE_LABELS: Record<'error' | 'warning' | 'ok', string> = {
  error: '有错',
  warning: '有警告',
  ok: '没问题',
}

// ---------------------------------------------------------------- 行头（搜索、加行、收展）

/** 搜索框右边那句：命中几行（含数组内几行）。 */
const matchNote = computed(() => {
  const view = rowView.value
  if (!targetQuery.value.trim() || !view.count) return ''
  return `${view.count} 行命中${view.inArrayCount ? `（含数组内 ${view.inArrayCount} 行）` : ''}`
})

/** 行列表一项都不剩时说清为什么。 */
const emptyRowsText = computed(() => (targetQuery.value.trim()
  ? `没有目标字段跟「${targetQuery.value.trim()}」对得上`
  : '还没有任何目标字段'))

// ---------------------------------------------------------------- 目标字段的补全（#102 / #106）

/** 目标字段的补全候选：清单里跟这一行形状对得上的字段（手输的路径不走这份清单）。 */
function targetSuggestions(entry: FlatRow, query: string): Record<string, any>[] {
  const typed = query.trim()
  // 正在这一行里打字 → 按打进去的词筛；只是聚焦（没打字）→ 把清单整个给出来。
  //
  // 后半条是 #102 留下的：聚焦时 el-autocomplete 拿这一行现有的值当查询词（`handleFocus` 里就是那么写的），
  // 照它筛的话，点一个已经填好的目标字段只会看到它自己——想从清单里换一个反而换不了（真机实测踩到过）。
  // 前半条是 #106 补的：手输一个清单里没有的路径时要真筛，筛完没有候选，下拉自己就收了；
  // 不筛的话那份下拉会一直铺着一整列不相干的字段，键盘接着「选来源」这一步就走不下去
  // （回车那时被当成「下拉开着」而让给补全，真机实测踩到过）。
  const q = typingTargetRowId.value === entry.row.id && typed ? typed.toLowerCase() : ''
  return targetCandidatesFor(targets.value, entry)
    .filter(path => !q || path.toLowerCase().includes(q))
    .map(path => ({ value: path }))
}

function onTargetInput(entry: FlatRow) {
  typingTargetRowId.value = entry.row.id
}

/** 补全要的是「给一个查询词、回一份候选」的函数，而清单按行给，所以每行现取一份。 */
function suggestFor(entry: FlatRow) {
  return (query: string, cb: (items: Record<string, any>[]) => void) => cb(targetSuggestions(entry, query))
}

/**
 * 行里那些控件的点击不要冒泡到行本身。
 *
 * 行的点击是「选中这一行」，而一行里有输入框、有参数面板、有「+ 子字段」——不拦住的话，
 * 点「+ 子字段」会在加完子行之后又被行的处理函数改成「选中那一行」（真机实测：加出来的子行
 * 明明选中了，一点就跳回数组行）。
 */
function stop(e: Event) {
  e.stopPropagation()
}

// ---------------------------------------------------------------- 行里那些人话（取词）

/** 点来源格子：这一行就是「要填哪一行」，用户在左边清单点一个字段即可。 */
function pickSourceFor(entry: FlatRow) {
  select(entry.row)
}

/** 操作符那一栏的悬停提示：一句话说清这一行在做什么。 */
function operationHint(entry: FlatRow): string {
  const kind = entry.row.transform
  if (kind === 'STRING') return '点一下换一种转换'
  return `${transformLabelOf(entry.row, () => rowLabel(entry, entry.depth > 0))}——点一下换一种转换`
}

function enumCountText(row: MapRow): string {
  const count = Object.keys(row.enumMap ?? {}).length
  return count ? `${count} 条` : '空表'
}

/**
 * 拼接片段的显示（#112）：片段与行级转换共用同一份人话（`transformLabelOf`）。
 *
 * 常量片段显示值本身；取值的片段走来源那一套（动作名、相对路径）；挂了转换的片段显示成
 * 「日期格式化 · yyyyMMdd（动作 1 · 读 BOM · createdTime）」这种——与行上读到的是同一句话。
 */
function partLabel(entry: FlatRow, i: number): string {
  const part = entry.row.parts[i]
  if (!part || partEmpty(part)) return '（空片段）'
  return transformLabelFor(partLabelSpec(part),
    () => (entry.depth > 0 ? part.value : fieldLabelOf(entry, part.value)))
}

/** 片段小块的样式：常量一种、直接取值一种、挂了转换的一种（一眼看出哪几段被加工过）。 */
function partChipClass(part: ConcatPart): string {
  const kind = partKindOf(part)
  if (kind === 'CONSTANT') return 'chip-const'
  return kind === 'STRING' ? 'chip-src' : 'chip-op'
}

/** 片段小块的悬停提示：点一下做什么、这一段现在是怎么取的。 */
function partChipHint(part: ConcatPart): string {
  const kind = partKindOf(part)
  if (kind === 'CONSTANT') return '常量片段——点一下改这个片段的取值方式'
  if (kind === 'STRING') return '直接取值——点一下换这个片段的转换'
  return `${partKindLabel(kind)}片段——点一下改这个片段的参数`
}

function isChild(entry: FlatRow): boolean {
  return entry.depth > 0
}

/** 这一行的问题原文，按顺序全部列出来（#113：不再只显示第一条、其余藏进悬停）。 */
function issueMessagesOf(rowId: number): string[] {
  return (props.rowIssues.get(rowId) ?? []).map(issue => issue.message)
}

function issueText(rowId: number): string {
  return issueMessagesOf(rowId).join('\n')
}

function rowStateOf(rowId: number): 'error' | 'warning' | 'ok' {
  // 「有错 / 有警告 / 没问题」这条判据只有 `draft.ts` 一处（这里不复制第二份）
  return rowIssueState(props.rowIssues.get(rowId))
}

function stateLabel(rowId: number): string {
  return STATE_LABELS[rowStateOf(rowId)]
}

// ---------------------------------------------------------------- 参数面板里那几个 handler
//
// 面板本身（种类、第几个选项、开着的是哪一行）在 `useWorkbenchState.ts`：键盘那一段也要用它。
// 这里只剩「用户在这一格里改了什么」——改的永远是行上的那一份，组件自己不存副本。

/**
 * 手输日期格式串（#111）：边打边落进这一行，摘要跟着变。
 *
 * 打字中途允许是空串（否则「全选删掉再打」会被自己填回去），空串在界面上显示成默认格式；
 * 真正归一在失焦/回车那一下（{@link onDatePatternCommit}）。
 */
function onDatePatternInput(row: MapRow, value: string) {
  row.extra = value
  markHumanEdited(row)
}

/** 手输框提交（失焦 / 回车）：清空的话回落到默认格式，不把空串留给编译器（#111 验收五）。 */
function onDatePatternCommit(row: MapRow) {
  row.extra = normalizeDatePattern(row.extra)
}

/** 行号起始值改了：写进行上（「空 → 1」那条规矩在 `MappingIndexParams` 里，只写一处）。 */
function onStartAtChange(row: MapRow, value: number) {
  row.startAt = value
  markHumanEdited(row)
}

/**
 * 一个来源路径在这份清单里的那条样例（枚举表「按样例补全」用）。
 *
 * 数组子行里写的是相对路径，要去元素字段里找（`items[0].<路径>`）。
 */
function sourceFieldOf(entry: FlatRow | null, path: string): SourceField | undefined {
  const source = (path ?? '').trim()
  if (!entry || !source) return undefined
  if (entry.depth > 0) {
    const array = entry.parent?.source.trim() ?? ''
    return sourceFields.value.find(f => f.name === `${array}[0].${source}`)
  }
  return sourceFields.value.find(f => f.name === source)
}

/** 面板这一行来源字段的样例值。 */
const panelSourceField = computed<SourceField | undefined>(() =>
  sourceFieldOf(panelEntry.value, panelEntry.value?.row.source ?? ''))

/** 候选来源字段：数组子行用相对路径，其余用请到的清单。 */
function selectableFields(entry: FlatRow): SourceField[] {
  const fields = sourceFields.value
  if (!isChild(entry)) return fields
  // 子行的来源是相对当前元素的路径，只能从组来源数组的元素字段里挑。
  const array = entry.parent?.source ?? ''
  if (!array) return fields
  const prefix = `${array.replace(/\[0\]$/, '')}[0].`
  const relative = fields
    .filter(f => f.name.startsWith(prefix))
    .map(f => ({ ...f, name: f.name.slice(prefix.length) }))
  return relative.length ? relative : fields.filter(f => !f.name.includes('.'))
}

// ---- 拼接片段（#112：每个片段挂一个片段转换）

function addPart(row: MapRow, inArray: boolean) {
  row.parts = [...row.parts, { type: inArray ? 'path' : 'const', value: '' } as ConcatPart]
  markHumanEdited(row)
}

function removePart(row: MapRow, index: number) {
  row.parts = row.parts.filter((_, i) => i !== index)
  markHumanEdited(row)
}

/** 换这个片段的转换：参数跟着换，旧的清掉（一个片段只有一个转换）。 */
function choosePartKind(entry: FlatRow, part: ConcatPart, kind: string) {
  applyPartKind(part, kind as Parameters<typeof applyPartKind>[1])
  markHumanEdited(entry.row)
}

function choosePartDatePattern(row: MapRow, part: ConcatPart, pattern: string) {
  applyPartKind(part, 'DATE', { datePattern: pattern })
  markHumanEdited(row)
}

/** 手输格式串：边打边落在这个片段上（清空那一格等失焦/回车再归一）。 */
function onPartDatePattern(row: MapRow, part: ConcatPart, value: string) {
  part.datePattern = value
  markHumanEdited(row)
}

function onPartDateCommit(row: MapRow, part: ConcatPart) {
  part.datePattern = normalizeDatePattern(part.datePattern ?? '')
}

function onPartEnumMap(row: MapRow, part: ConcatPart, map: Record<string, any>) {
  part.enumMap = map
  markHumanEdited(row)
}

/** 片段的起始值改了：写在片段上（与行级同一个输入框组件、同一条默认值规矩）。 */
function onPartStartAt(row: MapRow, part: ConcatPart, value: number) {
  part.startAt = value
  markHumanEdited(row)
}

/** 枚举映射表：组件里改的每一格立刻写回行上的 enumMap（行是唯一副本，不另存一份编辑状态）。 */
function onEnumMapChange(row: MapRow, map: Record<string, any>) {
  row.enumMap = map
  markHumanEdited(row)
}
</script>

<template>
  <section class="wb-rows">
    <div class="wb-rows-head">
      <div class="wb-panel-title">目标字段</div>
      <div class="wb-panel-tip">{{ props.targetTip }}</div>
      <span class="wb-spacer" />
      <!-- 按目标字段搜索并跳到那一行（#105）：回车在命中的行之间走一圈 -->
      <el-input
        v-model="targetQuery"
        size="small"
        clearable
        class="wb-target-search"
        placeholder="搜目标字段，回车跳过去"
        @keydown.enter.prevent="jumpToNextMatch"
      />
      <span v-if="targetQuery.trim()" class="wb-match-note">{{ matchNote }}</span>
      <el-tooltip content="加一行字段映射：目标字段从清单里选一个，或者直接手输一个路径" placement="top">
        <el-button size="small" type="primary" plain @click="addLeafRow">+ 字段映射</el-button>
      </el-tooltip>
      <el-tooltip content="加一行数组映射：目标写数组路径，来源在左边清单里选一个数组" placement="top">
        <el-button size="small" @click="addArrayRow">+ 数组映射</el-button>
      </el-tooltip>
      <el-tooltip placement="top">
        <template #content>
          按同名字段把空白的行填上（只在浏览器里比对，不发请求）。已经配好的行不动，数组映射行也留给
          你自己选来源数组。填上的行会标成「自动」，校验时会提醒你确认一遍。
        </template>
        <el-button size="small" @click="autoMatch">自动匹配同名</el-button>
      </el-tooltip>
      <el-button size="small" text @click="toggleAllArrays">{{ allCollapsed ? '展开全部数组' : '收起全部数组' }}</el-button>
      <el-button size="small" text type="primary" @click="emit('reload-targets')">重新探测目标字段</el-button>
    </div>

    <!-- 校验结果（#103）：点「校验配置」才有；每条问题点一下就跳到对应的行。
         那一块由工作台通过这个插槽放进来——它读的是草稿那层的结果，行列表不替它转手 -->
    <slot name="issues" />

    <div v-if="!rows.length" class="wb-empty">
      还没有目标字段清单——选一个写目标动作，或者先点「重新探测目标字段」。清单不全也没关系：
      用「+ 字段映射」「+ 数组映射」自己加行，目标字段直接手输。
    </div>

    <div v-else-if="!displayRows.length" class="wb-empty">{{ emptyRowsText }}</div>

    <div v-else class="wb-row-list">
      <!-- 每一项只有在「数组行的分组标题」与「普通行」之间二选一。
           两者必须挂在同一个 v-if/v-else 上并共用 key：分成两个兄弟节点时，
           Vue 会按位置复用 DOM，把数组行复制成两份（真机实测踩到过）。 -->
      <template v-for="entry in displayRows" :key="entry.key">
        <!-- 数组行的「数组元素内」分组标题：展开 / 收起子行就在这里（#101 验收） -->
        <div v-if="entry.arrayHeader" class="wb-group">
          <button
            class="wb-chip chip-toggle"
            :title="isCollapsed(entry.row) ? '展开这一行的子字段' : '收起这一行的子字段'"
            @click.stop="toggleCollapse(entry.row)"
          >
            {{ isCollapsed(entry.row) ? '▸ 展开' : '▾ 收起' }}
          </button>
          <span class="wb-group-title">{{ ELEMENT_GROUP_TITLE }}</span>
          <span class="wb-group-note">{{ entry.row.target }} · {{ entry.row.children.length }} 个子字段</span>
        </div>

        <div
          v-else
          class="wb-row"
          :class="{ selected: entry.row.id === selectedId, child: entry.depth > 0 }"
          :data-row-id="entry.row.id"
          @click="select(entry.row)"
        >
          <div class="wb-col-target">
            <!-- 目标字段：清单里选一个，或者直接手输一个清单里没有的路径（#102）。
                 清单按这一行的形状给：普通行给标量字段、数组行给数组字段、子行给元素内的相对路径。
                 `@keydown.escape.stop`：补全下拉关着时按 Esc 只该什么都不做——不拦的话它会冒到
                 抽屉上（下拉开着时 el-autocomplete 自己就把 Esc 收下了，这里拦不到它）。
                 抽屉从 #106 起不再用 Esc 关闭：Esc 归「收起参数面板 / 退出选来源」，免得误按
                 一下就连同没保存的草稿一起关掉。 -->
            <el-autocomplete
              v-model="entry.row.target"
              size="small"
              class="wb-target-input"
              :debounce="0"
              :fetch-suggestions="suggestFor(entry)"
              :placeholder="entry.depth > 0 ? '相对路径，例如 materialCode' : '目标字段路径（选一个或手输）'"
              @focus="select(entry.row)"
              @input="onTargetInput(entry)"
              @click="stop"
              @keydown.escape.stop
            >
              <template v-if="!entry.fromTargets && entry.row.target.trim()" #append>
                <span class="wb-chip" title="这个目标字段不在目标清单里，是手输的">手写</span>
              </template>
            </el-autocomplete>
          </div>

          <!-- 这一行的值怎么取：一句中文，点一下就地改，不开弹窗 -->
          <div class="wb-col-value" :title="rowFullLabel(entry)" @click="stop">
            <template v-if="entry.row.iterate === 'FOR_EACH'">
              <button class="wb-chip chip-src" @click.stop="pickSourceFor(entry)">
                {{ entry.row.source ? rowLabel(entry, false) : '还没选来源数组' }}
              </button>
              <span class="wb-child-hint">{{ entry.row.children.length }} 个子字段在数组元素内取值</span>
            </template>
            <template v-else>
              <!-- 这一行的来源是「自动匹配同名」填上的（#105）：标出来，校验时还会提醒确认 -->
              <el-tooltip v-if="entry.row.auto" placement="top">
                <template #content>
                  这一行的来源是「自动匹配同名」填上的，不是你手点的。校验时会提醒你确认一遍；
                  手点一次来源、换个来源步骤或者清一次行，这个标记就没了。
                </template>
                <span class="wb-chip chip-auto">自动</span>
              </el-tooltip>
              <button
                v-if="entry.row.transform !== 'INDEX' && entry.row.transform !== 'CONCAT' && entry.row.transform !== 'CONSTANT'"
                class="wb-chip chip-src"
                :class="{ empty: !entry.row.source }"
                title="点一下：左边清单里的字段填进这一行"
                @click.stop="pickSourceFor(entry)"
              >
                {{ entry.row.source ? rowLabel(entry, entry.depth > 0) : emptyRowLabel() }}
              </button>
              <span v-else-if="entry.row.transform === 'CONCAT'" class="wb-paren">拼接</span>
              <span v-else-if="entry.row.transform === 'CONSTANT'" class="wb-paren">常量</span>

              <!-- 转换跟在来源后面。直接映射就是来源本身，这里只留一个换转换的入口 -->
              <template v-if="entry.row.transform === 'STRING'">
                <button class="wb-chip chip-op-empty" title="点一下换一种转换" @click.stop="openPanel(entry, 'op')">
                  换转换
                </button>
              </template>
              <template v-else>
                <span class="wb-paren">·</span>
                <button class="wb-chip chip-op" :title="operationHint(entry)" @click.stop="openPanel(entry, 'op')">
                  {{ transformName(entry.row.transform) }}
                  <template v-if="entry.row.transform === 'DATE'">
                    · {{ datePatternFor(entry.row) }}
                  </template>
                  <template v-if="entry.row.transform === 'ENUM'">
                    · {{ enumCountText(entry.row) }}
                  </template>
                </button>
              </template>

              <!-- 拼接（#112）：每个片段一个小块，挂着转换的那几段按转换高亮；点一下就地改 -->
              <template v-if="entry.row.transform === 'CONCAT'">
                <span class="wb-paren">（</span>
                <template v-for="(part, i) in entry.row.parts" :key="i">
                  <span v-if="i" class="wb-paren"> + </span>
                  <button
                    class="wb-chip chip-part"
                    :class="partChipClass(part)"
                    :title="partChipHint(part)"
                    @click.stop="openPanel(entry, 'concat')"
                  >
                    {{ partLabel(entry, i) }}
                  </button>
                </template>
                <span class="wb-paren">）</span>
              </template>

              <!-- 常量：值直接显示在这一行里 -->
              <template v-if="entry.row.transform === 'CONSTANT'">
                <span class="wb-paren">「</span>
                <button class="wb-chip chip-const" @click.stop="openPanel(entry, 'const')">
                  {{ entry.row.extra.trim() || '点这里填一个值' }}
                </button>
                <span class="wb-paren">」</span>
              </template>

              <!-- 行号：起始值直接显示在这一行里（与片段级的那个输入框是同一个组件） -->
              <template v-if="entry.row.transform === 'INDEX'">
                <span class="wb-paren">从</span>
                <MappingIndexParams
                  :start-at="entry.row.startAt"
                  @update:start-at="onStartAtChange(entry.row, $event)"
                />
                <span class="wb-paren">起</span>
              </template>
            </template>

            <!-- 数组行：往下面加子行（子行的目标字段写相对路径） -->
            <template v-if="entry.row.iterate === 'FOR_EACH'">
              <span class="wb-paren">·</span>
              <el-button size="small" text type="primary" @click.stop="addChild(entry.row)">+ 子字段</el-button>
              <el-tooltip content="加一个行号子行：来源就是行号，起始值在行里改" placement="top">
                <el-button size="small" text type="primary" @click.stop="addIndexChild(entry.row)">+ 行号</el-button>
              </el-tooltip>
            </template>
          </div>

          <!-- 这一行有没有问题（#103）：自己的那一列，窄屏时排在「映射」下面（#106：依次排列）。
               一行上的**全部**问题都列在这里（#113 验收三：不再只显示第一条、其余藏进悬停）。
               那个「有错 / 有警告 / 没问题」的标签**只在后端真跑过一次校验之后**才给（`hasValidation`）：
               清掉一行之后没点过校验时，这一行上也有一条提醒（清掉的行，编辑器自己说的），但那时候
               谁都没判过这份草稿，不该给一个绿的「没问题」（#113 走查）。 -->
          <div
            v-if="props.hasValidation || issueMessagesOf(entry.row.id).length"
            class="wb-col-issues"
            :title="issueText(entry.row.id)"
          >
            <span v-if="props.hasValidation" class="wb-state" :class="rowStateOf(entry.row.id)">
              {{ stateLabel(entry.row.id) }}
            </span>
            <span
              v-for="(message, i) in issueMessagesOf(entry.row.id)"
              :key="i"
              class="wb-issue-inline"
            >{{ message }}</span>
          </div>

          <!-- 行操作：清除这一行的取值方式（清除后能撤销，见右边的「撤销」） -->
          <span class="wb-col-actions" @click="stop">
            <el-tooltip placement="top">
              <template #content>{{ clearHint(entry) }}</template>
              <button
                class="wb-chip chip-clear"
                :disabled="!hasMapping(entry.row)"
                @click.stop="clearRow(entry)"
              >清除</button>
            </el-tooltip>
            <el-tooltip v-if="canUndo(entry.row)" content="撤销刚才那次清除，把这一行配回来" placement="top">
              <button class="wb-chip chip-undo" @click.stop="undoClear(entry.row)">撤销</button>
            </el-tooltip>
          </span>
        </div>

        <!-- 就地参数编辑：就占这一行的下面一行，不开弹窗 -->
        <div v-if="panelRowId === entry.row.id" class="wb-panel" @click="stop">
          <div class="wb-panel-head">
            <span class="wb-panel-title">{{ panelTitle }}</span>
            <span class="wb-panel-target">{{ entry.row.target || '（还没填目标字段）' }}</span>
            <span class="wb-spacer" />
            <el-button size="small" text @click="closePanel">关闭</el-button>
          </div>

          <!-- 换转换：七种全在，不能用的置灰并说明原因。键盘：↑↓ 走，回车用这一个（#106） -->
          <template v-if="panel === 'op'">
            <div class="wb-ops">
              <button
                v-for="(o, i) in transformOptions(entry.row, entry.depth)"
                :key="o.value"
                class="wb-opbtn"
                :class="{ current: o.value === entry.row.transform, disabled: o.disabled, 'kb-current': i === optionIndex }"
                :disabled="o.disabled"
                @click="chooseTransform(entry, o)"
              >
                <span>{{ o.label }}</span>
                <small>{{ o.disabled ? o.reason : transformHint(o.value) }}</small>
              </button>
            </div>
          </template>

          <!-- 日期格式化：预设 + 手输格式串（#111）。键盘 ↑↓ 走预设、回车用这一个。
               这一份与片段级的日期参数是同一个组件（#112 验收二） -->
          <template v-else-if="panel === 'date'">
            <MappingDateParams
              :pattern="entry.row.extra"
              :option-index="optionIndex"
              @update:pattern="onDatePatternInput(entry.row, $event)"
              @commit="onDatePatternCommit(entry.row)"
              @pick="chooseDatePattern(entry.row, $event)"
            />
          </template>

          <!-- 常量：当场输入 -->
          <template v-else-if="panel === 'const'">
            <div class="wb-panel-row">
              <el-input
                :model-value="entry.row.extra"
                size="small"
                class="wb-grow"
                placeholder="这个字段固定写死的值，例如 固定值"
                @update:model-value="entry.row.extra = $event"
              />
              <span class="wb-panel-note">常量不取来源，填什么目标系统就收到什么。</span>
            </div>
          </template>

          <!-- 枚举映射：表格加行、删行、改键改值（与片段级的枚举参数是同一个组件） -->
          <template v-else-if="panel === 'enum'">
            <MappingEnumParams
              :map="entry.row.enumMap"
              :sample-field="panelSourceField"
              @update:map="onEnumMapChange(entry.row, $event)"
            />
          </template>

          <!-- 拼接（#112）：每个片段挂一个片段转换，参数就在片段这一行里编辑。
               「一个片段只能挂一个转换」——这里没有第二层的入口 -->
          <template v-else-if="panel === 'concat'">
            <div v-for="(part, i) in entry.row.parts" :key="i" class="wb-part">
              <div class="wb-panel-row">
                <el-select
                  :model-value="partKindOf(part)"
                  size="small"
                  class="wb-part-kind"
                  title="这个片段是六种里的哪一种；一个片段只能挂一个转换"
                  @update:model-value="choosePartKind(entry, part, $event)"
                >
                  <el-option
                    v-for="kind in PART_KINDS"
                    :key="kind"
                    :value="kind"
                    :label="partKindLabel(kind)"
                    :disabled="!canUsePartKind(kind, entry.depth)"
                  />
                </el-select>
                <el-input
                  v-if="partKindOf(part) === 'CONSTANT'"
                  v-model="part.value"
                  size="small"
                  class="wb-grow"
                  placeholder="常量片段，例如 BOM-"
                />
                <el-select
                  v-else-if="partKindOf(part) !== 'INDEX'"
                  v-model="part.value"
                  size="small"
                  filterable
                  allow-create
                  default-first-option
                  class="wb-grow"
                  placeholder="选一个来源字段（也可以手输路径）"
                >
                  <el-option v-for="f in selectableFields(entry)" :key="f.name" :value="f.name" :label="f.name" />
                </el-select>
                <span v-else class="wb-panel-note">源就是行号本身，不用选字段</span>
                <el-button size="small" text type="danger" @click="removePart(entry.row, i)">删</el-button>
              </div>

              <!-- 参数跟着转换就地展开：日期 / 枚举这两份与行级转换是同一套组件 -->
              <div v-if="partKindOf(part) === 'DATE'" class="wb-part-params">
                <MappingDateParams
                  :pattern="part.datePattern ?? ''"
                  @update:pattern="onPartDatePattern(entry.row, part, $event)"
                  @commit="onPartDateCommit(entry.row, part)"
                  @pick="choosePartDatePattern(entry.row, part, $event)"
                />
              </div>
              <div v-else-if="partKindOf(part) === 'ENUM'" class="wb-part-params">
                <MappingEnumParams
                  :map="part.enumMap"
                  :sample-field="sourceFieldOf(entry, part.value)"
                  @update:map="onPartEnumMap(entry.row, part, $event)"
                />
              </div>
              <div v-else-if="partKindOf(part) === 'INDEX'" class="wb-part-params">
                <div class="wb-panel-row">
                  <span class="wb-panel-note">起始值</span>
                  <MappingIndexParams
                    :start-at="part.startAt"
                    @update:start-at="onPartStartAt(entry.row, part, $event)"
                  />
                  <span class="wb-panel-note">数组里第 1 个元素拼出来的就是起始值本身</span>
                </div>
              </div>
            </div>
            <div class="wb-panel-row">
              <el-button size="small" @click="addPart(entry.row, entry.depth > 0)">加片段</el-button>
            </div>
            <div class="wb-panel-note">
              片段按顺序拼起来；来源片段在数组映射的子行里用相对路径。
              <template v-if="entry.depth === 0">
                行号片段只能在数组映射的子行里用——这一行在顶层，所以「行号」那一项是置灰的。
              </template>
            </div>
          </template>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.wb-rows { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 10px 16px; min-height: 0; }
/* 行头在窄屏上换行，而不是把搜索框和按钮挤成一团（#106） */
.wb-rows-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wb-rows-head .wb-panel-tip { margin: 0; }
.wb-row-list { overflow: auto; flex: 1; margin-top: 6px; }
.wb-row {
  display: flex; align-items: center; gap: 10px;
  padding: 5px 8px; border-radius: 4px; border: 1px solid transparent;
}
.wb-row:hover { background: #f9fafb; }
.wb-row.selected { border-color: #409eff; background: #ecf5ff; }
.wb-row.child { margin-left: 24px; background: #fcfcfd; }
.wb-row.child.selected { background: #ecf5ff; }
.wb-col-target { width: 280px; flex-shrink: 0; }
.wb-target-input { width: 100%; }
.wb-col-value { flex: 1; min-width: 0; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.wb-target-search { width: 200px; flex-shrink: 0; }
.wb-match-note { font-size: 11px; color: #6b7280; white-space: nowrap; }
.wb-child-hint { font-size: 11px; color: #9ca3af; }

/* 数组行的「数组元素内」分组标题：展开 / 收起子行就在这里 */
.wb-group { display: flex; align-items: center; gap: 8px; margin: 4px 0 2px 24px; }
.wb-group-title { font-size: 11px; color: #6b7280; font-weight: 600; }
.wb-group-note { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }

/* 一行里能点的那些小块：基元（`.wb-chip` 与它的悬停）在 styles/workbench-panel.css，
   来源清单也要用同一份；这里只留这一行里那几种小块。 */
.wb-chip.chip-src { font-family: Consolas, monospace; }
.wb-chip.chip-src.empty { color: #9ca3af; border-style: dashed; }
/* 「还没选来源」这类空块至少要占住一块地方：塌成一小撮时，后面的「+ 子字段」会挪到行的正中间，
   用户点行（想选中它）就会点到加子行上——真机实测踩到过。 */
.wb-col-value > .wb-chip.chip-src { min-width: 120px; text-align: left; }
.wb-chip.chip-op { border-color: #c7d2fe; background: #eef2ff; color: #3730a3; }
.wb-chip.chip-op-empty {
  border-style: dashed; color: #9ca3af; font-size: 11px; padding: 0 5px;
}
.wb-chip.chip-op-empty:hover { color: #409eff; border-color: #409eff; }
.wb-chip.chip-const { border-color: #fde68a; background: #fffbeb; color: #92400e; font-family: Consolas, monospace; }
.wb-chip.chip-toggle { font-size: 11px; padding: 0 5px; color: #4b5563; }
/* 行上那个「自动」标（#105）：来源是自动匹配同名填上的 */
.wb-chip.chip-auto { color: #b45309; border-color: #fde68a; background: #fffbeb; }
/* 行操作：清除 / 撤销（#102）。行里往后排，所以给它一个固定的位置 */
.wb-col-actions { margin-left: auto; display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.wb-chip.chip-clear { color: #b91c1c; border-color: #fecaca; background: #fff; }
.wb-chip.chip-clear:hover:not(:disabled) { border-color: #ef4444; color: #b91c1c; }
.wb-chip.chip-clear:disabled { color: #d1d5db; border-color: #e5e7eb; cursor: not-allowed; }
.wb-chip.chip-undo { color: #b45309; border-color: #fde68a; background: #fffbeb; font-weight: 600; }
.wb-chip.chip-undo:hover { border-color: #d97706; color: #92400e; }
.wb-paren { font-size: 12px; color: #6b7280; }
.wb-start-at { width: 96px; }

/* 行右侧那三种状态（#103）：颜色只在「有错 / 有警告 / 没问题」之间分。
   一行上的**全部**问题都列在这一列里（#113），所以是竖着排、每条各自换行，不再截断成一句 */
.wb-col-issues { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; max-width: 280px; flex-shrink: 0; }
.wb-state { flex-shrink: 0; font-size: 11px; border-radius: 3px; padding: 0 5px; border: 1px solid #e5e7eb; color: #9ca3af; }
.wb-state.error { color: #b91c1c; border-color: #fecaca; background: #fef2f2; }
.wb-state.warning { color: #b45309; border-color: #fde68a; background: #fffbeb; }
.wb-state.ok { color: #15803d; border-color: #bbf7d0; background: #f0fdf4; }
.wb-issue-inline { font-size: 11px; color: #6b7280; overflow-wrap: anywhere; }

/* 就地参数面板：占这一行下面的一行 */
.wb-panel {
  margin: 2px 0 8px 8px; padding: 8px 10px;
  border: 1px solid #dbeafe; border-radius: 6px; background: #f8fafc;
}
.wb-panel-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.wb-panel-target { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }

@media (max-width: 1100px) {
  .wb-target-search { width: 160px; }
}

@media (max-width: 820px) {
  /* 一行竖起来：目标字段 / 映射 / 问题 / 行操作从上到下依次排，谁也不挤谁 */
  .wb-row { flex-direction: column; align-items: stretch; gap: 4px; }
  .wb-col-target { width: auto; }
  .wb-col-value { flex: none; }
  .wb-col-issues { max-width: none; }
  .wb-col-actions { margin-left: 0; justify-content: flex-end; }
  .wb-chip { max-width: 100%; }
  .wb-row.child { margin-left: 12px; }
  .wb-panel { margin-left: 0; }
  /* 参数面板里那几条（按钮换行、输入框铺满）在 styles/workbench-panel.css：
     它们要作用到两个参数编辑器组件**内部**，scoped 的 @media 够不着（#112 走查发现） */
}

@media (max-width: 640px) {
  .wb-rows { padding: 8px; }
  /* 窄到这一步搜索框铺满一行（#106 的口径；它旁边那排按钮跟着换行） */
  .wb-target-search { width: 100%; }
}
</style>
