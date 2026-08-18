export type DataSourceType = 'HTTP' | 'MYSQL' | 'RABBITMQ'

export interface DataSource {
  id: number
  siteId: number
  name: string
  type: DataSourceType
  address: string
  addition: Record<string, any>
}

export interface Job {
  id: number
  siteId: number
  name: string
  status: number
  crontab?: string | null
  timezone?: string | null
  retryCount?: number | null
  backoffMs?: number | null
  watermarkType?: string | null
  watermarkColumn?: string | null
  remarks?: string | null
  lastRunStatus?: string | null
  lastRunAt?: string | null
  lastError?: string | null
}

export type ActionType = 'REMOTE_ACCESS' | 'FIELD_MAPPING'

export interface Action {
  id: number
  jobId: number
  name: string
  type: ActionType
  index: number
  addition: Record<string, any>
}

export interface DeadLetter {
  id: number
  jobId: number | null
  actionIndex: number | null
  inputJson: string | null
  error: string | null
  status: string
}

export interface Template {
  id: number
  name: string
  version: string
  configJson: Record<string, any>
}

export interface SiteInfo {
  id: number
  name: string
  timezone: string
  hasApiKey: boolean
  suggester: string
}

export interface ProbeField {
  name: string
  type: string
  nullable: boolean
  samples: any[]
}

export interface ProbeResult {
  sourceKey: string
  fields: ProbeField[]
}

export interface MappingSuggestion {
  sourcePath: string
  targetName: string
  score: number
  transform: string
  datePattern?: string | null
  constant?: string | null
  enumMap?: Record<string, any> | null
  matchSource: string
}

export interface ValidationIssue {
  scope: string
  field: string
  message: string
}

export interface ValidationResult {
  valid: boolean
  errors: ValidationIssue[]
  warnings: ValidationIssue[]
}
