<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="100%"
    :with-header="false"
    :close-on-click-modal="false"
    class="mapping-workbench-drawer"
  >
    <div class="wb" tabindex="-1" ref="rootEl">
      <!-- 顶部：标题 + 默认来源 + 进度 -->
      <header class="wb-head">
        <div class="wb-title">
          <strong>字段映射</strong>
          <span class="wb-sub">{{ actionName || '新动作' }}<template v-if="jobName"> · {{ jobName }}</template></span>
        </div>

        <div class="wb-head-actions">
          <span class="wb-label">默认来源</span>
          <el-select
            :model-value="defaultStep"
            size="small"
            class="wb-step-select"
            @update:model-value="onDefaultStepChange"
          >
            <el-option
              v-for="s in stepOptions"
              :key="String(s.step)"
              :value="s.step ?? 0"
              :label="s.label"
            />
          </el-select>

          <el-tooltip placement="bottom" effect="light">
            <template #content>
              <div>必填：顶层 {{ progress.top.configured }}/{{ progress.top.total }}</div>
              <div>必填：数组内 {{ progress.array.configured }}/{{ progress.array.total }}</div>
              <div>可选：{{ progress.optional.configured }}/{{ progress.optional.total }}</div>
            </template>
            <div class="wb-progress">
              <el-progress
                :percentage="progressPercent"
                :stroke-width="14"
                :status="progress.total > 0 && progress.configured >= progress.total ? 'success' : undefined"
              />
              <span class="wb-progress-text">必填 {{ progress.configured }}/{{ progress.total }}</span>
            </div>
          </el-tooltip>

          <el-button size="small" @click="close">取消</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
        </div>
      </header>

      <!-- 主体：左来源清单 + 右逐行配置 -->
      <el-alert
        v-if="expressionMode"
        class="wb-alert"
        type="warning"
        :closable="false"
        title="这个动作现在用的是手写 JSONata 表达式"
        description="新版逐行编辑器这一轮还看不了它。保存会用逐行规则替换掉这份表达式；只想改表达式请先用旧编辑器。"
      />
      <div class="wb-body">
        <aside class="wb-source">
          <div class="wb-panel-title">来源字段</div>
          <div class="wb-panel-tip">{{ sourceTip }}</div>
          <div class="wb-panel-tip">{{ pickerTip }}</div>
          <div v-if="loading" class="wb-empty">加载中…</div>
          <div v-else-if="!pickerGroups.length" class="wb-empty">这一来源还没有字段清单</div>
          <div v-else class="wb-source-list">
            <div v-for="(group, gi) in pickerGroups" :key="gi" class="wb-source-group">
              <div class="wb-source-group-title">{{ group.title }}</div>
              <div v-if="group.tip" class="wb-source-group-tip">{{ group.tip }}</div>
              <div
                v-for="item in group.items"
                :key="item.label"
                class="wb-source-item"
                :class="{ 'array-field': item.kind === 'array' }"
                tabindex="0"
                @click="pick(item)"
                @keydown.enter.prevent="pick(item)"
              >
                <div class="wb-source-item-main">
                  <span class="wb-source-name">{{ item.label }}</span>
                  <span v-if="item.kind === 'array'" class="wb-chip">数组</span>
                </div>
                <div class="wb-source-meta">{{ item.type }}<template v-if="sampleOf(item)"> · 样例 {{ sampleOf(item) }}</template></div>
              </div>
            </div>
          </div>
        </aside>

        <section class="wb-rows">
          <div class="wb-rows-head">
            <div class="wb-panel-title">目标字段</div>
            <div class="wb-panel-tip">{{ targetTip }}</div>
            <span class="wb-spacer" />
            <el-button size="small" text @click="toggleAllArrays">{{ allCollapsed ? '展开全部数组' : '收起全部数组' }}</el-button>
            <el-button size="small" text type="primary" @click="reloadTargets">重新探测目标字段</el-button>
          </div>

          <div v-if="!rows.length" class="wb-empty">
            还没有目标字段清单——选一个写目标动作，或者先点「重新探测目标字段」。
          </div>

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
                  <el-input
                    v-model="entry.row.target"
                    size="small"
                    :placeholder="entry.depth > 0 ? '相对路径，例如 materialCode' : '目标字段路径（可手输）'"
                    @focus="select(entry.row)"
                    @click="stop"
                  >
                    <template v-if="!entry.fromTargets" #append>
                      <span class="wb-chip">手写</span>
                    </template>
                  </el-input>
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

                    <!-- 拼接：片段挪到行外单独一行，才能既读得出来又加删得动 -->
                    <template v-if="entry.row.transform === 'CONCAT'">
                      <span class="wb-paren">（</span>
                      <template v-for="(part, i) in entry.row.parts" :key="i">
                        <span v-if="i" class="wb-paren"> + </span>
                        <button
                          class="wb-chip"
                          :class="part.type === 'path' ? 'chip-src' : 'chip-const'"
                          :title="part.type === 'path' ? '点一下换片段' : '点一下改常量'"
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

                    <!-- 行号：起始值直接显示在这一行里 -->
                    <template v-if="entry.row.transform === 'INDEX'">
                      <span class="wb-paren">从</span>
                      <el-input-number
                        :model-value="entry.row.startAt ?? 1"
                        size="small"
                        controls-position="right"
                        class="wb-start-at"
                        @update:model-value="onStartAtChange(entry.row, $event)"
                      />
                      <span class="wb-paren">起</span>
                    </template>
                  </template>

                  <!-- 数组行：往下面加子行（子行的目标字段写相对路径） -->
                  <template v-if="entry.row.iterate === 'FOR_EACH'">
                    <span class="wb-paren">·</span>
                    <el-button size="small" text type="primary" @click.stop="addChild(entry.row)">+ 子字段</el-button>
                  </template>
                </div>
              </div>

              <!-- 就地参数编辑：就占这一行的下面一行，不开弹窗 -->
              <div v-if="panelRowId === entry.row.id" class="wb-panel" @click="stop">
                <div class="wb-panel-head">
                  <span class="wb-panel-title">{{ panelTitle }}</span>
                  <span class="wb-panel-target">{{ entry.row.target || '（还没填目标字段）' }}</span>
                  <span class="wb-spacer" />
                  <el-button size="small" text @click="closePanel">关闭</el-button>
                </div>

                <!-- 换转换：七种全在，不能用的置灰并说明原因 -->
                <template v-if="panel === 'op'">
                  <div class="wb-ops">
                    <button
                      v-for="o in transformOptions(entry.row, entry.depth)"
                      :key="o.value"
                      class="wb-opbtn"
                      :class="{ current: o.value === entry.row.transform, disabled: o.disabled }"
                      :disabled="o.disabled"
                      @click="chooseTransform(entry, o)"
                    >
                      <span>{{ o.label }}</span>
                      <small>{{ o.disabled ? o.reason : transformHint(o.value) }}</small>
                    </button>
                  </div>
                </template>

                <!-- 日期格式化：从预设里选 -->
                <template v-else-if="panel === 'date'">
                  <div class="wb-ops">
                    <button
                      v-for="p in DATE_PRESETS"
                      :key="p"
                      class="wb-opbtn"
                      :class="{ current: p === datePatternFor(entry.row) }"
                      @click="chooseDatePattern(entry.row, p)"
                    >
                      <span>{{ p }}</span>
                      <small>{{ datePatternExample(p) }}</small>
                    </button>
                  </div>
                  <div class="wb-panel-note">目标系统收到的就是按这个格式串格式化后的文本。</div>
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

                <!-- 枚举映射：表格加行、删行、改键改值 -->
                <template v-else-if="panel === 'enum'">
                  <table class="wb-enum">
                    <thead>
                      <tr><th>来源值</th><th /><th>目标值</th><th /></tr>
                    </thead>
                    <tbody>
                      <tr v-for="(pair, i) in enumPairList" :key="pair.id">
                        <td>
                          <el-input
                            :model-value="pair.key"
                            size="small"
                            placeholder="来源值"
                            @update:model-value="setEnumPair(i, { key: $event })"
                          />
                        </td>
                        <td class="wb-enum-arrow">→</td>
                        <td>
                          <el-input
                            :model-value="pair.value"
                            size="small"
                            placeholder="目标值"
                            @update:model-value="setEnumPair(i, { value: $event })"
                          />
                        </td>
                        <td>
                          <el-button size="small" text type="danger" @click="removeEnumRow(i)">删</el-button>
                        </td>
                      </tr>
                      <tr v-if="!enumPairList.length">
                        <td colspan="4" class="wb-enum-empty">还没有键值对，点「加一行」或「按样例补全」</td>
                      </tr>
                    </tbody>
                  </table>
                  <div class="wb-panel-row">
                    <el-button size="small" @click="addEnumRow">加一行</el-button>
                    <el-button size="small" :disabled="!missingEnumKeysOf.length" @click="fillEnumFromSamples">
                      按样例补全{{ sampleHint }}
                    </el-button>
                  </div>
                  <div class="wb-panel-note">
                    查不到键的来源值会变成空值（不报错）——样例里出现过的值最好都在表里。
                  </div>
                </template>

                <!-- 拼接：增删片段，每个片段选常量或来源字段 -->
                <template v-else-if="panel === 'concat'">
                  <div v-for="(part, i) in entry.row.parts" :key="i" class="wb-panel-row">
                    <el-select v-model="part.type" size="small" class="wb-part-kind">
                      <el-option value="const" label="常量" />
                      <el-option value="path" label="来源字段" />
                    </el-select>
                    <el-select
                      v-if="part.type === 'path'"
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
                    <el-input
                      v-else
                      v-model="part.value"
                      size="small"
                      class="wb-grow"
                      placeholder="常量片段，例如 BOM-"
                    />
                    <el-button size="small" text type="danger" @click="removePart(entry.row, i)">删</el-button>
                  </div>
                  <div class="wb-panel-row">
                    <el-button size="small" @click="addPart(entry.row, entry.depth > 0)">加片段</el-button>
                  </div>
                  <div class="wb-panel-note">片段按顺序拼起来；来源片段在数组映射的子行里用相对路径。</div>
                </template>
              </div>
            </template>
          </div>
        </section>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { EditorSourceSample, FieldMappingEditorContext, SourceStructure } from '../types'
import {
  configFromRows, flattenRows, mergeRowsWithTargets, nextEmptyRow, progressOf,
  rowReferenceFullLabel, rowReferenceLabel, rowsFromConfig, rowsFromTargetFields, targetFieldsFromPaths,
  type FlatRow, type SourceField, type TargetField,
} from '../mapping/editor-model.ts'
import { ENUM_PLACEHOLDER, isRowConfigured, realEnumMap, ruleFromRow, type ConcatPart, type MapRow } from '../mapping/rows.ts'
import { emptyRowLabel, fieldReferenceLabel, stepLabel } from '../mapping/labels.ts'
import {
  applyTransform, appendEnumSamples, datePatternFor, missingEnumKeys, setEnumKey,
  transformLabelOf, transformName, transformOptions, DATE_PRESETS, type EnumPair, type TransformOption,
} from '../mapping/transforms.ts'
import {
  appendChildRow, emptyPickResult, fillRowFromPick, sourcePickerFor, toggledRowIds,
  visibleRows, ELEMENT_GROUP_TITLE, type PickableItem,
} from '../mapping/source-picker.ts'

/** 转换下拉里每一项的一句说明（行号那条的说明由后端校验规则来）。 */
const TRANSFORM_HINTS: Record<string, string> = {
  STRING: '原值直接搬过去',
  INT: '转成数字，非数字会失败',
  DATE: '按模式输出日期字符串',
  ENUM: '来源值 → 目标值的查表替换',
  CONSTANT: '不取来源，写死一个值',
  CONCAT: '常量与来源字段按顺序拼接',
  INDEX: '数组映射内每个元素的行号',
}

/** 有参数要填的转换 → 它的面板；没有的（直接映射、数字转换、行号）不在这里。 */
const PANEL_KEYS: Partial<Record<string, PanelKind>> = {
  DATE: 'date',
  CONSTANT: 'const',
  ENUM: 'enum',
  CONCAT: 'concat',
}

const props = defineProps<{
  jobId: number
  jobName?: string
  actionId?: number | null
  /** 新建动作时它准备占用的序号（用来找链上更早的动作）。 */
  actionIndex?: number | null
  actionName?: string
  /** 打开时的已存配置（编辑已有动作）。 */
  config?: Record<string, any> | null
}>()

const emit = defineEmits<{ (e: 'saved', config: Record<string, any>): void; (e: 'closed'): void }>()

const visible = ref(true)
const loading = ref(false)
const saving = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/** 这个动作原来是手写 JSONata（mode=EXPRESSION）：这一轮看不了它，保存会替换掉。 */
const expressionMode = computed(() => props.config?.mode === 'EXPRESSION')

const context = ref<FieldMappingEditorContext | null>(null)
const rows = ref<MapRow[]>([])
const targets = ref<TargetField[]>([])
const targetSourceKey = ref<string>('')
const selectedId = ref<number | null>(null)
const defaultStep = ref<number>(0)
/** 收起子行的数组行（默认全部展开）。 */
const collapsedIds = ref<Set<number>>(new Set())

const actionNames = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  for (const a of context.value?.actions ?? []) {
    if (a.index != null) map[a.index] = a.name ?? ''
  }
  return map
})

/** 链上更早的动作都能当默认来源；任务输入（0）永远在选项里。 */
const stepOptions = computed(() => {
  const options: { step: number | null; label: string }[] = [{ step: 0, label: '任务输入' }]
  for (const a of context.value?.actions ?? []) {
    if (a.index == null || a.index <= 0) continue
    if (props.actionIndex != null && a.index >= props.actionIndex) continue
    options.push({ step: a.index, label: stepLabel(a.index, a.name) })
  }
  return options
})

const sourceTip = computed(() => {
  const sample = context.value?.source
  if (!sample) return '链上还没有更早的动作'
  if (!sample.fields.length) return `${stepLabel(sample.step, sample.stepName)} 还没有运行记录`
  return `来自 ${stepLabel(sample.step, sample.stepName)} 最近一次成功运行`
})

const targetTip = computed(() => {
  const target = context.value?.writeTarget
  if (!target) return '这个动作后面没有写库动作，目标字段要手工导入'
  if (!targets.value.length) return `写目标：${target.actionName ?? `动作 ${target.actionIndex}`}（结构还没探测出来）`
  return `目标：${target.actionName ?? `动作 ${target.actionIndex}`}${targetSourceKey.value ? ` · ${targetSourceKey.value}` : ''}`
})

/** 左边清单给什么字段，取决于**当前选中的这一行**（#101：数组行只给数组、子行给相对路径与外层）。 */
const pickerGroups = computed(() =>
  sourcePickerFor(selectedEntry.value?.row ?? null, sourceFields.value, selectedEntry.value?.parent ?? null, defaultStep.value))

/** 还没选行时先说清怎么开始。 */
const pickerTip = computed(() =>
  selectedEntry.value ? '点一下字段就填进选中的那一行' : '先点一行目标字段，再从这里选来源')

const progress = computed(() => progressOf(rows.value, targets.value))
const progressPercent = computed(() => (progress.value.total ? Math.round(progress.value.configured / progress.value.total * 100) : 0))

interface DisplayRow extends FlatRow {
  /** 铺出来的每一项的唯一键：分组标题与它上面那一行共用同一个 MapRow，键不能重。 */
  key: string
  /** 这一行的目标字段来自目标清单（而不是手输的）。 */
  fromTargets: boolean
  /** 数组行的「数组元素内」分组标题（铺在数组行与它的子行之间）。 */
  arrayHeader: boolean
}

/** 行在界面上按层级铺开：数组行后面紧跟「数组元素内」标题与它的子行（收起时只剩数组行）。 */
const displayRows = computed<DisplayRow[]>(() => {
  const out: DisplayRow[] = []
  for (const entry of visibleRows(rows.value, collapsedIds.value)) {
    out.push({
      ...entry,
      key: `r${entry.row.id}`,
      fromTargets: targetPaths.value.has(entry.row.target.trim()),
      arrayHeader: false,
    })
    // 有子行就补一条分组标题（#101：子行的来源默认按相对路径解析，标题上写明「数组元素内」）。
    if (entry.row.children.length) {
      out.push({
        row: entry.row, depth: entry.depth, parent: entry.parent,
        key: `h${entry.row.id}`, fromTargets: false, arrayHeader: true,
      })
    }
  }
  return out
})

/** 收起 / 展开一个数组行；「展开全部 / 收起全部」一次处理所有数组行。 */
function toggleCollapse(row: MapRow) {
  collapsedIds.value = toggledRowIds(rows.value, collapsedIds.value, row.id)
}

function toggleAllArrays() {
  collapsedIds.value = allCollapsed.value ? new Set() : toggledRowIds(rows.value, new Set())
}

/** 所有带子行的数组行都收起了吗（决定按钮上写「展开全部」还是「收起全部」）。 */
const allCollapsed = computed(() => {
  const arrays = flattenRows(rows.value).filter(entry => entry.row.children.length)
  return arrays.length > 0 && arrays.every(entry => collapsedIds.value.has(entry.row.id))
})

function isCollapsed(row: MapRow): boolean {
  return collapsedIds.value.has(row.id)
}

/** 目标清单里的字段路径（含数组内的相对路径），用来标出哪些行是清单给的。 */
const targetPaths = computed(() => {
  const paths = new Set<string>()
  const walk = (list: TargetField[]) => {
    for (const field of list) {
      paths.add(field.path)
      if (field.element?.length) walk(field.element)
    }
  }
  walk(targets.value)
  return paths
})

/** 选中的那一行（清单与参数面板都看它）。 */
const selectedEntry = computed<DisplayRow | null>(() =>
  displayRows.value.find(e => e.row.id === selectedId.value) ?? null)

async function load() {
  loading.value = true
  try {
    const query = new URLSearchParams()
    if (props.actionId != null) query.set('actionId', String(props.actionId))
    else if (props.actionIndex != null) query.set('actionIndex', String(props.actionIndex))
    const ctx = await api<FieldMappingEditorContext>(`/jobs/${props.jobId}/field-mapping-editor?${query.toString()}`)
    context.value = ctx
    defaultStep.value = ctx.source?.step ?? previousActionIndex(ctx)
    const saved = rowsFromConfig(props.config)
    targets.value = await loadTargets(ctx)
    rows.value = saved.length ? mergeRowsWithTargets(saved, targets.value, defaultStep.value) : rowsFromTargetFields(targets.value, defaultStep.value)
    selectFirst()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
  await nextTick()
  rootEl.value?.focus()
}

/** 默认来源步骤：链上更早的那个动作；没有更早的动作就是任务输入（0）。 */
function previousActionIndex(ctx: FieldMappingEditorContext): number {
  const earlier = (ctx.actions ?? [])
    .map(a => a.index)
    .filter((i): i is number => i != null)
    .filter(i => props.actionIndex == null || i < props.actionIndex)
  return earlier.length ? Math.max(...earlier) : 0
}

/** 目标字段清单：连库探测写目标那张表；探不出来就空着，编辑器退化成手写目标字段。 */
async function loadTargets(ctx: FieldMappingEditorContext): Promise<TargetField[]> {
  const target = ctx.writeTarget
  targetSourceKey.value = ''
  if (!target?.actionId) return []
  if (!target.tableName) {
    // 没配表名就直接说清楚：探测接口自己不知道要探哪张表（#98 H3：HTTP/MQ 做目标时没结构可探）。
    ElMessage.warning(`写目标动作「${target.actionName ?? `动作 ${target.actionIndex}`}」没有配表名，目标字段要自己填`)
    return []
  }
  try {
    const structure = await api<SourceStructure>(`/actions/${target.actionId}/probe-target`, {
      method: 'POST',
      body: JSON.stringify({ table: target.tableName, refresh: true }),
    })
    targetSourceKey.value = structure.sourceKey
    return targetFieldsFromPaths(structure.fields.map(f => f.name), requiredOf(structure))
  } catch (e) {
    // 探测失败（连不上库 / 表名不合法）不阻塞编辑器，退化成手写目标字段。
    ElMessage.warning(`目标结构没探测出来：${(e as ApiError).message}`)
    return []
  }
}

/** 必填口径：库里 NOT NULL 的列当成必填。 */
function requiredOf(structure: SourceStructure): Map<string, boolean> {
  const required = new Map<string, boolean>()
  for (const f of structure.fields ?? []) required.set(f.name, f.nullable === false)
  return required
}

async function reloadTargets() {
  const ctx = context.value
  if (!ctx) return
  loading.value = true
  try {
    targets.value = await loadTargets(ctx)
    rows.value = mergeRowsWithTargets(rows.value, targets.value, defaultStep.value)
    ElMessage.success(targets.value.length ? '目标字段已更新' : '没探测到目标字段')
  } finally {
    loading.value = false
  }
}

function sampleOf(item: PickableItem): string {
  const first = item.samples?.[0]
  if (first == null) return ''
  const text = typeof first === 'object' ? JSON.stringify(first) : String(first)
  return text.length > 18 ? `${text.slice(0, 18)}…` : text
}

function rowLabel(entry: FlatRow, isChild: boolean) {
  return rowReferenceLabel(entry.row, actionNames.value, isChild)
}

function rowFullLabel(entry: FlatRow) {
  const inherited = entry.parent?.sourceStep ?? null
  return rowReferenceFullLabel(entry.row, actionNames.value, inherited)
}

function select(row: MapRow) {
  selectedId.value = row.id
  // 换行就把上一行的参数面板收起来，免得面板留在别的行下面看着像它的。
  if (panelRowId.value !== null && panelRowId.value !== row.id) closePanel()
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

// ---------------------------------------------------------------- 就地参数编辑（#100）

type PanelKind = 'op' | 'date' | 'const' | 'enum' | 'concat'

const panel = ref<PanelKind | null>(null)
const panelRowId = ref<number | null>(null)

const PANEL_TITLES: Record<PanelKind, string> = {
  op: '这一行的值怎么取',
  date: '日期格式化',
  const: '常量',
  enum: '枚举映射',
  concat: '拼接',
}

const panelTitle = computed(() => (panel.value ? PANEL_TITLES[panel.value] : ''))

/** 当前面板所在的那一行（行 + 层级 + 父行），脚本里的操作都从这里取。 */
const panelEntry = computed<DisplayRow | null>(() =>
  displayRows.value.find(e => e.row.id === panelRowId.value) ?? null)

/** 当前打开面板的行。 */
const panelRow = computed<MapRow | null>(() => panelEntry.value?.row ?? null)

function openPanel(entry: DisplayRow, kind: PanelKind) {
  select(entry.row)
  panel.value = kind
  panelRowId.value = entry.row.id
}

function closePanel() {
  panel.value = null
  panelRowId.value = null
}

/** 转换下拉里每一项的一句说明。 */
function transformHint(kind: string): string {
  return TRANSFORM_HINTS[kind] ?? ''
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

function onStartAtChange(row: MapRow, value: number | undefined) {
  row.startAt = value == null ? 1 : Number(value)
}

/** 点来源格子：这一行就是"要填哪一行"，用户在左边清单点一个字段即可。 */
function pickSourceFor(entry: FlatRow) {
  select(entry.row)
}

/** 往数组行下面加一个子行（子行的目标字段写相对路径），加完就选中它。 */
function addChild(row: MapRow) {
  appendChildRow(row)
  // 新加的子行在最后：把它铺出来才能接着填目标字段。
  collapsedIds.value = new Set([...collapsedIds.value].filter(id => id !== row.id))
  const added = row.children[row.children.length - 1]
  if (added) selectedId.value = added.id
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

/** 拼接片段的显示：常量显示值本身，来源片段显示字段名（人话）。 */
function partLabel(entry: FlatRow, i: number): string {
  const part = entry.row.parts[i]
  if (!part) return '（空片段）'
  if (part.type === 'const') return part.value.trim() || '（空片段）'
  const label = transformLabelOf({ ...entry.row, transform: 'STRING', source: part.value },
    () => (entry.depth > 0 ? part.value : fieldLabelOf(entry, part.value)))
  return part.value.trim() ? label : '（空片段）'
}

/** 把一个来源路径按这一行的上下文显示成人话（数组子行里就是相对路径本身）。 */
function fieldLabelOf(entry: FlatRow, path: string): string {
  const step = entry.row.sourceStep ?? entry.parent?.sourceStep ?? defaultStep.value
  return fieldReferenceLabel({ step, path }, actionNames.value)
}

function isChild(entry: FlatRow): boolean {
  return entry.depth > 0
}

// ---- 拼接片段

function addPart(row: MapRow, inArray: boolean) {
  row.parts = [...row.parts, { type: inArray ? 'path' : 'const', value: '' } as ConcatPart]
}

function removePart(row: MapRow, index: number) {
  row.parts = row.parts.filter((_, i) => i !== index)
}

// ---- 枚举映射表：表格里改的每一格立刻写回行上的 enumMap（行是唯一副本，不另存一份编辑状态）

function enumPairsOf(row: MapRow): EnumPair[] {
  const map = row.enumMap ?? {}
  return Object.keys(map).map((key, i) => ({
    // 行的身份 = 它在表里的位置（键会随用户输入变，拿键当 key 会让正在输入的那行被重建）。
    id: i,
    key: key.startsWith(ENUM_PLACEHOLDER) ? '' : key,
    value: String(map[key] ?? ''),
  }))
}

/** 面板里编辑中的枚举表：读的是行，改的也是行。 */
const enumPairList = computed<EnumPair[]>(() => (panelRow.value ? enumPairsOf(panelRow.value) : []))

/** 表格里改了一格：把这一格并进 enumMap（值照抄，键按已填的算）。 */
function setEnumPair(index: number, patch: Partial<Pick<EnumPair, 'key' | 'value'>>) {
  const row = panelRow.value
  if (!row) return
  const base = enumPairList.value.map((pair, i) => (i === index ? { ...pair, ...patch } : pair))
  // 改键撞上已有的键时按「后改的赢」合并（setEnumKey 负责把被撞掉的那行去掉）。
  const pairs = patch.key === undefined ? base : setEnumKey(base, index, patch.key)
  row.enumMap = enumMapOf(pairs)
}

function enumMapOf(pairs: EnumPair[]): Record<string, any> {
  const map: Record<string, any> = {}
  pairs.forEach((pair, i) => {
    const key = pair.key.trim()
    // 空键的两条会互相覆盖，用占位键各占一个位置，等用户填上真键。
    map[key || `${ENUM_PLACEHOLDER}${i}`] = pair.value
  })
  return map
}

function addEnumRow() {
  const row = panelRow.value
  if (!row) return
  row.enumMap = enumMapOf([...enumPairList.value, { id: enumPairList.value.length, key: '', value: '' }])
}

function removeEnumRow(index: number) {
  const row = panelRow.value
  if (!row) return
  row.enumMap = enumMapOf(enumPairList.value.filter((_, i) => i !== index))
}

/** 这一行来源字段的样例值（枚举表「按样例补全」用；子行用相对路径，取元素里的那个字段）。 */
const panelSourceField = computed<SourceField | undefined>(() => {
  const entry = panelEntry.value
  if (!entry) return undefined
  const source = entry.row.source.trim()
  if (!source) return undefined
  if (entry.depth > 0) {
    const array = entry.parent?.source.trim() ?? ''
    return sourceFields.value.find(f => f.name === `${array}[0].${source}`)
  }
  return sourceFields.value.find(f => f.name === source)
})

/** 样例里出现过、枚举表里还没有的来源值（空键的占位行不算）。 */
const missingEnumKeysOf = computed(() =>
  missingEnumKeys(realEnumMap(panelRow.value?.enumMap), panelSourceField.value))

const sampleHint = computed(() => {
  const count = missingEnumKeysOf.value.length
  return count ? `（补 ${count} 个）` : ''
})

function fillEnumFromSamples() {
  const row = panelRow.value
  if (!row) return
  row.enumMap = appendEnumSamples(realEnumMap(row.enumMap), missingEnumKeysOf.value)
}

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

/** 当前来源步骤的字段清单（探测结果）。 */
const sourceFields = computed<SourceField[]>(() => context.value?.source?.fields ?? [])

/** 日期预设的样例（拿一个真日期格式化一遍，让人一眼看出这个模式长什么样）。 */
function datePatternExample(pattern: string): string {
  return formatExample(new Date(2026, 7, 21, 9, 5, 7), pattern)
}

/** 只支持后端那几个预设与常见日期记号；认不出来就原样返回模式串。 */
function formatExample(date: Date, pattern: string): string {
  const pad = (n: number, width = 2) => String(n).padStart(width, '0')
  const parts: Record<string, string> = {
    yyyy: String(date.getFullYear()),
    yy: String(date.getFullYear()).slice(-2),
    MM: pad(date.getMonth() + 1),
    dd: pad(date.getDate()),
    HH: pad(date.getHours()),
    mm: pad(date.getMinutes()),
    ss: pad(date.getSeconds()),
  }
  return pattern.replace(/yyyy|yy|MM|dd|HH|mm|ss/g, token => parts[token] ?? token)
}

function selectFirst() {
  const first = displayRows.value.find(e => e.row.target.trim())
  selectedId.value = first?.row.id ?? null
}

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

/** 切默认来源：空行跟着换步骤，左边字段清单也要换成那个动作的清单。 */
async function onDefaultStepChange(step: number) {
  defaultStep.value = step
  // 还没选来源的行跟着默认来源走（已配的行不动）。
  const walk = (list: MapRow[]) => {
    for (const row of list) {
      if (!row.source.trim()) row.sourceStep = step
      if (row.children.length) walk(row.children)
    }
  }
  walk(rows.value)
  await loadSourceFields(step)
}

/** 重新取某个动作的字段清单（换默认来源时必须换，否则会把 A 的字段配到 B 的步骤上）。 */
async function loadSourceFields(step: number) {
  loading.value = true
  try {
    const sample = await api<EditorSourceSample>(`/jobs/${props.jobId}/field-mapping-editor/source-fields?step=${step}`)
    if (context.value) context.value = { ...context.value, source: sample }
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}

/** 上下键在行之间移动（#99 验收）。挂窗口监听：焦点可能停在行里的任意输入框上。 */
function onKeydown(e: KeyboardEvent) {
  if (!visible.value || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) return
  const list = displayRows.value.filter(entry => entry.row.target.trim())
  if (!list.length) return
  const current = list.findIndex(entry => entry.row.id === selectedId.value)
  const nextIndex = e.key === 'ArrowDown'
    ? Math.min(current + 1, list.length - 1)
    : Math.max(current - 1, 0)
  selectedId.value = list[nextIndex].row.id
  e.preventDefault()
  nextTick(() => {
    document.querySelector(`[data-row-id="${selectedId.value}"]`)?.scrollIntoView({ block: 'nearest' })
  })
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

/** 配置 → 规则（编辑器保存时用；一行就是一条规则，只管配好的行）。 */
function getConfig(): Record<string, any> {
  return configFromRows(rows.value)
}

/** 配好的行数：没配好的行留在编辑器里继续填，但不进规则（进了后端会整份拒掉）。 */
const configuredCount = computed(() => rows.value.filter(isRowConfigured).length)

/** 有目标字段、但参数还差一口的行（常量没填值、枚举表空、拼接没片段、没选来源）。 */
const untouchedCount = computed(() =>
  rows.value.filter(row => row.target.trim() !== '' && !isRowConfigured(row)).length)

async function save() {
  if (expressionMode.value) {
    try {
      await ElMessageBox.confirm(
        '这个动作现在用的是手写 JSONata 表达式。新版逐行编辑器还看不了它，保存会用逐行规则替换掉它。',
        '会替换手写表达式',
        { type: 'warning', confirmButtonText: '替换并保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  if (!configuredCount.value) {
    ElMessage.warning(rows.value.some(r => r.target.trim())
      ? '还没有配好的字段映射——每个目标字段至少要选一个来源，常量要填一个值，枚举表要有一条，拼接要有片段'
      : '还没有任何目标字段——改一个写目标动作，或者先点「重新探测目标字段」，也可以直接手输目标字段路径')
    return
  }
  if (untouchedCount.value) {
    const ok = await ElMessageBox.confirm(
      `还有 ${untouchedCount.value} 行没配好（没选来源 / 常量没填值 / 枚举表空 / 拼接没片段）。这些行不会被保存。`,
      '有没配好的行',
      { type: 'warning', confirmButtonText: '只保存配好的', cancelButtonText: '回去补' },
    ).then(() => true).catch(() => false)
    if (!ok) return
  }
  saving.value = true
  try {
    emit('saved', getConfig())
    visible.value = false
  } finally {
    saving.value = false
  }
}

function close() {
  visible.value = false
  emit('closed')
}

defineExpose({ getConfig, load })

load()
</script>

<style scoped>
.wb { display: flex; flex-direction: column; height: 100vh; outline: none; }
.wb-head {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  padding: 10px 16px; border-bottom: 1px solid #e5e7eb; background: #fff;
}
.wb-title { display: flex; align-items: baseline; gap: 8px; }
.wb-sub { font-size: 12px; color: #6b7280; }
.wb-head-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.wb-label { font-size: 12px; color: #6b7280; }
.wb-step-select { width: 200px; }
.wb-progress { width: 220px; display: flex; align-items: center; gap: 8px; }
.wb-progress-text { font-size: 12px; color: #6b7280; white-space: nowrap; }

.wb-body { display: flex; flex: 1; min-height: 0; }
.wb-source {
  width: 280px; flex-shrink: 0; border-right: 1px solid #e5e7eb;
  display: flex; flex-direction: column; padding: 10px; min-height: 0;
}
.wb-panel-title { font-size: 13px; font-weight: 600; color: #111827; }
.wb-panel-tip { font-size: 11px; color: #9ca3af; margin: 2px 0 8px; }
.wb-source-list { overflow: auto; flex: 1; }
.wb-source-group + .wb-source-group { margin-top: 10px; }
.wb-source-group-title { font-size: 12px; color: #374151; font-weight: 600; margin-bottom: 4px; }
.wb-source-group-tip { font-size: 11px; color: #b45309; margin-bottom: 4px; }
.wb-source-item { padding: 5px 6px; border-radius: 4px; cursor: pointer; }
.wb-source-item:hover { background: #f3f4f6; }
.wb-source-item.array-field .wb-source-name { color: #7c3aed; }
.wb-source-item-main { display: flex; align-items: center; gap: 6px; }
.wb-source-name { font-family: Consolas, monospace; font-size: 12px; }
.wb-source-meta { font-size: 11px; color: #9ca3af; margin-top: 2px; }
.wb-chip { font-size: 10px; color: #6b7280; border: 1px solid #d1d5db; border-radius: 3px; padding: 0 4px; }
.wb-chip.used { color: #0369a1; border-color: #bae6fd; }

.wb-rows { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 10px 16px; min-height: 0; }
.wb-rows-head { display: flex; align-items: center; gap: 10px; }
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
.wb-col-value { flex: 1; min-width: 0; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.wb-source-value { font-size: 13px; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wb-child-hint { font-size: 11px; color: #9ca3af; }
.wb-empty { font-size: 12px; color: #9ca3af; padding: 10px 0; }
.wb-alert { margin: 8px 16px 0; }

/* 数组行的「数组元素内」分组标题：展开 / 收起子行就在这里 */
.wb-group { display: flex; align-items: center; gap: 8px; margin: 4px 0 2px 24px; }
.wb-group-title { font-size: 11px; color: #6b7280; font-weight: 600; }
.wb-group-note { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }

/* 一行里能点的那些小块：点一下就就地改，不开弹窗 */
.wb-chip {
  font: inherit; font-size: 12px; line-height: 18px; padding: 1px 6px;
  border: 1px solid #d1d5db; border-radius: 4px; background: #fff; color: #111827;
  cursor: pointer; max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wb-chip:hover { border-color: #409eff; color: #409eff; }
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
.wb-paren { font-size: 12px; color: #6b7280; }
.wb-start-at { width: 96px; }

/* 就地参数面板：占这一行下面的一行 */
.wb-panel {
  margin: 2px 0 8px 8px; padding: 8px 10px;
  border: 1px solid #dbeafe; border-radius: 6px; background: #f8fafc;
}
.wb-panel-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.wb-panel-target { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }
.wb-spacer { flex: 1; }
.wb-panel-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.wb-panel-note { font-size: 11px; color: #9ca3af; }
.wb-grow { flex: 1; min-width: 200px; }
.wb-part-kind { width: 120px; flex-shrink: 0; }
.wb-ops { display: flex; gap: 6px; flex-wrap: wrap; }
.wb-opbtn {
  font: inherit; text-align: left; min-width: 150px; padding: 4px 8px;
  border: 1px solid #d1d5db; border-radius: 4px; background: #fff; cursor: pointer;
  display: flex; flex-direction: column; gap: 1px;
}
.wb-opbtn small { font-size: 10px; color: #9ca3af; }
.wb-opbtn:hover:not(.disabled) { border-color: #409eff; }
.wb-opbtn.current { border-color: #409eff; background: #ecf5ff; }
.wb-opbtn.disabled { cursor: not-allowed; background: #f3f4f6; color: #9ca3af; }
.wb-opbtn.disabled small { color: #b45309; }
.wb-enum { border-collapse: collapse; width: 100%; max-width: 620px; }
.wb-enum th { font-size: 11px; color: #6b7280; text-align: left; font-weight: 500; padding: 2px 4px; }
.wb-enum td { padding: 2px 4px; }
.wb-enum-arrow { color: #9ca3af; font-size: 12px; }
.wb-enum-empty { font-size: 11px; color: #9ca3af; padding: 6px 4px; }

@media (max-width: 900px) {
  .wb-source { width: 200px; }
  .wb-col-target { width: 180px; }
  .wb-chip { max-width: 200px; }
}
</style>
