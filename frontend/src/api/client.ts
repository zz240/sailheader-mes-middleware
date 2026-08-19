/**
 * 统一 API 客户端：/api 前缀 + 业务码信封解包（DS-1）。
 * 成功返回 data；code!=OK 抛带 code 的 ApiError；204 返回 undefined。
 */
const BASE = '/api'

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (res.status === 204) {
    return undefined as T
  }
  const body = (await res.json().catch(() => null)) as ApiEnvelope<T> | null
  if (!res.ok || !body || body.code !== 'OK') {
    throw new ApiError(
      res.status,
      body?.code ?? 'INTERNAL_ERROR',
      body?.message || `HTTP ${res.status}`,
    )
  }
  return body.data
}
