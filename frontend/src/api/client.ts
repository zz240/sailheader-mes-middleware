/** 统一 API 客户端：/api 前缀 + JSON + 错误抛出（含 HTTP 状态码与后端错误文本）。 */
const BASE = '/api'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, text || `HTTP ${res.status}`)
  }
  return res.status === 204 ? (undefined as T) : (res.json() as Promise<T>)
}
