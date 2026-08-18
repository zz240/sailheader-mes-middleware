<template>
  <el-card>
    <template #header>
      <div class="header">
        <span>模板</span>
        <div class="actions">
          <el-upload :show-file-list="false" accept=".json" :before-upload="onImportFile" style="display: inline-block; margin-right: 8px">
            <el-button>导入 JSON</el-button>
          </el-upload>
          <el-button type="primary" @click="openCreate">新建模板</el-button>
        </div>
      </div>
    </template>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="version" label="版本" width="90" />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="exportTemplate(row)">导出</el-button>
          <el-button size="small" @click="detail(row)">详情</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" title="新建模板" width="640px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="如 po-sync-模板" /></el-form-item>
        <el-form-item label="configJson">
          <el-input v-model="configText" type="textarea" :rows="12" placeholder='{"job":{...},"actions":[...]}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="模板详情" width="640px">
      <pre class="pre">{{ JSON.stringify(detailRow, null, 2) }}</pre>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { Template } from '../types'

const list = ref<Template[]>([])
const formVisible = ref(false)
const form = reactive({ name: '' })
const configText = ref('')
const detailVisible = ref(false)
const detailRow = ref<Template | null>(null)

async function load() {
  list.value = await api<Template[]>('/templates')
}

function openCreate() {
  form.name = ''
  configText.value = ''
  formVisible.value = true
}

async function save() {
  try {
    const configJson = JSON.parse(configText.value)
    const t = await api<Template>('/templates', {
      method: 'POST',
      body: JSON.stringify({ name: form.name, configJson }),
    })
    ElMessage.success(`已创建 ${t.name} v${t.version}`)
    formVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as ApiError).message)
  }
}

async function exportTemplate(row: Template) {
  const t = await api<Template>(`/templates/${row.id}/export`)
  const blob = new Blob([JSON.stringify({ name: t.name, configJson: t.configJson }, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${t.name}-v${t.version}.json`
  a.click()
  URL.revokeObjectURL(url)
}

function onImportFile(file: File) {
  file.text().then(async text => {
    try {
      const parsed = JSON.parse(text)
      const t = await api<Template>('/templates/import', {
        method: 'POST',
        body: JSON.stringify({ name: parsed.name ?? '', configJson: parsed.configJson ?? parsed }),
      })
      ElMessage.success(`导入成功：${t.name} v${t.version}`)
      await load()
    } catch (e) {
      ElMessage.error((e as ApiError).message)
    }
  })
  return false
}

async function detail(row: Template) {
  detailRow.value = row
  detailVisible.value = true
}

async function remove(row: Template) {
  await ElMessageBox.confirm(`删除模板 ${row.name} v${row.version}？`, '确认')
  await api(`/templates/${row.id}`, { method: 'DELETE' })
  await load()
}

onMounted(load)
</script>

<style scoped>
.header { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; align-items: center; }
.pre { white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
