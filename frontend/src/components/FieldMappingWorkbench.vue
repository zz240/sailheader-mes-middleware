<template>
  <el-drawer
    v-model="visible"
    direction="rtl"
    size="100%"
    :with-header="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
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
          <!-- 手写表达式这一边没有「默认来源」也没有必填进度：那边只有一段表达式 -->
          <template v-if="rowsMode">
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
          </template>
          <span v-else class="wb-chip chip-mode" title="这个动作保存的是一段手写 JSONata 表达式">手写 JSONata</span>

          <el-button size="small" @click="close">取消</el-button>
          <el-tooltip content="把这份草稿交给后端判一遍：有没有错、错在哪一行，顺带看编译产物" placement="bottom">
            <el-button size="small" :loading="validating" @click="validateConfig()">校验配置</el-button>
          </el-tooltip>
          <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
          <!-- 「高级：手写 JSONata」（#106）：不显眼但可达。深层数组的任意重排只能走它，
               所以它一直在（票里那句话）。 -->
          <el-button
            v-if="rowsMode"
            size="small"
            text
            class="wb-advanced"
            @click="openExpression()"
          >高级：手写 JSONata</el-button>
        </div>
      </header>

      <!-- 逐行配置这一边：左来源清单 + 右逐行配置 -->
      <el-alert
        v-if="rowsMode && storedExpression"
        class="wb-alert"
        type="warning"
        :closable="false"
        title="这个动作原来配的是手写 JSONata 表达式"
        :description="expressionReplaced()"
      />
      <div v-if="rowsMode" class="wb-body">
        <!-- 左边来源清单（#117 拆出去）：给哪些字段看当前选中的那一行，点一项就填进这一行 -->
        <MappingSourcePanel
          ref="sourcePanel"
          :source-tip="sourceTip"
          :loading="loading"
          @pick="pick"
        />

        <section class="wb-rows">
          <div class="wb-rows-head">
            <div class="wb-panel-title">目标字段</div>
            <div class="wb-panel-tip">{{ targetTip }}</div>
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
            <el-button size="small" text type="primary" @click="reloadTargets">重新探测目标字段</el-button>
          </div>

          <!-- 校验结果（#103）：点「校验配置」才有；每条问题点一下就跳到对应的行 -->
          <MappingIssuesPanel
            v-if="validation"
            :issues="locatedIssues"
            :summary="issueSummary"
            :has-errors="validation.errors.length > 0"
            @jump="jumpToRowId"
          />

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
                     三种状态都由后端返回的问题算出来，前端不自己判对错。 -->
                <div v-if="validation" class="wb-col-issues" :title="issueText(entry.row.id)">
                  <span class="wb-state" :class="rowStateOf(entry.row.id)">{{ stateLabel(entry.row.id) }}</span>
                  <span v-if="firstMessageOf(entry.row.id)" class="wb-issue-inline">
                    {{ firstMessageOf(entry.row.id) }}
                  </span>
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
      </div>

      <!-- 手写 JSONata 这一边（#106）：整段表达式就是这次映射要执行的东西（与后端 mode=EXPRESSION 一致）。
           表达式的正文仍存在工作台这一层（保存与送校验读的都是它），子组件只负责把它画出来、改它。 -->
      <MappingExpressionEditor
        v-else
        v-model="expressionText"
        :has-validation="validation != null"
        :has-errors="(validation?.errors.length ?? 0) > 0"
        :summary="issueSummary"
        :issues="expressionIssues"
        @switch-back="switchBackToRows"
      />

      <!-- 底部编译产物（#103）：两个标签页，JSONata 与规则 JSON -->
      <div v-if="validation" class="wb-foot">
        <el-tabs v-model="artifactTab" class="wb-artifacts">
          <el-tab-pane label="JSONata" name="jsonata">
            <pre class="wb-artifact">{{ validation.compiled.jsonata || '这份草稿还没编译出表达式——先把错误改掉' }}</pre>
          </el-tab-pane>
          <el-tab-pane label="规则 JSON" name="rules">
            <pre class="wb-artifact">{{ validation.compiled.rulesJson || '（没有内容）' }}</pre>
          </el-tab-pane>
        </el-tabs>
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
  configFromRows, flattenRows, mergeRowsWithTargets, progressOf, rowsFromConfig, rowsFromTargetFields,
  targetCandidatesFor, targetFieldsFromPaths, type FlatRow, type SourceField, type TargetField,
} from '../mapping/editor-model.ts'
import { autoSourceNotices } from '../mapping/fast-config.ts'
import {
  hasMapping, isRowConfigured, markHumanEdited, type ConcatPart, type MapRow,
} from '../mapping/rows.ts'
import { emptyRowLabel, stepLabel } from '../mapping/labels.ts'
import {
  datePatternFor, normalizeDatePattern, transformLabelFor, transformLabelOf, transformName,
  transformOptions,
} from '../mapping/transforms.ts'
import { ELEMENT_GROUP_TITLE } from '../mapping/source-picker.ts'
import {
  draftRequest, firstErrorRowId, issuesByRow, locateIssues, rowIssueState,
  type DraftValidation,
} from '../mapping/draft.ts'
import {
  editorModeOf, expressionConfig, expressionOf, expressionReplacedNotice, expressionSkipNotice,
  hasExpression, type EditorMode,
} from '../mapping/expression.ts'
import { keyIntent, rowStepIndex } from '../mapping/keyboard.ts'
import {
  PART_KINDS, applyPartKind, canUsePartKind, partEmpty, partKindLabel, partKindOf, partLabelSpec,
} from '../mapping/parts.ts'
import {
  createWorkbenchState, provideWorkbenchState, type DisplayRow,
} from '../mapping/workbench-state.ts'
import MappingDateParams from './MappingDateParams.vue'
import MappingEnumParams from './MappingEnumParams.vue'
import MappingExpressionEditor from './MappingExpressionEditor.vue'
import MappingIndexParams from './MappingIndexParams.vue'
import MappingIssuesPanel from './MappingIssuesPanel.vue'
import MappingSourcePanel from './MappingSourcePanel.vue'

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
const validating = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/** 底部编译产物那两个标签页。 */
const artifactTab = ref('jsonata')

/**
 * 编辑器里那些**跨组件共用**的状态（行、选中、参数面板、收起集合、清单）：建一份，provide 给子组件。
 *
 * 左边来源清单、行列表、问题面板拆出去之后（#117），这几样是它们与键盘那一段共用的：一处状态源比
 * 一层层 props / emit 传下去短得多，也不会出现两个副本各自记着「选中的是哪一行」。
 * 状态的来龙去脉与「行上的操作」都在 `mapping/workbench-state.ts`。
 */
const state = createWorkbenchState()
provideWorkbenchState(state)
const {
  rows, targets, sourceFields, selectedId, defaultStep, collapsedIds, clearedRows, panel, panelRowId,
  optionIndex, targetQuery, typingTargetRowId, actionNames, validation,
  displayRows, rowView, selectedEntry, panelEntry, panelTitle, allCollapsed,
  select, isCollapsed, toggleCollapse, toggleAllArrays,
  closePanel, openPanel, transformHint, chooseTransform, chooseDatePattern,
  walkPanelOption, confirmPanelOption,
  clearRow, undoClear, canUndo, clearHint, jumpToRowId, jumpToNextMatch,
  focusRow, addLeafRow, addArrayRow, addChild, addIndexChild, autoMatch, pick,
  rowLabel, rowFullLabel, fieldLabelOf,
} = state

/** 左边来源清单那一块（「走清单」那一段的状态归它，这里只按键盘的意图调它）。 */
const sourcePanel = ref<InstanceType<typeof MappingSourcePanel> | null>(null)

/** 编辑器上下文（哪一步能当来源、写目标是谁），以及正在编辑的动作那一条。 */
const context = ref<FieldMappingEditorContext | null>(null)
const targetSourceKey = ref<string>('')

/**
 * 这个编辑器现在在哪一边（#106）：逐行配置，还是手写 JSONata。
 *
 * 打开时按已存配置定（`mode=EXPRESSION` 就直接进手写这一边）——以前是给一条「这一轮看不了它」的
 * 警告，那等于把已经配好的表达式藏起来，只有删掉重配一条路。两边读写的是同一份数据格式（#98 C1），
 * 所以随时能来回切。
 */
const editorMode = ref<EditorMode>(editorModeOf(props.config))
const expressionText = ref(expressionOf(props.config))
/** 现在在逐行配置这一边（手写那一边没有「行」这套东西，跟行有关的判断都看它）。 */
const rowsMode = computed(() => editorMode.value === 'rows')
/** 已存配置里本来就有一段手写表达式（切回逐行后要说清它会被替换）。 */
const storedExpression = computed(() => hasExpression(expressionOf(props.config)))
const expressionReplaced = () => expressionReplacedNotice()

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

/** 左边来源清单（搜索、三档过滤、已用高亮与计数、键盘「走清单」那一段）在 `MappingSourcePanel.vue`（#117）。 */

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

const progress = computed(() => progressOf(rows.value, targets.value))
const progressPercent = computed(() => (progress.value.total ? Math.round(progress.value.configured / progress.value.total * 100) : 0))

// 行的铺开、展开/收起、选中、新增行、清除/撤销、参数面板那一套都在 `mapping/workbench-state.ts`（#117）。

// ---------------------------------------------------------------- 手工新增行（#102）

/** 目标字段的补全候选：清单里跟这一行形状对得上的字段（手输的路径不走这份清单）。 */
function targetSuggestions(entry: DisplayRow, query: string): Record<string, any>[] {
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

function onTargetInput(entry: DisplayRow) {
  typingTargetRowId.value = entry.row.id
}

/** 补全要的是「给一个查询词、回一份候选」的函数，而清单按行给，所以每行现取一份。 */
function suggestFor(entry: DisplayRow) {
  return (query: string, cb: (items: Record<string, any>[]) => void) => cb(targetSuggestions(entry, query))
}

// ---------------------------------------------------------------- 加载（上下文、目标清单、来源清单）

async function load() {
  loading.value = true
  try {
    const query = new URLSearchParams()
    if (props.actionId != null) query.set('actionId', String(props.actionId))
    else if (props.actionIndex != null) query.set('actionIndex', String(props.actionIndex))
    const ctx = await api<FieldMappingEditorContext>(`/jobs/${props.jobId}/field-mapping-editor?${query.toString()}`)
    context.value = ctx
    actionNames.value = actionNamesOf(ctx)
    sourceFields.value = ctx.source?.fields ?? []
    defaultStep.value = ctx.source?.step ?? previousActionIndex(ctx)
    const saved = rowsFromConfig(props.config)
    targets.value = await loadTargets(ctx)
    rows.value = saved.length ? mergeRowsWithTargets(saved, targets.value, defaultStep.value) : rowsFromTargetFields(targets.value, defaultStep.value)
    // 行是重铺出来的（id 全换了），上一次打开留下的撤销快照跟着作废。
    clearedRows.value = new Map()
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

/** 动作序号 → 动作名（行与片段上那些人话要用；由上下文给）。 */
function actionNamesOf(ctx: FieldMappingEditorContext): Record<number, string> {
  const map: Record<number, string> = {}
  for (const a of ctx.actions ?? []) {
    if (a.index != null) map[a.index] = a.name ?? ''
  }
  return map
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
    return targetFieldsFromPaths(structure.fields.map(f => f.name), requiredOf(structure), typesOf(structure))
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

/** 类型口径：探测给什么带什么（清单上要显示它，下一张票的类型检查也要用它）。 */
function typesOf(structure: SourceStructure): Map<string, string> {
  const types = new Map<string, string>()
  for (const f of structure.fields ?? []) {
    if (f.type) types.set(f.name, f.type)
  }
  return types
}

async function reloadTargets() {
  const ctx = context.value
  if (!ctx) return
  loading.value = true
  try {
    targets.value = await loadTargets(ctx)
    rows.value = mergeRowsWithTargets(rows.value, targets.value, defaultStep.value)
    // 已配的行按目标字段沿用同一个行对象（含 id），所以撤销快照能留就留着；重铺掉的那些行
    // 换了 id，快照按 id 找不到就自然不显示，不用特意清。
    ElMessage.success(targets.value.length ? '目标字段已更新' : '没探测到目标字段')
  } finally {
    loading.value = false
  }
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

// ---------------------------------------------------------------- 校验配置（#103）

/**
 * 点「校验配置」：把草稿交给后端那个只读接口判。
 *
 * 判断对错全在那边，这里一条校验规则都没有——发出去的是「按现在这样保存会写出去的那一份」，
 * 收回来的是问题列表和编译产物，前端只负责把每条问题挂到对应的那一行上（`draft.ts`）。
 * 手写表达式那一边（#106）发的是 `{mode: 'EXPRESSION', expression, rules: []}`：后端按 `mode`
 * 分岔，EXPRESSION 下不校验规则树——这正是界面上写着的那句「这条路跳过规则树校验」。
 * 返回 null 表示这次没跑起来（网络或服务端错误），调用方据此决定要不要继续。
 */
async function validateConfig(): Promise<DraftValidation | null> {
  validating.value = true
  try {
    const result = await api<DraftValidation>('/field-mapping-editor/validate', {
      method: 'POST',
      body: JSON.stringify(draftRequest({
        rows: rows.value,
        targets: targets.value,
        sourceFields: sourceFields.value,
        // 左边清单是哪一步的探测结果，后端就拿它判哪一步的来源路径
        sourceFieldsStep: context.value?.source?.step ?? null,
        earlierActionIndexes: earlierActionIndexes.value,
        defaultSourceStep: defaultStep.value,
        actionIndex: props.actionIndex ?? null,
        draft: editorMode.value === 'expression' ? expressionConfig(expressionText.value) : undefined,
      })),
    })
    validation.value = result
    return result
  } catch (e) {
    ElMessage.error(`校验没跑起来：${(e as ApiError).message}`)
    return null
  } finally {
    validating.value = false
  }
}

/** 链上执行顺序更早的动作序号（顶部那个下拉去掉「任务输入」）：sourceStep 只能指向这些。 */
const earlierActionIndexes = computed(() =>
  stepOptions.value.map(o => o.step ?? 0).filter(step => step > 0))

/**
 * 「自动匹配同名」填上的那些行，校验时要给的那条提示（#105）。
 *
 * 由前端自己出：后端那份草稿里没有「这一行是自动填的」这个信息，也不该有——自动与否是编辑器里的
 * 本地事实。所以这些提示挂进同一个问题列表，但**不改后端返回的内容**（`validation` 还是原来那份）。
 */
const autoNotices = computed(() => autoSourceNotices(rows.value))

/** 后端的问题 + 前端那几条提醒：界面上一视同仁地列出来、按同一套口径定位到行。 */
const mergedValidation = computed<DraftValidation | null>(() => {
  const result = validation.value
  if (!result) return null
  // 手写表达式那一边没有行，「自动匹配填上的行」那条提醒也不该跟过去
  if (editorMode.value === 'expression') return result
  return {
    ...result,
    warnings: [...result.warnings, ...autoNotices.value],
  }
})

/** 手写这一边的问题列表：没有「哪一行」可言，只按错误在前列原文。 */
const expressionIssues = computed(() => {
  const result = mergedValidation.value
  if (!result) return []
  return [
    ...result.errors.map(issue => ({ severity: 'error' as const, message: issue.message })),
    ...result.warnings.map(issue => ({ severity: 'warning' as const, message: issue.message })),
  ]
})

/** 后端返回的问题 + 前端提醒 → 界面上的问题列表（每条带上它落在哪一行）。 */
const locatedIssues = computed(() => (mergedValidation.value
  ? locateIssues(flattenRows(rows.value), mergedValidation.value)
  : []))

/** 每一行的问题（键是行 id）：行右侧那三种状态用它算。 */
const rowIssues = computed(() => issuesByRow(locatedIssues.value))

const issueSummary = computed(() => {
  const result = mergedValidation.value
  if (!result) return ''
  if (!result.errors.length && !result.warnings.length) return '没有发现问题'
  return `${result.errors.length} 个错误 · ${result.warnings.length} 个警告`
})

/** 「有错 / 有警告 / 没问题」——#103 要求界面上分得开这三种。 */
const STATE_LABELS: Record<'error' | 'warning' | 'ok', string> = {
  error: '有错',
  warning: '有警告',
  ok: '没问题',
}

function rowStateOf(rowId: number): 'error' | 'warning' | 'ok' {
  return rowIssueState(rowIssues.value.get(rowId))
}

function stateLabel(rowId: number): string {
  return STATE_LABELS[rowStateOf(rowId)]
}

/** 这一行第一条问题的原文（行里显示一条，剩下的挂在悬停提示上）。 */
function firstMessageOf(rowId: number): string {
  return rowIssues.value.get(rowId)?.[0]?.message ?? ''
}

function issueText(rowId: number): string {
  return (rowIssues.value.get(rowId) ?? []).map(issue => issue.message).join('\n')
}

// ---------------------------------------------------------------- 就地参数编辑（#100）

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

/** 点来源格子：这一行就是"要填哪一行"，用户在左边清单点一个字段即可。 */
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

// ---- 枚举映射表：组件里改的每一格立刻写回行上的 enumMap（行是唯一副本，不另存一份编辑状态）

function onEnumMapChange(row: MapRow, map: Record<string, any>) {
  row.enumMap = map
  markHumanEdited(row)
}

/**
 * 一个来源路径在这份清单里的那条样例（枚举表「按样例补全」用）。
 *
 * 数组子行里写的是相对路径，要去元素字段里找（`items[0].<路径>`）。
 */
function sourceFieldOf(entry: DisplayRow | FlatRow | null, path: string): SourceField | undefined {
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
  // 子行的来源是相对当前路径的元素路径，只能从组来源数组的元素字段里挑。
  const array = entry.parent?.source ?? ''
  if (!array) return fields
  const prefix = `${array.replace(/\[0\]$/, '')}[0].`
  const relative = fields
    .filter(f => f.name.startsWith(prefix))
    .map(f => ({ ...f, name: f.name.slice(prefix.length) }))
  return relative.length ? relative : fields.filter(f => !f.name.includes('.'))
}

function selectFirst() {
  const first = displayRows.value.find(e => e.row.target.trim())
  selectedId.value = first?.row.id ?? null
}

/** 切默认来源：空行跟着换步骤，左边字段清单也要换成那个动作的清单。 */
async function onDefaultStepChange(step: number) {
  defaultStep.value = step
  // 还没选来源的行跟着默认来源走（已配的行不动）。
  const walk = (list: MapRow[]) => {
    for (const row of list) {
      if (!row.source.trim()) {
        row.sourceStep = step
        // 人主动换了来源步骤：这一行不再算「自动填上的那一行」（#105）。
        markHumanEdited(row)
      }
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
    sourceFields.value = sample.fields ?? []
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- 键盘（#99 上下走行 / #106 补齐其余）

/**
 * 键盘上的每一个键要做什么，全在 `keyboard.ts` 里定（那是一份能用 node 直接跑的检查）；
 * 这里只把意图落到界面上：选中哪一行、焦点给谁、开哪个面板。
 *
 * 挂窗口监听、走**捕获阶段**（`{ capture: true }`）：焦点可能停在抽屉里的任意地方，而键盘操作
 * 不该受它影响；更要紧的是「目标字段那个输入框里到底算不算在输入里」要看**按下那一刻**的下拉状态
 * ——el-autocomplete 自己也在 input 上处理这些键，冒泡阶段再问就晚了（它按完回车已经把下拉收了，
 * 我们这边就会当成「下拉关着」，于是同一次回车既选中了补全项、又开了「选来源」；真机实测踩到过）。
 */
function onKeydown(e: KeyboardEvent) {
  if (!visible.value) return
  // 手写表达式那一边没有行、没有来源清单：那边只有一段文本，键盘全归输入框
  if (editorMode.value === 'expression') return
  // 确认框开着时不接管：那一刻键盘归那个框（Enter 是「确定」，退格不该去清某一行）
  if (document.querySelector('.el-message-box')) return
  const intent = keyIntent(e.key, {
    inTextEntry: inTextEntryFor(e.key, e.target),
    hasSelection: selectedId.value != null,
    pickingSource: sourcePanel.value?.picking === true,
    panel: panel.value,
  })
  if (!intent) return
  e.preventDefault()
  switch (intent.kind) {
    case 'move':
      moveSelection(intent.delta)
      return
    case 'transform':
      openTransformPanel()
      return
    case 'pick-source':
      startPickingSource()
      return
    case 'clear':
      clearSelectedRow()
      return
    case 'leave':
      sourcePanel.value?.endPick()
      return
    case 'close-panel':
      closePanel()
      return
    case 'walk-item':
      sourcePanel.value?.movePick(intent.delta)
      return
    case 'confirm-pick':
      sourcePanel.value?.confirmPick()
      return
    case 'walk-option':
      walkPanelOption(intent.delta)
      return
    case 'confirm-option':
      confirmPanelOption()
      return
  }
}

/** 上下走行：只在铺出来的、有目标字段的行之间走（从哪头进由 `rowStepIndex` 定）。 */
function moveSelection(delta: -1 | 1) {
  const list = displayRows.value.filter(entry => entry.row.target.trim())
  if (!list.length) return
  const current = list.findIndex(entry => entry.row.id === selectedId.value)
  selectedId.value = list[rowStepIndex(current, delta, list.length)].row.id
  nextTick(() => {
    document.querySelector(`[data-row-id="${selectedId.value}"]`)?.scrollIntoView({ block: 'nearest' })
  })
}

/** `/` 换转换：打开选中这一行的转换面板（键盘停在它现在用的那一种上，由 `openPanel` 摆好）。 */
function openTransformPanel() {
  const entry = selectedEntry.value
  if (!entry) return
  openPanel(entry, 'op')
}

/** 回车选来源：进「走清单」这一段（清单那一侧自己把焦点收进去、停在第一项上）。 */
function startPickingSource() {
  const entry = selectedEntry.value
  if (!entry) return
  if (entry.row.iterate !== 'FOR_EACH' && (entry.row.transform === 'CONSTANT' || entry.row.transform === 'INDEX')) {
    ElMessage.warning(entry.row.transform === 'CONSTANT'
      ? '这一行是常量：不取来源，填的是那个写死的值'
      : '这一行是行号：来源就是行号本身，不用选来源字段')
    return
  }
  const panel = sourcePanel.value
  if (!panel?.itemCount) {
    ElMessage.warning(panel?.emptyText ?? '')
    return
  }
  closePanel()
  panel.beginPick()
}

/** 退格 / 删除：清除选中这一行的取值方式（和行里的「清除」是同一件事，清除后照样能撤销）。 */
function clearSelectedRow() {
  const entry = selectedEntry.value
  if (!entry || !hasMapping(entry.row)) return
  clearRow(entry)
}

/** 这个事件发生在某个输入框 / 下拉框里吗。 */
function isTextEntry(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  const tag = el?.tagName?.toLowerCase() ?? ''
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el?.isContentEditable === true
}

/**
 * 这个键在这个焦点位置上算不算「在输入里」（算了就一个键都不抢）。
 *
 * 一处例外（#106 收掉 #102 留下的那条）：**目标字段**那个单行输入框里，补全下拉没开着时
 * `↑↓` 与回车照样归界面——`↑↓` 走行、回车进「选来源」这一段。理由：加完一行光标就落在这个输入框里，
 * 而「走行 / 选来源」正是键盘配一张清单的两步；这两个键在一个单行输入框里本来也没有别的用处
 * （下拉开着时归 el-autocomplete 换高亮 / 选中，所以那时让开）。
 *
 * 别的输入框一律不抢：搜索框的回车有它自己的活（跳行 / 补全），行号、常量、拼接片段里
 * `↑↓` 与回车都是输入的一部分，文本域（含手写表达式那一整段）更是一个键都不能动。
 */
function inTextEntryFor(key: string, target: EventTarget | null): boolean {
  if (!isTextEntry(target)) return false
  if (key !== 'ArrowUp' && key !== 'ArrowDown' && key !== 'Enter') return true
  const el = target as HTMLElement
  if (el.tagName?.toLowerCase() !== 'input' || !el.closest('.wb-col-target')) return true
  return hasOpenAutocomplete()
}

/**
 * 目标字段的补全下拉开着吗（开着时这些键归它）。
 *
 * 两个坑：① 补全是 teleport 到 body 的，每个输入框一个 popper，**只看 `querySelector` 拿到的第一个
 * 会认到别行那个关着的**——要看「有没有任何一个正铺着」；② 没有候选时 element-plus 自己就把
 * `visible` 关掉（`suggestionVisible = 候选数 > 0 && activated`），所以「铺着」就等于「有候选」。
 */
function hasOpenAutocomplete(): boolean {
  return [...document.querySelectorAll<HTMLElement>('.el-autocomplete-suggestion')]
    .some(el => el.offsetParent !== null)
}

onMounted(() => window.addEventListener('keydown', onKeydown, { capture: true }))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown, { capture: true }))

/**
 * 配置 → 保存会写出去的那一份（编辑器保存时用）。
 *
 * 逐行这一边：一行一条规则，只管配好的行。手写那一边：整段表达式就是这份配置
 * （与后端 `mode=EXPRESSION` 的约定一致，规则树不参与）。
 */
function getConfig(): Record<string, any> {
  return editorMode.value === 'expression'
    ? expressionConfig(expressionText.value)
    : configFromRows(rows.value)
}

/** 配好的行数：没配好的行留在编辑器里继续填，但不进规则（进了后端会整份拒掉）。 */
const configuredCount = computed(() => rows.value.filter(isRowConfigured).length)

/** 有目标字段、但参数还差一口的行（常量没填值、枚举表空、拼接没片段、没选来源）。 */
const untouchedCount = computed(() =>
  rows.value.filter(row => row.target.trim() !== '' && !isRowConfigured(row)).length)

/** 「自动匹配同名」填上、还没被人碰过的行数（保存前要人确认一遍）。 */
const autoFilledCount = computed(() => flattenRows(rows.value).filter(e => e.row.auto).length)

/** 切到手写 JSONata（#106）：先说清这条路跳过规则树校验、逐行的规则树会被整段替换。 */
async function openExpression() {
  const ok = await ElMessageBox.confirm(
    `${expressionReplaced()}\n\n${expressionSkipNotice()}`,
    '高级：手写 JSONata',
    { type: 'warning', confirmButtonText: '切到手写表达式', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  editorMode.value = 'expression'
  // 上一次校验是逐行那边跑的：换了一边就不该再显示它的行状态与问题
  validation.value = null
  closePanel()
  sourcePanel.value?.endPick()
}

/** 切回逐行配置（#106）：说清保存时规则树会替换掉这份手写表达式。 */
async function switchBackToRows() {
  const ok = await ElMessageBox.confirm(
    expressionReplacedNotice(),
    '切回逐行配置',
    { type: 'warning', confirmButtonText: '切回去', cancelButtonText: '取消' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  editorMode.value = 'rows'
  validation.value = null
}

async function save() {
  // 手写这一边：先把「写了没有」说清楚（编辑器里的本地事实），对错仍然交给后端那个只读接口判
  if (editorMode.value === 'expression' && !hasExpression(expressionText.value)) {
    ElMessage.warning('还没有写表达式——这一整段就是这次映射要执行的东西')
    return
  }
  // 从手写切回逐行之后再保存：那一刻说过一次「规则树会替换掉表达式」，这里再确认一次（保存是最后一道）
  if (rowsMode.value && storedExpression.value) {
    try {
      await ElMessageBox.confirm(
        `${expressionReplaced()}`,
        '会替换手写表达式',
        { type: 'warning', confirmButtonText: '替换并保存', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  if (rowsMode.value && !configuredCount.value) {
    ElMessage.warning(rows.value.some(r => r.target.trim())
      ? '还没有配好的字段映射——每个目标字段至少要选一个来源，常量要填一个值，枚举表要有一条，拼接要有片段'
      : '还没有任何目标字段——改一个写目标动作，或者先点「重新探测目标字段」，也可以直接手输目标字段路径')
    return
  }
  saving.value = true
  try {
    // 校验排在「还有几行没配好」前面：有错的时候保存整份都进不去，先把这个说清楚
    //（那份确认框问的是「要不要少存几行」，问早了会把真正的拦下原因压到后面）。
    // 保存前一定校验一次：这份草稿后端收不收，只有后端说了算（前端不复制任何一条校验规则）。
    // 没跑起来（网络/服务端错误）时不拦——真正的兜底还是保存时那个 400。
    const result = await validateConfig()
    if (result?.errors.length) {
      jumpToRowId(firstErrorRowId(locatedIssues.value))
      ElMessage.error(`还有 ${result.errors.length} 个错误，先改掉再保存`)
      return
    }
    // 手写这一边没有「哪几行没配好」这回事：那些确认框只在逐行配置那边问
    if (rowsMode.value && untouchedCount.value) {
      const ok = await ElMessageBox.confirm(
        `还有 ${untouchedCount.value} 行没配好（没选来源 / 常量没填值 / 枚举表空 / 拼接没片段）。这些行不会被保存。`,
        '有没配好的行',
        { type: 'warning', confirmButtonText: '只保存配好的', cancelButtonText: '回去补' },
      ).then(() => true).catch(() => false)
      if (!ok) return
    }
    // 「自动匹配同名」填上的行：保存前要人认一遍（票里：提醒确认后才算人工结果）。
    // 确认过就照常保存——只是提醒，不是拦截（#98 D5：问题只提示，不阻止保存）。
    if (rowsMode.value && autoFilledCount.value) {
      const ok = await ElMessageBox.confirm(
        `有 ${autoFilledCount.value} 行的来源是「自动匹配同名」填上的，不是你手点的。`
        + '确认这些字段对得上再保存。',
        '自动填上的行还没确认',
        { type: 'warning', confirmButtonText: '确认过了，保存', cancelButtonText: '回去看看' },
      ).then(() => true).catch(() => false)
      if (!ok) return
    }
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

/* 来源清单那一整块（含它的样式）在 `MappingSourcePanel.vue` 里（#117）。 */

/* 目标字段的搜索与三档过滤（#105） */
.wb-target-search { width: 200px; flex-shrink: 0; }
.wb-match-note { font-size: 11px; color: #6b7280; white-space: nowrap; }
.wb-row-filters { display: flex; gap: 4px; flex-shrink: 0; }
/* 行上那个「自动」标（#105）：来源是自动匹配同名填上的 */
.wb-chip.chip-auto { color: #b45309; border-color: #fde68a; background: #fffbeb; }

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
.wb-source-value { font-size: 13px; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wb-child-hint { font-size: 11px; color: #9ca3af; }
.wb-alert { margin: 8px 16px 0; }

/* 数组行的「数组元素内」分组标题：展开 / 收起子行就在这里 */
.wb-group { display: flex; align-items: center; gap: 8px; margin: 4px 0 2px 24px; }
.wb-group-title { font-size: 11px; color: #6b7280; font-weight: 600; }
.wb-group-note { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }

/* 一行里能点的那些小块：点一下就就地改，不开弹窗。
   基元（`.wb-chip` 与悬停那一条）在 styles/workbench-panel.css——行列表与来源清单两处都用它，
   这里只留这一行里那几种小块的落点（拼接片段、清除/撤销、换转换……）。 */
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
   那个问题盒子在 `MappingIssuesPanel.vue` 里（#117 拆出去了） */
.wb-col-issues { display: flex; align-items: center; gap: 6px; max-width: 280px; flex-shrink: 0; }
.wb-state { flex-shrink: 0; font-size: 11px; border-radius: 3px; padding: 0 5px; border: 1px solid #e5e7eb; color: #9ca3af; }
.wb-state.error { color: #b91c1c; border-color: #fecaca; background: #fef2f2; }
.wb-state.warning { color: #b45309; border-color: #fde68a; background: #fffbeb; }
.wb-state.ok { color: #15803d; border-color: #bbf7d0; background: #f0fdf4; }
.wb-issue-inline {
  font-size: 11px; color: #6b7280; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 底部编译产物：两个标签页（JSONata / 规则 JSON） */
.wb-foot { border-top: 1px solid #e5e7eb; padding: 0 16px; background: #fff; }
.wb-artifacts :deep(.el-tabs__header) { margin-bottom: 4px; }
.wb-artifact {
  margin: 0 0 8px; max-height: 150px; overflow: auto;
  font-family: Consolas, monospace; font-size: 12px; line-height: 1.5;
  background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 4px; padding: 8px;
  white-space: pre; color: #111827;
}

/* 就地参数面板：占这一行下面的一行 */
.wb-panel {
  margin: 2px 0 8px 8px; padding: 8px 10px;
  border: 1px solid #dbeafe; border-radius: 6px; background: #f8fafc;
}
.wb-panel-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.wb-panel-target { font-size: 11px; color: #9ca3af; font-family: Consolas, monospace; }
/* 这一组（行、说明、转换按钮、枚举表、片段）在下面那个**非 scoped** 的 style 块里：
   两个参数编辑器组件（MappingDateParams / MappingEnumParams）在行级与片段级共用，
   它们内部的元素也要用到这套样式，scoped 到不了子组件里面（#112）。 */

/* 手写 JSONata 那一边的样式与它的问题列表在 `MappingExpressionEditor.vue` 里（#117 拆出去了）。 */

/* 头部那个「手写 JSONata」的当前模式标，与「高级」入口的样式（不显眼，但一直都在） */
.wb-chip.chip-mode { color: #3730a3; border-color: #c7d2fe; background: #eef2ff; cursor: default; }
.wb-advanced { color: #9ca3af; }

/* 窄屏（#106）：每一行「目标字段 → 映射 → 问题」依次排列，读得通、不重叠。
   两档：先把左边来源清单挪到上面（中等宽度），再把每一行的三列竖起来（更窄）。 */
@media (max-width: 1100px) {
  .wb-head { gap: 10px; }
  .wb-head-actions { flex-wrap: wrap; row-gap: 6px; }
  .wb-progress { width: 160px; }
  .wb-step-select { width: 160px; }
  .wb-target-search { width: 160px; }
}

@media (max-width: 1024px) {
  /* 来源清单铺在上面：宽度不够时它和行列表并排，两边都读不成
     （清单自己那一侧的尺寸在 `MappingSourcePanel.vue` 里） */
  .wb-body { flex-direction: column; }
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
  .wb-head { padding: 8px; }
  .wb-progress { width: 120px; }
  .wb-step-select, .wb-target-search { width: 100%; }
}
</style>

<!-- 参数面板的基元与它们的响应式规则：两个参数编辑器组件（行级 / 片段级共用）内部也要用，见那个文件 -->
<style src="../styles/workbench-panel.css"></style>
<!-- 校验结果列表的基元：逐行那边的问题列表与手写那边的结果列表共用，见那个文件 -->
<style src="../styles/workbench-issues.css"></style>
