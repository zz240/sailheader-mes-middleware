<template>
  <!-- 快照面板（#132 / 原型 `.sc-tools` + `.snaps`）：单步卡里的两块快照——
       输入快照与数据快照的格式化树、按键路径搜索、以及三种降级（非 JSON 文本 / 空 / 超长）。
       判定口径全在 `history/snapshot.ts`（纯模块），这里只按 `kind` 分派。
       「与上一步对照」那一档（原型 `.seg` 里的 `diff`）是 #133，落在同一个工具栏上。 -->
  <div class="hc-snaps-wrap" data-od-id="snapshot-panel">
    <div class="hc-sctools" data-od-id="snapshot-tools">
      <div class="hc-sctool">
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

    <div v-else-if="!sides.length" class="hc-list-empty">
      <div class="hc-empty-title">还没有可看的步骤快照。</div>
    </div>

    <template v-else>
      <div class="hc-snaps">
        <section
          v-for="side in sides"
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
              <pre v-if="raw" class="hc-sraw" data-od-id="snapshot-raw-json">{{ side.rawText }}</pre>
              <div v-else class="hc-stree" :data-od-id="`snapshot-tree-${side.which}`">
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

      <div class="hc-snote hc-slegend" data-od-id="snapshot-legend">
        <span class="hc-schip">口径</span>
        <span>
          <b>输入快照</b>＝进入本步时的报文 / 上下文；<b>数据快照</b>＝本步输出，写入
          <code>context[本步序号]</code>，下游用 <code>sourceStep</code> 引用。
          两份都是 <code>message_step</code> 的 TEXT 列（上限 64 KB，<code>#127</code>）。
        </span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 快照面板（#132）。三件事只在这里发生一次：
 * ① 读一份快照落在哪一档（`readSnapshot`）与两份快照怎么组装（`snapshotSides`）——都在纯模块里；
 * ② 工具栏的三个控件（展开层数 / 键路径搜索 / 原始文本）是**两块共用**的一份状态（原型 `state.depth/query/raw`）；
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
import type { MessageStep } from '../../types'

const props = defineProps<{
  /** 选中那一步的**整行**（按 id 取回来，带两份快照原文）；`null` = 还没取到，看 `loading`。 */
  stepRow: MessageStep | null
  loading: boolean
  error: string
}>()

const emit = defineEmits<{ retry: [] }>()

const depth = ref(DEFAULT_SNAPSHOT_DEPTH)
const query = ref('')
const raw = ref(false)
/** 手工展开过的长值（`哪一块:节点路径`）——票面「长值截断可查」的那个出口。 */
const longOpen = ref(new Set<string>())

const sides = computed(() => snapshotSides(props.stepRow, query.value))
/** 搜了就把 `deep` 顶到全展开（`treeDeep` 那一处判决），两块共用。 */
const deep = computed(() => treeDeep(depth.value, query.value))

// 换一步就把「手工展开过的长值」收回去：那张表是按 `哪一块:节点路径` 记的，
// 路径在下一条快照里会重名——不收的话新的一步一进来就有一个键莫名其妙是展开的。
// 展开层数 / 搜索 / 原始文本三格**不**收：它们与「在看哪一步」无关（原型也是全局的一份 state）。
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
