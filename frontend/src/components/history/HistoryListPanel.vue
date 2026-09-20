<template>
  <aside class="hc-panel hc-list-panel" aria-label="运行列表">
    <div class="hc-phead">
      <span class="hc-pt">运行</span>
      <span class="hc-ps">{{ listCountText(total) }}</span>
      <span class="hc-right">选一条运行看它的消息与消息步</span>
    </div>

    <div class="hc-scroll">
      <!-- 加载中：占位而不是一片空白（接口返回前用户要知道「在取」） -->
      <div v-if="loading" class="hc-skel" aria-label="加载中">
        <i v-for="n in 6" :key="n" />
      </div>

      <div v-else-if="error" class="hc-errbox">
        <div>{{ emptyCopy.title }}</div>
        <div>{{ emptyCopy.hint }}</div>
        <div class="hc-err-act">
          <el-button size="small" @click="emit('retry')">重试</el-button>
          <span class="hc-ps">{{ error }}</span>
        </div>
      </div>

      <div v-else-if="!rows.length" class="hc-list-empty">
        <div class="hc-empty-title">{{ emptyCopy.title }}</div>
        <div class="hc-empty-hint">{{ emptyCopy.hint }}</div>
      </div>

      <div v-else class="hc-rlist">
        <button
          v-for="row in rows"
          :key="row.run.id"
          class="hc-ritem"
          type="button"
          :aria-pressed="row.run.id === selectedId"
          @click="emit('select', row.run)"
        >
          <div class="hc-r-top">
            <span class="hc-r-id">#{{ row.run.id }}</span>
            <span class="hc-r-job" :title="row.jobTitle">{{ row.jobTitle }}</span>
            <span class="hc-st" :class="`hc-st-${row.chip.kind}`">{{ row.chip.label }}</span>
          </div>
          <div class="hc-r-sub">
            <span>开始 {{ row.started }}</span>
            <span>结束 {{ row.finished }}</span>
          </div>
          <div v-if="row.errLine" class="hc-r-err" :title="row.run.error ?? ''">{{ row.errLine }}</div>
        </button>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  firstLine, jobTitleOf, listCountText, listEmptyCopy, stampOf, statusChip,
  type RunFilters,
} from '../../history/console'
import type { Job, JobRun } from '../../types'

const props = defineProps<{
  runs: JobRun[]
  jobs: Job[]
  total: number
  selectedId: number | null
  loading: boolean
  error: string
  filters: RunFilters
}>()

const emit = defineEmits<{
  select: [run: JobRun]
  retry: []
}>()

const emptyCopy = computed(() => listEmptyCopy({ error: props.error, filters: props.filters }))

/** 每行的说法一次算好：模板里不再重复调 statusChip / 反复查任务名。两个时刻**同一套写法**。 */
const rows = computed(() => props.runs.map((run) => ({
  run,
  chip: statusChip(run.status),
  jobTitle: jobTitleOf(props.jobs, run.jobId),
  started: stampOf(run.startedAt),
  finished: stampOf(run.finishedAt),
  errLine: firstLine(run.error),
})))
</script>
