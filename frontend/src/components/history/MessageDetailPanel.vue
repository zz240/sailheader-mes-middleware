<template>
  <section class="hc-panel hc-detail-panel" aria-label="消息详情">
    <div class="hc-phead">
      <span class="hc-pt">消息详情</span>
      <!-- 头右端就是**选中了什么**：消息步检索模式下点一行，这里写清运行 / 消息 / 步骤（#136） -->
      <span class="hc-ps">{{ detailPath(selection) }}</span>
    </div>

    <div class="hc-scroll">
      <div v-if="runError" class="hc-errbox">
        <div>这条运行的详情没取到。</div>
        <div class="hc-ps">{{ runError }}</div>
      </div>

      <div v-else-if="!run" class="hc-list-empty">
        <div class="hc-empty-title">选一条运行看它的消息与消息步</div>
      </div>

      <div v-else class="hc-dbody">
        <div class="hc-dhead">
          <h2>运行 #{{ run.id }}</h2>
          <span class="hc-st" :class="`hc-st-${chip.kind}`">{{ chip.label }}</span>
          <span class="hc-ps">{{ jobTitle }}</span>
        </div>
        <div class="hc-dsub">
          <span>开始 <b>{{ stampOf(run.startedAt) }}</b></span>
          <span>结束 <b>{{ stampOf(run.finishedAt) }}</b></span>
        </div>
        <div v-if="run.error" class="hc-runerr">
          <span class="hc-mk">运行错误</span>
          <pre>{{ run.error }}</pre>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { detailPath, jobTitleOf, stampOf, statusChip, type HistorySelection } from '../../history/console'
import type { Job, JobRun } from '../../types'

const props = defineProps<{
  run: JobRun | null
  jobs: Job[]
  selection: HistorySelection
  /** 取这条运行失败时的说法（消息步模式下点一行会按需去取它所属的运行）。 */
  runError?: string
}>()

const chip = computed(() => statusChip(props.run?.status))
const jobTitle = computed(() => (props.run ? jobTitleOf(props.jobs, props.run.jobId) : ''))
</script>
