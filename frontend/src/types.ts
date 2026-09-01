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
  direction?: 'NORTH' | 'SOUTH' | null
  crontab?: string | null
  timezone?: string | null
  retryCount?: number | null
  backoffMs?: number | null
  remarks?: string | null
  lastRunStatus?: string | null
  lastRunAt?: string | null
  lastError?: string | null
}

export type ActionType = 'READ_SQL' | 'WRITE_UPSERT' | 'HTTP_CALL' | 'MQ_CONSUME' | 'MQ_PUBLISH' | 'FIELD_MAP'

export interface Action {
  id: number
  jobId: number
  name: string
  type: ActionType
  index: number
  addition: Record<string, any>
}

export interface JobRun {
  id: number
  jobId: number
  status: string
  inputSnapshot?: string | null
  error?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface MessageStep {
  id: number
  runId: number
  messageId: string
  step: number
  actionName?: string | null
  status: string
  dataSnapshot?: string | null
  inputSnapshot?: string | null
  error?: string | null
  createdAt?: string | null
  attempt?: number | null
  businessKey?: string | null
}

export interface Page<T> {
  total: number
  records: T[]
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
  children?: MappingSuggestion[] | null
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
