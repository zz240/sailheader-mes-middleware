<template>
  <div class="hc-page">
    <section class="hc-pagehead">
      <div class="hc-title-row">
        <div>
          <h1>消息历史</h1>
          <p class="hc-subline">
            <span>死信升维为「运行 + 消息步」<code>ADR-0009</code></span>
            <span>·</span>
            <span>失败即停、重试退避、从失败步断点续跑<code>ADR-0003</code></span>
          </p>
        </div>
      </div>
    </section>

    <HistoryFilters
      v-model="filters"
      :jobs="jobs"
      :jobs-error="jobsError"
      :busy="loading"
      @query="onQuery"
      @reset="onReset"
    />

    <main class="hc-workspace">
      <HistoryListPanel
        :runs="runs"
        :jobs="jobs"
        :total="total"
        :selected-id="selectedRunId"
        :loading="loading"
        :error="error"
        :filters="filters"
        @select="onSelect"
        @retry="load"
      />
      <MessageDetailPanel :run="selectedRun" :jobs="jobs" />
    </main>

    <!-- 分页条放在**页级**：左栏只有 322px，装不下 element-plus 那套完整分页（实测会被推到栏外）。
         只在接口出错时收起来——「筛没了」也要留着它，否则每页条数都没得改。 -->
    <div v-if="!error" class="hc-pfoot">
      <el-pagination
        :current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPage"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import HistoryFilters from '../components/history/HistoryFilters.vue'
import HistoryListPanel from '../components/history/HistoryListPanel.vue'
import MessageDetailPanel from '../components/history/MessageDetailPanel.vue'
import { api, ApiError } from '../api/client'
import { emptyFilters, runQuery, type RunFilters } from '../history/console'
import type { Job, JobRun, Page } from '../types'

const jobs = ref<Job[]>([])
const jobsError = ref('')
const runs = ref<JobRun[]>([])
const filters = ref<RunFilters>(emptyFilters())
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const selectedRunId = ref<number | null>(null)

const selectedRun = computed(() => runs.value.find((run) => run.id === selectedRunId.value) ?? null)

async function loadJobs() {
  try {
    jobs.value = await api<Job[]>('/jobs')
    jobsError.value = ''
  } catch (e) {
    // 任务清单取不到**不该拖住列表**：行上的任务名退化成 `#id` 照样能看，但这件事要在界面上说出来
    jobsError.value = e instanceof ApiError ? e.message : String(e)
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await api<Page<JobRun>>(`/history/runs?${runQuery(filters.value, currentPage.value, pageSize.value)}`)
    runs.value = page.records
    total.value = page.total
    // 选中的那条不在这一页了就把右栏收回去，免得详情与列表对不上
    if (selectedRunId.value !== null && !page.records.some((run) => run.id === selectedRunId.value)) {
      selectedRunId.value = null
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : String(e)
    runs.value = []
    total.value = 0
    selectedRunId.value = null
  } finally {
    loading.value = false
  }
}

function onQuery() {
  currentPage.value = 1
  void load()
}

function onReset() {
  filters.value = emptyFilters()
  currentPage.value = 1
  void load()
}

function onSelect(run: JobRun) {
  selectedRunId.value = run.id
}

function onPage(page: number) {
  currentPage.value = page
  void load()
}

function onSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  void load()
}

onMounted(async () => {
  // 两件事一起发：任务清单挂掉也不影响列表自己把那句话说出来（别把「坏了」装成「没有」）
  await Promise.all([loadJobs(), load()])
})
</script>

<style src="../styles/history-console.css"></style>
