<script setup lang="ts">
/**
 * 左边的来源字段清单（#117 从 `FieldMappingWorkbench.vue` 拆出来）：搜索、三档过滤、已用高亮与计数、
 * 键盘「走清单」那一段（#105 / #106），以及顶上那两个入口——从运行记录探测 / 粘贴 JSON（#115）。
 *
 * 它给什么字段由**当前选中的那一行**决定（数组行只给数组、子行给相对路径与外层，`source-picker.ts`），
 * 「哪些被用过了、用了几处」由行树算（`fast-config.ts`）——两者都是纯逻辑，这里只负责把它们画出来。
 * 过滤与搜索也只回答「这一项要不要显示」，**不判对错**：来源对不对仍然只在后端那个只读接口里判（#98 D1）。
 *
 * 顶上那两个入口是两条**并列**的路（#115）：点哪个只发一个意图出去，取清单与推断都不在这里
 * （探测那条路要发请求、粘的那条路要发到后端推字段，两件事都归工作台）；入口下面那句
 * 「数组按第 1 条推的」是工作台算好传进来的（`mapping/sample.ts` 的 `sampleNoteOf`）。
 *
 * 它在界面上是一整块面板（`aside`），所以「走清单」这一段的状态（走到第几项）也归它：
 * 回车进来（`beginPick`）、上下走（`movePick`）、回车填进去（`confirmPick`）、Esc 退出（`endPick`）
 * 由工作台那一段键盘按意图调用，填行本身还是发 `pick` 出去（行是工作台那一层的状态）。
 */
import { computed, nextTick, ref } from 'vue'
import {
  filterSourceGroups, requiredSourceKeys, sourceUsageOf, usedCountOf, usedRowIdsOf,
  SOURCE_FILTERS, SOURCE_FILTER_LABELS, type SourceFilter,
} from '../mapping/fast-config.ts'
import { flattenRows } from '../mapping/editor-model.ts'
import { sourcePickerFor, type PickableItem } from '../mapping/source-picker.ts'
import { stepIndex } from '../mapping/keyboard.ts'
import { useWorkbenchState } from '../mapping/useWorkbenchState.ts'

const props = defineProps<{
  /** 这份清单取自哪儿（哪一步的运行输出，或者粘进来的一份样例）——工作台从编辑器上下文里算好传进来。 */
  sourceTip: string
  /** 数组样例按第几条推的（#115）；整份就是一个对象时是空串，这一行不铺。 */
  sampleNote: string
  /** 正在换默认来源 / 重新取清单 / 推粘进来的样例。 */
  loading: boolean
}>()

const emit = defineEmits<{
  /** 点了一项（或者键盘在清单里回车）：把这一项填进当前选中的那一行。 */
  (e: 'pick', item: PickableItem): void
  /** 「从运行记录探测」：重新取这一步最近一次成功运行的输出（#115）。 */
  (e: 'probe'): void
  /** 「粘贴 JSON」：打开粘样例那个框（#115）。 */
  (e: 'paste'): void
}>()

const { rows, targets, sourceFields, defaultStep, selectedEntry } = useWorkbenchState()

/** 来源清单的搜索词与过滤档（#105）：搜路径、搜样例值；按「全部 / 只看未配置 / 只看必填」筛。 */
const sourceQuery = ref('')
const sourceFilter = ref<SourceFilter>('all')

/** 行里每个来源字段被引用了几处、被哪几行引用（高亮与计数用它）。 */
const sourceUsage = computed(() => sourceUsageOf(rows.value))

/** 「只看必填」要用的那份清单：还没配好、且目标字段标了 required 的那些。 */
const requiredSources = computed(() =>
  requiredSourceKeys(rows.value, targets.value, defaultStep.value, sourceFields.value))

/** 左边清单给什么字段，取决于**当前选中的这一行**（#101：数组行只给数组、子行给相对路径与外层）。 */
const pickerGroupsAll = computed(() =>
  sourcePickerFor(selectedEntry.value?.row ?? null, sourceFields.value, selectedEntry.value?.parent ?? null, defaultStep.value))

/**
 * 「只看必填」比的是「这一项会被填到哪一步、哪一层」：子行里数组元素字段存的是相对路径，
 * 它取哪份来源数据由组节点决定（`sourcePickerFor` 把那一组给出来，就说明组节点就是这一步的）。
 */
const pickerStep = computed(() => selectedEntry.value?.parent
  ? selectedEntry.value.parent.sourceStep ?? defaultStep.value
  : defaultStep.value)

/** 当前清单给的是不是数组元素子行那一组（必填那一档要按层分开看）。 */
const pickerInArray = computed(() => selectedEntry.value?.parent != null)

/** 这一档 + 这个词之后，清单上真正铺出来的那些项。 */
const pickerGroups = computed(() => filterSourceGroups(
  pickerGroupsAll.value, sourceQuery.value, sourceFilter.value, sourceUsage.value, requiredSources.value,
  pickerStep.value, pickerInArray.value))

/**
 * 清单铺出来时带上「第几项」（键盘走清单用的编号，#106）。
 *
 * 键盘走的顺序就是眼睛看到的顺序（分组一段接一段），所以编号要跨分组连着数——分组各数各的，
 * 上下键走到分组边界就会跳回去。
 */
const pickerViews = computed(() => {
  let index = 0
  return pickerGroups.value.map(group => ({
    title: group.title,
    tip: group.tip,
    items: group.items.map(item => ({ item, index: index++ })),
  }))
})

/** 每一档下能显示几项（按钮上的数字）；空档也要看得见，所以按整份清单算，不按已经筛过的那份。 */
const sourceFilterCounts = computed<Record<SourceFilter, number>>(() => {
  const groups = pickerGroupsAll.value
  const count = (filter: SourceFilter) => filterSourceGroups(
    groups, sourceQuery.value, filter, sourceUsage.value, requiredSources.value,
    pickerStep.value, pickerInArray.value)
    .reduce((sum, group) => sum + group.items.length, 0)
  return { all: count('all'), unconfigured: count('unconfigured'), required: count('required') }
})

/** 筛完一项都不剩时说清是搜索词的问题还是这一档本来就没有。 */
const emptyPickerText = computed(() => {
  if (sourceQuery.value.trim()) return `没有路径或样例值里有「${sourceQuery.value.trim()}」的字段`
  const texts: Record<SourceFilter, string> = {
    all: '这一来源还没有字段清单',
    unconfigured: '这一来源的字段都已经被用过了',
    required: '必填的目标字段都配好了',
  }
  return texts[sourceFilter.value]
})

/** 这一项被用过（清单上高亮 + 显示几处）。 */
function isUsed(item: PickableItem): boolean {
  return usedCount(item) > 0
}

function usedCount(item: PickableItem): number {
  // 参数顺序是（来源清单，这一步）：相对路径要靠清单认出「属于哪个数组」，再按步骤分开算（#105）
  return usedCountOf(sourceUsage.value, item, sourceFields.value, pickerStep.value)
}

/** 已用那一项的悬停提示：被几处引用、第一处在哪个目标字段上。 */
function usedHint(item: PickableItem): string {
  const count = usedCount(item)
  if (!count) return ''
  const ids = usedRowIdsOf(sourceUsage.value, item, sourceFields.value, pickerStep.value)
  const first = flattenRows(rows.value).find(e => e.row.id === ids[0])
  const where = first ? `：${first.parent ? `${first.parent.target.trim()} · ` : ''}${first.row.target.trim()}` : ''
  return `这个来源已经被引用了 ${count} 处${where}`
}

/** 样例值的短显示（太长就截断——它只是给人认字段用）。 */
function sampleOf(item: PickableItem): string {
  const first = item.samples?.[0]
  if (first == null) return ''
  const text = typeof first === 'object' ? JSON.stringify(first) : String(first)
  return text.length > 18 ? `${text.slice(0, 18)}…` : text
}

// ---------------------------------------------------------------- 「走清单」这一段（#106）

/** 清单的容器：键盘选来源时把焦点放进来，让上下键不再落在某个输入框里。 */
const sourceListEl = ref<HTMLElement | null>(null)
/** 「选来源」这一段开着（回车进来的）：↑↓ 在清单里走，回车填进这一行，Esc 退出。 */
const pickingSource = ref(false)
/** 键盘走到清单上的第几项（跨分组连着数，与 `pickerViews` 的编号一致）。 */
const pickIndex = ref(0)

/** 清单一整份摊平（键盘走清单的顺序就是它）。 */
const flatPickItems = computed(() => pickerGroups.value.flatMap(group => group.items))

/** 还没选行时先说清怎么开始；「选来源」这一段开着时说清键盘怎么走。 */
const pickerTip = computed(() => {
  if (pickingSource.value) return '选来源：↑↓ 走，回车填进这一行，Esc 退出'
  return selectedEntry.value ? '点一下字段就填进选中的那一行（也可以按回车用键盘选）' : '先点一行目标字段，再从这里选来源'
})

/** 回车进来：停在第几项、焦点交给清单、滚到看得见。工作台先把参数面板收起来（那一步在它那边）。 */
function beginPick() {
  pickingSource.value = true
  pickIndex.value = 0
  nextTick(() => {
    sourceListEl.value?.focus()
    scrollPickItemIntoView()
  })
}

/** 走清单：跳过没有的项，走到头就停在原地（`stepIndex` 的口径与面板那边一样）。 */
function movePick(delta: -1 | 1) {
  pickIndex.value = stepIndex(pickIndex.value, delta, flatPickItems.value.length)
  nextTick(scrollPickItemIntoView)
}

/** 键盘走到的那一项要看得见：清单可能是滚动的（长清单 + 过滤之后）。 */
function scrollPickItemIntoView() {
  document.querySelector<HTMLElement>(`[data-pick-index="${pickIndex.value}"]`)?.scrollIntoView({ block: 'nearest' })
}

/** 回车：把清单里停着的那一项填进这一行，然后退出这一段（选中的行会自己往下走一行）。 */
function confirmPick() {
  const item = flatPickItems.value[pickIndex.value]
  endPick()
  if (item) emit('pick', item)
}

/** 退出「选来源」这一段（Esc，或者已经填进去了）。 */
function endPick() {
  pickingSource.value = false
  pickIndex.value = 0
}

/** 鼠标点进来的也算把「选来源」这一段走完了：键盘那一段的状态不留到下一次。 */
function onItemClick(item: PickableItem) {
  endPick()
  emit('pick', item)
}

defineExpose({
  /** 这一段开着吗（工作台那一段键盘要看它）。 */
  picking: pickingSource,
  /** 这一档 + 这个词之后清单上有几项（进来之前先问一句「有没有得选」）。 */
  itemCount: computed(() => flatPickItems.value.length),
  /** 一项都没有时的说明（工作台拿它弹提示）。 */
  emptyText: emptyPickerText,
  beginPick,
  movePick,
  confirmPick,
  endPick,
})
</script>

<template>
  <aside class="wb-source">
    <div class="wb-panel-title">来源字段</div>
    <!-- 两条并列的路（#115）：谁都不兜底谁。探测那条路取这一步的运行输出，粘的那条路用手上那份来源数据 -->
    <div class="wb-source-entries">
      <el-button size="small" class="wb-source-entry" :loading="props.loading" @click="emit('probe')">
        从运行记录探测
      </el-button>
      <el-button size="small" class="wb-source-entry" @click="emit('paste')">粘贴 JSON</el-button>
    </div>
    <div class="wb-panel-tip">{{ props.sourceTip }}</div>
    <!-- 数组只按第 1 条推的：这件事得写在清单上面，不然会以为后面几条的字段也进来了（#115） -->
    <div v-if="props.sampleNote" class="wb-panel-tip wb-panel-note">{{ props.sampleNote }}</div>
    <div class="wb-panel-tip">{{ pickerTip }}</div>
    <el-input
      v-model="sourceQuery"
      size="small"
      clearable
      class="wb-source-search"
      placeholder="搜路径或样例值，例如 qty / PCS"
    />
    <div class="wb-source-filters">
      <button
        v-for="f in SOURCE_FILTERS"
        :key="f"
        class="wb-filterbtn"
        :class="{ current: sourceFilter === f }"
        @click="sourceFilter = f"
      >{{ SOURCE_FILTER_LABELS[f] }} <small>{{ sourceFilterCounts[f] }}</small></button>
    </div>
    <div v-if="props.loading" class="wb-empty">加载中…</div>
    <div v-else-if="!sourceFields.length" class="wb-empty">这一来源还没有字段清单</div>
    <div v-else-if="!pickerGroups.length" class="wb-empty">{{ emptyPickerText }}</div>
    <div
      v-else
      ref="sourceListEl"
      class="wb-source-list"
      :class="{ picking: pickingSource }"
      tabindex="-1"
    >
      <div v-for="(group, gi) in pickerViews" :key="gi" class="wb-source-group">
        <div class="wb-source-group-title">{{ group.title }}</div>
        <div v-if="group.tip" class="wb-source-group-tip">{{ group.tip }}</div>
        <div
          v-for="view in group.items"
          :key="view.item.label"
          class="wb-source-item"
          :class="{
            'array-field': view.item.kind === 'array',
            used: isUsed(view.item),
            'kb-current': pickingSource && view.index === pickIndex,
          }"
          :data-pick-index="view.index"
          tabindex="0"
          :title="usedHint(view.item)"
          @click="onItemClick(view.item)"
          @keydown.enter.prevent="onItemClick(view.item)"
        >
          <div class="wb-source-item-main">
            <span class="wb-source-name">{{ view.item.label }}</span>
            <span v-if="view.item.kind === 'array'" class="wb-chip">数组</span>
            <!-- 已经被用过的来源字段高亮，并写明被引用了几处（#105） -->
            <span v-if="isUsed(view.item)" class="wb-chip used-badge">{{ usedCount(view.item) }} 处</span>
          </div>
          <div class="wb-source-meta">{{ view.item.type }}<template v-if="sampleOf(view.item)"> · 样例 {{ sampleOf(view.item) }}</template></div>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.wb-source {
  width: 280px; flex-shrink: 0; border-right: 1px solid var(--border);
  display: flex; flex-direction: column; padding: 10px; min-height: 0;
}
.wb-source-list { overflow: auto; flex: 1; outline: none; }
/* 顶上那两个入口（#115）：一行放得下，窄屏也不会顶掉下面的清单 */
.wb-source-entries { display: flex; gap: 6px; margin-bottom: 6px; }
.wb-source-entry { flex: 1; min-width: 0; padding-left: 0; padding-right: 0; }
.wb-panel-note { color: var(--warn-ink); }
.wb-source-group + .wb-source-group { margin-top: 10px; }
.wb-source-group-title { font-size: var(--fs-body); color: var(--fg); font-weight: 600; margin-bottom: 4px; }
.wb-source-group-tip { font-size: var(--fs-meta); color: var(--warn-ink); margin-bottom: 4px; }
.wb-source-item { padding: 5px 6px; border-radius: var(--radius); cursor: pointer; }
.wb-source-item:hover { background: var(--fg-soft); }
.wb-source-item.array-field .wb-source-name { color: var(--accent); }
/* 键盘走到的那一项（#106）：与鼠标悬停同一个落点，用边框区分是键盘在走 */
.wb-source-list.picking .wb-source-item.kb-current {
  background: var(--accent-soft); outline: 2px solid var(--accent); outline-offset: -2px;
}
/* 已经被用过的来源字段：整项高亮，右侧写清被引用了几处（#105） */
.wb-source-item.used { background: var(--accent-soft); }
/* 悬停那一档不能也写 `--accent-soft`（票里那张表把原来的两个浅蓝都收进它）：那样悬停与 `.used`
   会塌成同一个值，「已经用过」这一项鼠标扫过去就没有反馈了。同族再深一档，值就地写在这里
   而不是新造令牌——票里明说「收口，不新增令牌」。 */
.wb-source-item.used:hover { background: color-mix(in oklch, var(--accent) 20%, transparent); }
.wb-source-item.used .wb-source-name { color: var(--accent-strong); }
.wb-source-item-main { display: flex; align-items: center; gap: 6px; }
.wb-source-name { font-family: var(--font-mono); font-size: var(--fs-body); }
.wb-source-meta { font-size: var(--fs-meta); color: var(--muted); margin-top: 2px; }
.wb-chip.used-badge { color: var(--accent-strong); border-color: var(--accent-line); background: var(--accent-soft); }

/* 来源清单的搜索与三档过滤（#105） */
.wb-source-search { margin-bottom: 6px; }
.wb-source-filters { display: flex; gap: 4px; margin-bottom: 6px; flex-wrap: wrap; }
.wb-filterbtn {
  font: inherit; font-size: var(--fs-meta); line-height: 18px; padding: 0 6px; cursor: pointer;
  border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--fg);
}
.wb-filterbtn:hover { border-color: var(--accent); color: var(--accent); }
.wb-filterbtn.current { border-color: var(--accent); background: var(--accent-soft); color: var(--accent); }
.wb-filterbtn small { color: var(--muted); }
.wb-filterbtn.current small { color: var(--accent); }

@media (max-width: 1024px) {
  /* 来源清单铺在上面：宽度不够时它和行列表并排，两边都读不成 */
  .wb-source {
    width: auto; max-height: 220px; border-right: 0;
    border-bottom: 1px solid var(--border); flex-shrink: 0;
  }
  /* 顶上多了两个入口（#115）：清单这一段让出同样的高度，整块仍然守着上面那个 max-height */
  .wb-source-list { flex: none; max-height: 110px; }
}

@media (max-width: 640px) {
  .wb-source { padding: 8px; }
}
</style>
