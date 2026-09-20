<template>
  <section class="hc-filters">
    <div class="hc-ctl">
      <label for="hc-filter-job">任务</label>
      <el-select
        id="hc-filter-job"
        class="hc-filter-job"
        :model-value="modelValue.jobId ?? undefined"
        clearable
        filterable
        placeholder="全部任务"
        @update:model-value="onJob"
      >
        <el-option v-for="job in jobs" :key="job.id" :value="job.id" :label="jobOptionLabel(job)" />
      </el-select>
    </div>

    <div class="hc-ctl">
      <label for="hc-filter-status">状态</label>
      <!-- 两个模式的状态默认值本来就不一样：运行这一侧默认不过滤（占位「全部状态」）；
           消息步那一侧默认只看 FAILED（#74），占位就把这句真话写出来（#136）。
           「全部状态」是**显式的一档**（STATUS_ANY）：选了它才真的不过滤——旧实现把它与
           「没选」混成一个空值，于是消息步那一侧的「全部状态」看不到全部。 -->
      <el-select
        id="hc-filter-status"
        class="hc-filter-status"
        :model-value="modelValue.status || undefined"
        clearable
        :placeholder="statusPlaceholder(mode)"
        @update:model-value="onStatus"
      >
        <el-option :value="STATUS_ANY" label="全部状态" />
        <el-option v-for="status in RUN_STATUSES" :key="status" :value="status" :label="status" />
      </el-select>
    </div>

    <div class="hc-ctl">
      <label for="hc-filter-biz">业务键</label>
      <el-input
        id="hc-filter-biz"
        class="hc-filter-biz"
        :model-value="modelValue.businessKey"
        clearable
        placeholder="SO-2026-0917-0042"
        @update:model-value="onBusinessKey"
        @keyup.enter="emit('query')"
      />
    </div>

    <el-button type="primary" :loading="busy" @click="emit('query')">查询</el-button>
    <el-button @click="emit('reset')">重置</el-button>

    <!-- 两个模式的开关（原型把它放进筛选条右端）：切模式回第 1 页、选中态留在那条运行上。
         与右端那句口径**抱成一组**：宽度不够时整组落到第二行，不会一个在上一行尾、一个在下一行头。 -->
    <div class="hc-tail">
      <el-radio-group class="hc-seg" :model-value="mode" aria-label="历史视图" @update:model-value="onMode">
        <el-radio-button v-for="item in HISTORY_MODES" :key="item.value" :value="item.value">
          {{ item.label }}
        </el-radio-button>
      </el-radio-group>

      <span v-if="jobsError" class="hc-note hc-note-warn">任务清单没取到（列表里只剩 #id）：{{ jobsError }}</span>
      <span v-else class="hc-note">{{ note }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  filterNote, HISTORY_MODES, jobOptionLabel, RUN_STATUSES, statusPlaceholder, STATUS_ANY,
  type HistoryMode, type RunFilters,
} from '../../history/console'
import type { Job } from '../../types'

const props = defineProps<{
  jobs: Job[]
  modelValue: RunFilters
  mode: HistoryMode
  jobsError?: string
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: RunFilters]
  'update:mode': [value: HistoryMode]
  query: []
  reset: []
}>()

/** 右端那句口径随模式与状态走（消息步那一侧要把「默认只看 FAILED」写出来）。 */
const note = computed(() => filterNote(props.mode, props.modelValue))

function onJob(value: number | string | null | undefined) {
  const jobId = value === null || value === undefined || value === '' ? null : Number(value)
  emit('update:modelValue', { ...props.modelValue, jobId })
  emit('query')
}

function onStatus(value: string | null | undefined) {
  emit('update:modelValue', { ...props.modelValue, status: value ?? '' })
  emit('query')
}

function onBusinessKey(value: string) {
  emit('update:modelValue', { ...props.modelValue, businessKey: value ?? '' })
}

function onMode(value: string | number | boolean | undefined) {
  if (value === 'runs' || value === 'steps') emit('update:mode', value)
}
</script>
