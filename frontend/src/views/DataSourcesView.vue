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
          <el-button size="small" v-if="row.type === 'MYSQL' || row.type === 'SQLSERVER'" @click="openProbe(row)">探测结构</el-button>
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="editing ? '编辑数据源' : '新建数据源'" width="600px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="站点 ID"><el-input-number v-model="form.siteId" :min="1" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" @change="onTypeChange">
            <el-option label="HTTP" value="HTTP" />
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="SQL Server" value="SQLSERVER" />
            <el-option label="RabbitMQ" value="RABBITMQ" />
          </el-select>
        </el-form-item>

        <el-form-item label="地址">
          <el-input v-model="form.address" :placeholder="addressPlaceholder" />
        </el-form-item>

        <!-- 按类型特化的 addition 字段 -->
        <template v-if="form.type === 'HTTP'">
          <el-form-item label="Token">
            <el-input v-model="addition.token" type="password" show-password placeholder="可选，Bearer 鉴权" />
          </el-form-item>
        </template>
        <template v-else-if="form.type === 'MYSQL' || form.type === 'SQLSERVER'">
          <el-form-item label="用户名"><el-input v-model="addition.username" placeholder="如 gateway" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="addition.password" type="password" show-password /></el-form-item>
        </template>
        <template v-else-if="form.type === 'RABBITMQ'">
          <el-form-item label="Host"><el-input v-model="addition.host" placeholder="默认 localhost" /></el-form-item>
          <el-form-item label="Port"><el-input-number v-model="addition.port" :min="1" :max="65535" /></el-form-item>
          <el-form-item label="用户名"><el-input v-model="addition.username" placeholder="默认 guest" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="addition.password" type="password" show-password placeholder="默认 guest" /></el-form-item>
        </template>

        <div class="hint">path / method / queue 等具体资源在动作里配，不在此处。</div>
        <div class="hint">
          已有一整份 addition？<el-button link type="primary" size="small" @click="openImport">从 JSON 粘贴</el-button>，一次填满上面的格子。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 一次性导入：只把认识的键填回上面的格子，不构成第二种存储形态（#155） -->
    <el-dialog v-model="importVisible" title="从 JSON 粘贴 addition" width="520px" append-to-body>
      <div class="hint">整份导入：没写的键清空，MQ 的 Host / Port / 账号回到默认值。点「导入」只填上面的格子，保存才写库。</div>
      <el-input v-model="importText" type="textarea" :rows="6" :placeholder="importPlaceholder" />
      <div class="hint">本类型只接受：{{ allowedKeys.join('、') }}</div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="applyImport">导入</el-button>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api/client'
import type { DataSource, ProbeResult } from '../types'

const list = ref<DataSource[]>([])
const formVisible = ref(false)
const editing = ref<DataSource | null>(null)
const form = reactive({ name: '', siteId: 1, type: 'HTTP' as string, address: '' })

// 按类型特化的 addition 字段：token / username / password / host / port
const addition = reactive({ token: '', username: '', password: '', host: '', port: 5672 })

// 「从 JSON 粘贴」：一次性导入的原文与弹窗开关（导入结果只落进上面的 addition）
const importVisible = ref(false)
const importText = ref('')

/** 每种类型后端接受的键，与 config/datasource 下的强类型配置一一对应。 */
const ADDITION_KEYS: Record<string, string[]> = {
  HTTP: ['token'],
  MYSQL: ['username', 'password'],
  SQLSERVER: ['username', 'password'],
  RABBITMQ: ['host', 'port', 'username', 'password'],
}

const allowedKeys = computed(() => ADDITION_KEYS[form.type] ?? [])

const addressPlaceholder = computed(() => {
  switch (form.type) {
    case 'MYSQL': return 'jdbc:mysql://host:3306/库名（作为连接 url，以 address 为准）'
    case 'SQLSERVER': return 'jdbc:sqlserver://host:1433;databaseName=库名（作为连接 url，以 address 为准）'
    case 'RABBITMQ': return 'amqp://host:5672（broker）'
    default: return 'http://host:port（根地址，path/method 在动作里配）'
  }
})

const importPlaceholder = computed(() => {
  switch (form.type) {
    case 'MYSQL': return '{"username":"gateway","password":"..."}'
    case 'SQLSERVER': return '{"username":"sa","password":"..."}'
    case 'RABBITMQ': return '{"host":"localhost","port":5672,"username":"guest","password":"guest"}'
    default: return '{"token":"..."}'
  }
})

const probeVisible = ref(false)
const probeDs = ref<DataSource | null>(null)
const probeTable = ref('')
const probeSample = ref('')
const probeResult = ref<ProbeResult | null>(null)
const probeMsg = ref('')

function resetAddition() {
  Object.assign(addition, { token: '', username: '', password: '', host: '', port: 5672 })
}

function initAddition(ad: Record<string, any> | undefined, type: string) {
  resetAddition()
  const a = ad || {}
  if (type === 'HTTP') {
    addition.token = a.token ?? ''
  } else if (type === 'MYSQL' || type === 'SQLSERVER') {
    addition.username = a.username ?? ''
    addition.password = a.password ?? ''
  } else if (type === 'RABBITMQ') {
    addition.host = a.host ?? 'localhost'
    addition.port = a.port ?? 5672
    addition.username = a.username ?? 'guest'
    addition.password = a.password ?? 'guest'
  }
}

function buildAddition(): Record<string, any> {
  const o: Record<string, any> = {}
  if (form.type === 'HTTP') {
    if (addition.token) o.token = addition.token
  } else if (form.type === 'MYSQL' || form.type === 'SQLSERVER') {
    // 连接 url 以 address 为准（关系库配置只收 username/password）
    if (addition.username) o.username = addition.username
    if (addition.password) o.password = addition.password
  } else if (form.type === 'RABBITMQ') {
    if (addition.host) o.host = addition.host
    const p = Number(addition.port)
    if (Number.isInteger(p) && p > 0) o.port = p
    if (addition.username) o.username = addition.username
    if (addition.password) o.password = addition.password
  }
  return o
}

function formValidate(): string | null {
  if (!form.address) return '需要填写地址'
  if (form.type === 'MYSQL' || form.type === 'SQLSERVER') {
    const dbName = form.type === 'SQLSERVER' ? 'SQL Server' : 'MySQL'
    if (!addition.username) return `${dbName} 需要用户名`
    if (!addition.password) return `${dbName} 需要密码`
  }
  if (form.type === 'RABBITMQ' && addition.port != null && !Number.isInteger(Number(addition.port))) {
    return '端口必须是整数'
  }
  return null
}

function onTypeChange() {
  resetAddition()
}

function openImport() {
  importText.value = ''
  importVisible.value = true
}

/** JSON 里的标量转成输入框的值；对象/数组由 applyImport 先挡掉。 */
function importTextValue(value: unknown): string {
  return value === undefined || value === null ? '' : String(value)
}

/** 缺省 5672；只收 1..65535 的整数（数字或数字字符串），布尔值不算。 */
function importPort(value: unknown): number | null {
  if (value === undefined || value === null) return 5672
  if (typeof value === 'boolean') return null
  const port = Number(value)
  return Number.isInteger(port) && port >= 1 && port <= 65535 ? port : null
}

/**
 * 导入是一次性「填满表单」，不是第二种存储形态：
 * 只接受本类型认识的键，导入后保存仍由 buildAddition() 从表单拼。
 * 原来这里是一个「非空时优先」的原始 JSON 框，等于两处真相——表单填了也不算数。
 */
function applyImport() {
  let parsed: unknown
  try {
    parsed = JSON.parse(importText.value)
  } catch {
    ElMessage.error('不是合法 JSON')
    return
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    ElMessage.error('要粘一个 JSON 对象，比如 {"token":"..."}')
    return
  }
  const value = parsed as Record<string, unknown>
  const unknown = Object.keys(value).filter((k) => !allowedKeys.value.includes(k))
  if (unknown.length > 0) {
    ElMessage.error(`本类型不认识：${unknown.join('、')}；只接受 ${allowedKeys.value.join('、')}`)
    return
  }
  const nested = Object.keys(value).find((k) => value[k] !== null && typeof value[k] === 'object')
  if (nested) {
    ElMessage.error(`字段 ${nested} 的值要是字符串或数字`)
    return
  }
  const port = form.type === 'RABBITMQ' ? importPort(value.port) : null
  if (form.type === 'RABBITMQ' && port === null) {
    ElMessage.error('port 要是 1 到 65535 的整数')
    return
  }

  resetAddition()
  if (form.type === 'HTTP') {
    addition.token = importTextValue(value.token)
  } else if (form.type === 'MYSQL' || form.type === 'SQLSERVER') {
    addition.username = importTextValue(value.username)
    addition.password = importTextValue(value.password)
  } else if (form.type === 'RABBITMQ') {
    // 没写或写空的键显示后端会落库的默认值，避免「界面空着、库里却有值」
    addition.host = importTextValue(value.host) || 'localhost'
    addition.port = port as number
    addition.username = importTextValue(value.username) || 'guest'
    addition.password = importTextValue(value.password) || 'guest'
  }
  importVisible.value = false
  ElMessage.success('已填到表单，点「保存」才写库')
}

async function load() {
  list.value = await api<DataSource[]>('/data-sources')
}

function openCreate() {
  editing.value = null
  Object.assign(form, { name: '', siteId: 1, type: 'HTTP', address: '' })
  resetAddition()
  formVisible.value = true
}

function openEdit(row: DataSource) {
  editing.value = row
  Object.assign(form, { name: row.name, siteId: row.siteId, type: row.type, address: row.address })
  initAddition(row.addition, row.type)
  formVisible.value = true
}

async function save() {
  const err = formValidate()
  if (err) {
    ElMessage.error(err)
    return
  }
  const body = { name: form.name, siteId: form.siteId, type: form.type, address: form.address, addition: buildAddition() }
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
.msg { margin-top: 8px; font-size: var(--fs-body); color: var(--accent); white-space: pre-wrap; }
.hint { margin: 4px 0 8px; font-size: var(--fs-body); color: var(--muted); }
</style>
