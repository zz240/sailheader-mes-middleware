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
      <!-- 运行这一侧**默认不过滤**，所以占位符就把这句话写出来（消息步那一侧默认 FAILED，#74） -->
      <el-select
        id="hc-filter-status"
        class="hc-filter-status"
        :model-value="modelValue.status || undefined"
        clearable
        placeholder="全部状态"
        @update:model-value="onStatus"
      >
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
    <span v-if="jobsError" class="hc-note hc-note-warn">任务清单没取到（列表里只剩 #id）：{{ jobsError }}</span>
    <span v-else class="hc-note">选任务 / 状态即时生效；业务键敲回车或点查询</span>
  </section>
</template>

<script setup lang="ts">
import { jobOptionLabel, RUN_STATUSES, type RunFilters } from '../../history/console'
import type { Job } from '../../types'

const props = defineProps<{
  jobs: Job[]
  modelValue: RunFilters
  jobsError?: string
  busy?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: RunFilters]
  query: []
  reset: []
}>()

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
</script>
