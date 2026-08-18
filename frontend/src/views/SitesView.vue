<template>
  <el-card>
    <template #header><span>站点与 API Key</span></template>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="timezone" label="时区" width="140" />
      <el-table-column label="API Key" width="140">
        <template #default="{ row }">
          <el-tag :type="row.hasApiKey ? 'success' : 'info'">{{ row.hasApiKey ? '已设置' : '未设置' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="suggester" label="建议器覆盖" width="130">
        <template #default="{ row }">{{ row.suggester || '默认(rule)' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="generateKey(row)">生成/重置 API Key</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="keyVisible" title="API Key 已生成（仅显示一次，请妥善保存）" width="560px">
      <el-alert type="warning" :closable="false" title="明文只显示这一次，关闭后无法再次查看" />
      <pre class="pre">{{ generatedKey }}</pre>
    </el-dialog>

    <el-alert type="info" :closable="false" class="tip">
      外部触发任务：POST /api/external/jobs/{id}/trigger，请求头 X-API-Key 携带站点 Key（任务需启用）。
    </el-alert>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { SiteInfo } from '../types'

const list = ref<SiteInfo[]>([])
const keyVisible = ref(false)
const generatedKey = ref('')

async function load() {
  list.value = await api<SiteInfo[]>('/sites')
}

async function generateKey(row: SiteInfo) {
  try {
    const r = await api<{ apiKey: string }>(`/sites/${row.id}/api-key`, { method: 'POST' })
    generatedKey.value = r.apiKey
    keyVisible.value = true
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

onMounted(load)
</script>

<style scoped>
.pre { white-space: pre-wrap; word-break: break-all; font-size: 14px; }
.tip { margin-top: 16px; }
</style>
