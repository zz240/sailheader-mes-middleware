<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>数据源</span>
        <el-button type="primary" @click="openCreate">新建数据源</el-button>
      </div>
    </template>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="type" label="类型" width="100" />
      <el-table-column prop="address" label="地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="test(row)">测试连接</el-button>
          <el-button size="small" v-if="row.type === 'MYSQL'" @click="openProbe(row)">探测结构</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="editing ? '编辑数据源' : '新建数据源'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="站点 ID"><el-input-number v-model="form.siteId" :min="1" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="RabbitMQ" value="RABBITMQ" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="addition">
          <el-input v-model="additionText" type="textarea" :rows="5" placeholder="JSON 配置：HTTP 需 method(/token)，MySQL 需 url/username/password，RabbitMQ 需 queue" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="probeVisible" :title="`结构探测：${probeDs?.name ?? ''}`" width="720px">
      <div class="probe-row">
        <el-input v-model="probeTable" placeholder="表名，如 po_order" style="width: 220px" />
        <el-input v-model="probeSample" placeholder="样例 JSON（可选，连库失败回退）" style="width: 300px" />
        <el-button type="primary" @click="runProbe(false)">探测</el-button>
        <el-button @click="runProbe(true)">强制刷新</el-button>
      </div>
      <el-table :data="probeResult?.fields ?? []" max-height="360" border>
        <el-table-column prop="name" label="字段" />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column label="可空" width="70">
          <template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="样例（前5）">
          <template #default="{ row }">{{ (row.samples || []).slice(0, 5).map((v: any) => JSON.stringify(v)).join(', ') }}</template>
        </el-table-column>
      </el-table>
      <div class="msg">{{ probeMsg }}</div>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { DataSource, ProbeResult } from '../types'

const list = ref<DataSource[]>([])
const formVisible = ref(false)
const editing = ref<DataSource | null>(null)
const form = reactive({ name: '', siteId: 1, type: 'HTTP' as string, address: '' })
const additionText = ref('{}')

const probeVisible = ref(false)
const probeDs = ref<DataSource | null>(null)
const probeTable = ref('')
const probeSample = ref('')
const probeResult = ref<ProbeResult | null>(null)
const probeMsg = ref('')

async function load() {
  list.value = await api<DataSource[]>('/data-sources')
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', siteId: 1, type: 'HTTP', address: '' })
  additionText.value = '{}'
  formVisible.value = true
}

function openEdit(row: DataSource) {
  editing.value = row
  Object.assign(form, { name: row.name, siteId: row.siteId, type: row.type, address: row.address })
  additionText.value = JSON.stringify(row.addition || {}, null, 2)
  formVisible.value = true
}

async function save() {
  const body = { name: form.name, siteId: form.siteId, type: form.type, address: form.address, addition: JSON.parse(additionText.value || '{}') }
  try {
    if (editing.value) {
      await api(`/data-sources/${editing.value.id}`, { method: 'PUT', body: JSON.stringify(body) })
    } else {
      await api('/data-sources', { method: 'POST', body: JSON.stringify(body) })
    }
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function test(row: DataSource) {
  const r = await api<{ ok: boolean; message: string }>(`/data-sources/${row.id}/test`, { method: 'POST' })
  ElMessage[r.ok ? 'success' : 'error'](`ok=${r.ok} ${r.message}`)
}

async function remove(row: DataSource) {
  await ElMessageBox.confirm(`删除数据源 ${row.name}？`, '确认')
  await api(`/data-sources/${row.id}`, { method: 'DELETE' })
  await load()
}

async function openProbe(row: DataSource) {
  probeDs.value = row
  probeResult.value = null
  probeMsg.value = ''
  probeSample.value = ''
  probeVisible.value = true
  const r = await api<{ table: string | null }>(`/data-sources/${row.id}/suggest-table`)
  probeTable.value = r.table ?? ''
  if (r.table) probeMsg.value = `已从任务读 SQL 预填表名：${r.table}`
}

async function runProbe(refresh: boolean) {
  if (!probeDs.value) return
  try {
    const body: Record<string, any> = { table: probeTable.value, refresh }
    if (probeSample.value.trim()) body.sample = JSON.parse(probeSample.value)
    probeResult.value = await api<ProbeResult>(`/data-sources/${probeDs.value.id}/probe`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    probeMsg.value = `探测成功，共 ${probeResult.value.fields.length} 个字段` +
      (probeResult.value.sourceKey === 'sample' ? '（已回退样例模式）' : '')
  } catch (e) {
    probeMsg.value = (e as ApiError).message
  }
}

onMounted(load)
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; }
.probe-row { display: flex; gap: 8px; margin-bottom: 10px; }
.msg { margin-top: 8px; font-size: 12px; color: #2563eb; white-space: pre-wrap; }
</style>
