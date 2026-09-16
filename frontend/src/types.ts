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
  scheduleType?: 'NONE' | 'INTERVAL' | 'DAILY' | 'WEEKLY' | null
  intervalValue?: number | null
  intervalUnit?: 'MINUTES' | 'HOURS' | null
  scheduleTime?: string | null
  scheduleWeekday?: number | null
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

/** 新版字段映射编辑器（#99）打开时的上下文。 */
export interface EditorActionLabel {
  index: number | null
  name: string | null
  type: string | null
}

export interface EditorWriteTarget {
  actionId: number | null
  actionIndex: number | null
  actionName: string | null
  dataSourceId: number | null
  tableName: string | null
}

export interface EditorSourceSample {
  step: number | null
  stepName: string | null
  sourceKey: string | null
  /**
   * 这份样例是一整个数组、只按第 1 条推的（界面据此注明「按第 1 条推的」）；
   * 整份就是一个对象（或运行记录那条路）时没有这一项。
   */
  sampledFirstItem?: boolean
  /**
   * 粘的那份**原文**（#115 / #119）；探测那条路没有这一格。库里存过的那份重开时把原文一起带回来，
   * 所以保存那一刻手里还握着它——「保存写回库里的就是原文」靠这一格（#119）。
   */
  json?: string | null
  fields: ProbeField[]
}

export interface FieldMappingEditorContext {
  jobId: number
  writeTarget: EditorWriteTarget | null
  source: EditorSourceSample | null
  /**
   * 库里给这个写目标动作**存过**的那份目标样例（#119）；没存过是 null——目标清单走连库探测那条路。
   * 打开时解析一次：之后按 #118 的行为，与「重新探测目标字段」互不影响。
   */
  targetSample: TargetFieldsSample | null
  actions: EditorActionLabel[]
}

export interface SourceStructure {
  sourceKey: string
  /**
   * 这份清单是一整个数组、只按第 1 条推的（粘的那条路会带上，连库探测没有这一项）——#118 目标那一边
   * 也用它注明「按第 1 条推的」。
   */
  sampledFirstItem?: boolean
  fields: ProbeField[]
}

/**
 * 目标字段清单（#118 / #119）：连库探测给 `sourceKey`（界面拿它显示表名），粘的那条路给
 * `paste`（这一次粘的）或 `stored`（库里存过的那份）——与来源那侧同一个口径，界面据此选说法。
 */
export interface TargetFieldsSample {
  sourceKey?: string
  sampledFirstItem?: boolean
  /** 粘的那份原文（#118 / #119）；连库探测（`SourceStructure`）没有这一格。 */
  json?: string | null
  fields: ProbeField[]
}
