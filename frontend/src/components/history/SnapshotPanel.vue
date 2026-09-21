<template>
  <!-- 快照面板（#132 / 原型 `.sc-tools` + `.snaps`）：单步卡里的两块快照——
       输入快照与数据快照的格式化树、按键路径搜索、以及三种降级（非 JSON 文本 / 空 / 超长）。
       判定口径全在 `history/snapshot.ts`（纯模块），这里只按 `kind` 分派。
       「与上一步对照」那一档（原型 `.seg` 里的 `diff`）是 #133，落在同一个工具栏上。 -->
  <div class="hc-snaps-wrap" data-od-id="snapshot-panel">
    <div class="hc-sctools" data-od-id="snapshot-tools">
      <!-- 视图四档（#133）：前三档是 #132 那两块快照的取景（原型 `.seg` 里的 `side/input/data`），
           第四档「与上一步对照」把这一块换成路径对齐的对照表（原型同一段里的 `diff`）。
           缺了第四档时，那三档里也不会出现「并排」以外的写法——原型本来就是这一段四档一家。 -->
      <div class="hc-sctool">
        <span
          class="hc-sctool-l"
          title="输入快照＝进入本步的报文 / 上下文；数据快照＝本步写回 context[本步序号] 的输出。"
        >视图</span>
        <div class="hc-dseg" role="group" aria-label="快照视图">
          <button
            v-for="option in SNAPSHOT_VIEWS"
            :key="option.value"
            class="hc-sbtn"
            type="button"
            :aria-pressed="view === option.value"
            @click="view = option.value"
          >{{ option.label }}</button>
        </div>
      </div>
      <!-- 展开层数只管那两块树：对照档是一张表，没有「层」可展开（原型也会把这一格留着当摆设，
           这里收掉更诚实——留着它按下去毫无反应，看着像坏了）。 -->
      <div v-if="!isDiff" class="hc-sctool">
        <span
          class="hc-sctool-l"
          title="快照树的默认展开深度：容器自动展开这么多层嵌套，更深的保持折叠；行首 ▸ / ▾ 可以单独开合。搜索命中会整个展开。"
        >展开</span>
        <div class="hc-dseg" role="group" aria-label="默认展开层级">
          <button
            v-for="option in SNAPSHOT_DEPTHS"
            :key="option.value"
            class="hc-sbtn"
            type="button"
            :aria-pressed="depth === option.value"
            @click="depth = option.value"
          >{{ option.label }}</button>
        </div>
      </div>
      <input
        v-model="query"
        class="hc-sq"
        type="text"
        placeholder="搜键路径，如 handle"
        aria-label="搜索键路径"
      >
      <span class="hc-spacer" />
      <button class="hc-sbtn" type="button" :aria-pressed="raw" @click="raw = !raw">原始文本</button>
    </div>

    <div v-if="loading" class="hc-skel" aria-label="快照加载中">
      <i v-for="n in 2" :key="n" />
    </div>

    <div v-else-if="error" class="hc-errbox">
      <div>这一步的快照没取到。</div>
      <div class="hc-snote-src">{{ error }}</div>
      <div class="hc-err-act">
        <el-button size="small" @click="emit('retry')">重试</el-button>
      </div>
    </div>

    <!-- 对照那一档（#133）：两块快照换成按叶子路径对齐的一张表。
         能不能对照由纯模块判（`stepDiffStart`），这里只按它给的三样分派。
         **`v-show` 而不是 `v-if`**：这一档与下面那两块快照是同一个工具栏底下的两个取景，
         用 `v-if` 的话每次切档都会把另一侧的子树销毁重建——树那边是「用户手工折起来的节点
         又被摊开」（组件实例没了），对照这边是白算一遍 diff。两边都留着，只切显示。 -->
    <div v-show="isDiff" data-od-id="snapshot-diff-view">
      <div v-if="prevLoading" class="hc-skel" aria-label="上一步的快照加载中">
        <i v-for="n in 2" :key="n" />
      </div>

      <div v-else-if="prevError" class="hc-errbox">
        <div>上一步的快照没取到，没法对照。</div>
        <div class="hc-snote-src">{{ prevError }}</div>
        <div class="hc-err-act">
          <el-button size="small" @click="emit('retry-prev-row')">重试</el-button>
        </div>
      </div>

      <div v-else-if="diffBlocker" class="hc-diff" data-od-id="snapshot-diff">
        <div class="hc-stree-empty" data-od-id="snapshot-diff-blocked">{{ diffBlocker.text }}</div>
      </div>

      <div v-else-if="diffStart && diff" class="hc-diff" data-od-id="snapshot-diff">
        <div class="hc-dsum">
          <span class="hc-dsum-line">
            上一步 <b>{{ diffStart.prevHead }}</b> 的输出 → 本步 <b>{{ diffStart.nextHead }}</b> 的输出
          </span>
          <span class="hc-spacer" />
          <span
            v-for="item in countItems"
            :key="item.status"
            class="hc-dc"
            :class="`hc-dc-${item.status}`"
          ><i />{{ item.text }}</span>
        </div>

        <div class="hc-dgrid" data-od-id="snapshot-diff-grid">
          <div class="hc-dhead2">
            <span>路径</span><span>上一步</span><span /><span>本步</span><span>状态</span>
          </div>
          <div
            v-for="row in diff.rows"
            :key="`${row.path}-${row.status}`"
            class="hc-drow"
            :class="`hc-dst-${row.status}`"
          >
            <span class="hc-dpath" :title="contextPath(row.path)">{{ row.path }}</span>
            <span class="hc-dval hc-dval-old" :title="cellTitle(row.oldValue)">{{ cellText(row.oldValue) }}</span>
            <span class="hc-darrow" aria-hidden="true">→</span>
            <span class="hc-dval hc-dval-new" :title="cellTitle(row.newValue)">{{ cellText(row.newValue) }}</span>
            <span class="hc-dchip">{{ DIFF_LABELS[row.status] }}</span>
          </div>
          <div v-if="!diff.rows.length" class="hc-stree-empty">两侧都没有可对照的字段。</div>
        </div>

        <!-- 口径那一句（票面要求写在界面上）：叶子路径级、数组按索引、以及那个连弱处一起接受的后果 -->
        <div class="hc-snote" data-od-id="snapshot-diff-note">
          <span class="hc-schip">对照口径</span>
          <span>{{ DIFF_NOTE }}</span>
        </div>
      </div>
    </div>

    <!-- 两块快照那一侧（并排 / 只看输入 / 只看数据）整块用 `v-show` 而不是 `v-if`：
         切到对照那一档再切回来时，这两棵树**不重挂**——重挂会丢掉用户在树上手工开合过的节点
         （那是他自己的浏览状态，他自己折的就得留着他的；`vue-json-pretty` 的折叠态记在组件实例上，
         挂在 `v-if` 分支里等于每次切视图都重置一遍）。

         里面**不再套 `v-if` / `v-show`**：外面这层留着、里面一判「有没有快照」，
         `sides` 在对照档是空数组，子节点照样被销毁重建（真机脚本第一版就是这么抓到
         「切回来又全展开了」的）。空态与列表就叠着铺，空态那一格有自己的底色。 -->
    <div v-show="!isDiff">
      <div class="hc-snaps">
        <section
          v-for="side in allSides"
          v-show="view === 'side' || view === side.which"
          :key="side.which"
          class="hc-snap"
          :data-od-id="`snapshot-${side.which}`"
        >
          <div class="hc-snaphead">
            <div class="hc-snaphr">
              <span class="hc-snapt">{{ side.title }}</span>
              <span class="hc-snapw">{{ side.where }}</span>
              <button
                v-if="side.copyLabel"
                class="hc-sbtn hc-snapcp"
                type="button"
                @click="copySide(side)"
              >{{ side.copyLabel }}</button>
            </div>
          </div>

          <div class="hc-snapbody">
            <!-- 降级一：没落（空白也算没落，与步骤链上那两个小点同一个判据） -->
            <div v-if="side.read.kind === 'missing'" class="hc-stree-empty" data-od-id="snapshot-missing">
              本步没有{{ side.title }}。
            </div>

            <!-- 降级二：非 JSON 文本 / 超长——**不解析**，原样显示（超长只画前 8 KB） -->
            <template v-else-if="side.read.kind === 'text' || side.read.kind === 'oversized'">
              <pre class="hc-sraw" data-od-id="snapshot-raw">{{ side.read.text }}</pre>
              <div
                class="hc-snote hc-snote-warn"
                :data-od-id="`snapshot-${side.which}-note`"
              >
                <span class="hc-schip">{{ side.read.kind === 'oversized' ? '超长快照' : '非 JSON 文本' }}</span>
                <span>{{ side.read.note }}</span>
              </div>
            </template>

            <!-- 降级三：空对象 / 空数组——树照画（`{}`），下面再补一句说清「合法但是空的」 -->
            <template v-else>
              <!-- `v-show` 而不是 `v-if`（#133）：切到对照那一档再切回来时，这一棵**不重挂**——
                   重挂会丢掉用户在树上手工开合过的节点（那是他自己的浏览状态）。
                   下面那个 `v-else` 分支里本就是「换数据 / 换搜索才整棵重挂」，两处不冲突。 -->
              <pre v-show="raw" class="hc-sraw" data-od-id="snapshot-raw-json">{{ side.rawText }}</pre>
              <div v-show="!raw" class="hc-stree" :data-od-id="`snapshot-tree-${side.which}`">
                <!-- 换数据 / 换搜索就整棵重挂：库里那份折叠态是按路径记的，不重挂的话
                     「搜索命中的分支自动展开」会被上一次的手动折叠盖掉 -->
                <JsonPretty
                  :key="`${side.which}-${deep}-${query}`"
                  :data="side.tree"
                  :deep="deep"
                  :show-length="true"
                  :show-icon="true"
                  :show-line="false"
                  theme="light"
                >
                  <template #renderNodeValue="{ node, defaultValue }">
                    <!-- 值那一格里的东西都**摆在行里**（不走库那个绝对定位的 actions 覆盖层：
                         它压在右端，会把这一格里的「展开 N 字」盖住——点不着，实测踩到过） -->
                    <span class="hc-jcell">
                      <span v-if="isLongValue(node.content)" class="hc-jlong">
                        <span class="hc-jlong-v">{{ longText(side.which, node) }}</span>
                        <button
                          class="hc-jmore"
                          type="button"
                          @click.stop="toggleLong(side.which, node)"
                        >{{ longOpen.has(longKey(side.which, node)) ? '收起' : `展开 ${node.content.length} 字` }}</button>
                      </span>
                      <span v-else>{{ slotText(defaultValue) }}</span>
                      <!-- 只给**叶子**一格「复制值」：容器的 `content` 是那半个括号，复制它没意义
                           （整份要复制走面板头上那个按钮）。平时透明，hover 那一行才浮出来。 -->
                      <button
                        v-if="node.type === 'content'"
                        class="hc-jact"
                        type="button"
                        @click.stop="copyValue(node)"
                      >复制值</button>
                    </span>
                  </template>
                </JsonPretty>
              </div>

              <div
                v-if="side.read.emptyLabel"
                class="hc-snote hc-snote-warn"
                :data-od-id="`snapshot-${side.which}-empty`"
              >
                <span class="hc-schip">{{ side.read.emptyLabel }}</span>
                <span>{{ side.read.note }}</span>
              </div>
            </template>

            <!-- 搜索那一句（两块各数各的命中）：ADR-0016 决策 10 —— 不做行内高亮，改成
                 「按键路径过滤 + 命中分支自动展开」。这一格**三种档都在**：搜索框是两块共用的，
                 非 JSON / 超长那两档也得回一句「没得搜」，不然敲了字界面一动不动（看着像坏了）。 -->
            <div v-if="side.searchNote" class="hc-snote" data-od-id="snapshot-search-note">
              <span class="hc-schip">搜索</span>
              <span>{{ side.searchNote }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- 没快照时那一句（`sides` 空 = 手里还没这一步的整行）：空态铺在网格下面，
           照旧不铺树也不铺口径那一行 -->
      <div v-if="!allSides.length" class="hc-list-empty">
        <div class="hc-empty-title">还没有可看的步骤快照。</div>
      </div>

      <div v-else class="hc-snote hc-slegend" data-od-id="snapshot-legend">
        <span class="hc-schip">口径</span>
        <span>
          <b>输入快照</b>＝进入本步时的报文 / 上下文；<b>数据快照</b>＝本步输出，写入
          <code>context[本步序号]</code>，下游用 <code>sourceStep</code> 引用。
          两份都是 <code>message_step</code> 的 TEXT 列（上限 64 KB，<code>#127</code>）。
        </span>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
/**
 * 快照面板（#132 与 #133）。四件事只在这里发生一次：
 * ① 读一份快照落在哪一档（`readSnapshot`）与两份快照怎么组装（`snapshotSides`）——都在纯模块里；
 * ② 工具栏那几个控件（视图四档 / 展开层数 / 键路径搜索 / 原始文本）是**两块共用**的一份状态
 *    （原型 `state.snapView/depth/query/raw`）；对照那两档的判据在 `history/diff.ts`，
 *    这个组件不自己判「能不能对照」；
 * ③ 复制（整份 JSON / 原文 / 单格的值）走 `navigator.clipboard`——127.0.0.1 是安全上下文，能写。
 */
import { computed, ref, watch } from 'vue'
import JsonPretty from 'vue-json-pretty'
import { ElMessage } from 'element-plus'
// 组件库自带的样式（行高、括号、缩进、hover）：它用的是自己的硬编码颜色，
// 与本页令牌冲突的那几处由 `styles/history-console.css` 的 `.hc-stree` 那一段压回去。
import 'vue-json-pretty/lib/styles.css'
import {
  DEFAULT_SNAPSHOT_DEPTH, SNAPSHOT_DEPTHS, isLongValue, longPreview, snapshotSides, treeDeep,
  type SnapshotSide,
} from '../../history/snapshot'
import {
  contextPath, diffCellText, diffCountsText, snapshotDiff, stepDiffStart, DIFF_LABELS, DIFF_NOTE,
} from '../../history/diff'
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 选中那一步的**整行**（按 id 取回来，带两份快照原文）；`null` = 还没取到，看 `loading`。 */
  stepRow: MessageStep | null
  loading: boolean
  error: string
  /**
   * **上一步**的整行（#133）：对照那一档要的是它的数据快照。它与上面那一行走的是同一条
   * 「按 id 取整行」的路（索引那条路上没有快照原文），所以三态也是三格。
   */
  prevStepRow: MessageStep | null
  prevLoading: boolean
  prevError: string
}>()

const emit = defineEmits<{ retry: []; 'retry-prev-row': [] }>()

/** 视图四档（#133 补齐；原型 `.seg` 里那一排的取值与文案照抄）。 */
const SNAPSHOT_VIEWS = [
  { value: 'side', label: '并排对照' },
  { value: 'input', label: '只看输入' },
  { value: 'data', label: '只看数据' },
  { value: 'diff', label: '与上一步对照' },
] as const

type SnapshotView = (typeof SNAPSHOT_VIEWS)[number]['value']

const view = ref<SnapshotView>('side')
const depth = ref(DEFAULT_SNAPSHOT_DEPTH)
const query = ref('')
const raw = ref(false)
/** 手工展开过的长值（`哪一块:节点路径`）——票面「长值截断可查」的那个出口。 */
const longOpen = ref(new Set<string>())

const isDiff = computed(() => view.value === 'diff')
/**
 * 两块快照**始终都算出来**（不按视图过滤），视图只决定哪一块显示出来（模板上的 `v-show`）。
 *
 * 这一条是踩出来的：过滤掉的写法（`view` 一变就让 `sides` 少一块）会让 `v-for` 把那一整棵
 * 子树销毁重建——用户在树上手工折起来的节点当场被摊开（`vue-json-pretty` 的折叠态记在组件实例上）。
 * 真机脚本第一版就是这么抓到「切到对照再切回来又全展开了」的。
 */
const allSides = computed(() => snapshotSides(props.stepRow, query.value))
/** 搜了就把 `deep` 顶到全展开（`treeDeep` 那一处判决），两块共用。 */
const deep = computed(() => treeDeep(depth.value, query.value))

/* ── 对照那一档（#133） ─────────────────────────────────────────── */

/** 能不能对照（第一步 / 哪一侧缺哪种快照）：判据全在 `history/diff.ts`，这里只分派。 */
const stepDiff = computed(() => stepDiffStart(props.prevStepRow, props.stepRow, {
  prevAction: props.prevStepRow?.actionName ?? undefined,
  nextAction: props.stepRow?.actionName ?? undefined,
}))
const diffStart = computed(() => stepDiff.value.start)
const diffBlocker = computed(() => stepDiff.value.blocker)
/** 两侧都有可对照的快照时才真去算那张表（挡路时不白算一遍）。 */
const diff = computed(() => {
  const start = diffStart.value
  if (!start) return null
  const before = JSON.parse(start.prev.dataSnapshot ?? 'null')
  const after = JSON.parse(start.next.dataSnapshot ?? 'null')
  return snapshotDiff(before, after)
})
const countItems = computed(() => (diff.value ? diffCountsText(diff.value.counts) : []))

/** 一格里显示什么（`null` / 空串 / ISO 时刻 / 长串截断 / 缺的那一侧给破折号）。 */
function cellText(value: unknown): string {
  return diffCellText(value)
}

/**
 * 那一格的 `title`（鼠标停上去看全文）：值本身，不是截断后的那半段。
 *
 * 值那一格是**截断**显示的（原型 `showVal` 那个 46 字），而这一块是「看证据」的地方——
 * 一串长长的 handle 被截掉尾巴就再也认不出是哪一个了。原型给的是路径的 `title`（路径也在，
 * 在左边那一格上），这里不搬它自己画的「展开 N 字」按钮：一张表上每行两个按钮太吵。
 */
function cellTitle(value: unknown): string {
  return value === undefined ? '' : JSON.stringify(value) ?? ''
}

// 换一步就把「手工展开过的长值」收回去：那张表是按 `哪一块:节点路径` 记的，
// 路径在下一条快照里会重名——不收的话新的一步一进来就有一个键莫名其妙是展开的。
// 展开层数 / 搜索 / 原始文本三格**不**收：它们与「在看哪一步」无关（原型也是全局的一份 state）。
// 视图也不收：原型那份 `snapView` 同样是全局的一份 state，切步骤时留在用户选的那一档。
watch(() => props.stepRow?.id, () => { longOpen.value = new Set<string>() })

/** 槽里那个节点：只用到这四格（库的 `.d.ts` 是从 TSX 生成的，比运行时宽）。 */
interface JsonNode {
  type?: string
  path?: string
  content?: unknown
  key?: string
}

/**
 * 值那一格的默认渲染。运行时它**一定是字符串**（库里 `g` 是个 `computed`，算出来就是字符串），
 * `.d.ts` 里写成 `string | JSX.Element` 是 TSX 时代的残留——所以这里只做一次窄化，不改行为。
 */
function slotText(value: unknown): string {
  return typeof value === 'string' ? value : String(value)
}

function longKey(which: string, node: JsonNode): string {
  return `${which}:${node.path ?? ''}`
}

/** 长值那一行显示什么：截断的空档给前 72 字 + 「展开 N 字」，展开后给全文。 */
function longText(which: string, node: JsonNode): string {
  const text = typeof node.content === 'string' ? node.content : ''
  return longOpen.value.has(longKey(which, node)) ? JSON.stringify(text) : longPreview(text)
}

function toggleLong(which: string, node: JsonNode): void {
  const key = longKey(which, node)
  const next = new Set(longOpen.value)
  if (!next.delete(key)) next.add(key)
  longOpen.value = next
}

/** 面板头上那个按钮：JSON 档复制格式化后的 JSON，文本档复制**原文全文**（不是截断那半段）。 */
function copySide(side: SnapshotSide): void {
  void write(side.copyText, `已复制${side.title}${side.copyKind === 'text' ? '原文' : '（JSON）'}`)
}

/** 一格里那个「复制值」：叶子节点的 `content` 就是它的真实值（容器那半个括号不给这个按钮）。 */
function copyValue(node: JsonNode): void {
  if (node.type !== 'content') return
  void write(JSON.stringify(node.content, null, 2), '已复制这个值（JSON）')
}

/**
 * 写剪贴板。**失败要说出来**：`navigator.clipboard` 在非安全上下文 / 没给权限时是抛错的，
 * 静默失败比复制错东西更坏（原型那句 toast 的同一个位置）。
 */
async function write(text: string, done: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(done)
  } catch {
    ElMessage.error('复制没成功：浏览器没给剪贴板权限')
  }
}
</script>
