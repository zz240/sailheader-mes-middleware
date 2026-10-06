export type DataSourceType = 'HTTP' | 'MYSQL' | 'SQLSERVER' | 'RABBITMQ'

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

export type ActionType =
  | 'READ_SQL' | 'WRITE_UPSERT' | 'HTTP_CALL' | 'MQ_CONSUME' | 'MQ_PUBLISH' | 'FIELD_MAP'
  // 拆分（#140）：一条消息里的一个数组摊成 N 条子消息，是基数变化动作（1 → N）
  | 'SPLIT'

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
  /**
   * 所属运行的 `jobId`（#136）：列表接口按页补上的**投影**，`message_step` 表没有这一列，
   * 所以消息步详情那条路不带它——取不到时界面写「任务 —」，不要按行补拉运行（那是 N+1）。
   */
  jobId?: number | null
  step: number
  actionName?: string | null
  status: string
  /**
   * 两个快照原文。**只有「列表」与「详情」两条路带它们**（`/history/steps`、`/history/steps/{id}`）；
   * 右详情走的是索引那条路（`/history/runs/{id}/steps`，见下），那条**不取这两列**（TEXT，单条上限 64 KB）。
   */
  dataSnapshot?: string | null
  inputSnapshot?: string | null
  /**
   * 快照落没落（#131）：索引那条路用这两格回答「步骤链上那两个小点亮不亮」——
   * 快照本身不在索引里，点开某一步再按 id 取整行（#132 的快照面板走那条）。
   */
  hasDataSnapshot?: boolean
  hasInputSnapshot?: boolean
  error?: string | null
  createdAt?: string | null
  attempt?: number | null
  /**
   * 本步的**动作耗时**（毫秒，#134）：这个动作从开始执行到返回的墙钟时长。
   *
   * 可空，而且是**真的可空**：V4 迁移之前落库的消息步没测过，界面那一格写 `—` 而不是 `0 ms`
   * （「没测」与「测得就是 0 ms」是两件事）。两条取数路都带它：索引那条（步骤链 + 单步卡）
   * 与整行那条。
   */
  durationMs?: number | null
  businessKey?: string | null
  /**
   * 这一步所属的**批号**（#139 / ADR-0017）：某一个动作上、被同一次基数变化动作归到一起的那组消息
   * 的编号。每执行一次现生成，随消息在链上**继承到下一个基数变化动作为止**——同一批的每一步同号。
   *
   * 两条取数路都带它（索引那条路是右详情按批分组的依据）；可空：MQ 消费 / 任务输入那些链没有批号，
   * 这一列之前落库的老数据也没有（V5 迁移不回溯）。空 = 「这一步没有批次」，不是「这条自己一批」。
   */
  batchId?: string | null
  /**
   * **批内序号**（#139）：拆分的元素顺序 / 合并的攒批顺序，从 1 起。
   * 只在拆分与合并时写；读取批产生的那些行**留空**（读取批里「第几条」没有额外语义）。
   */
  batchSeq?: number | null
  /** **父消息标识**（#139）：这条消息是从哪条消息拆出来的；管谱系，与批号（管分组）各管一件事。 */
  parentMessageId?: string | null
}

/**
 * 运行摘要（#130）：某一条运行在当前筛选集里的规模，由 `/history/runs/summary` 一次带回
 * （`message_step` 上没有 jobId，逐行补拉就是 ADR-0016 决策 6 拒绝的 N+1）。
 *
 * 名字分工：`stepCount` 是**这一条运行**的消息步数，与计数条的 `steps`（整条筛选集的合计）
 * 不是同一个范围——两处都叫 `steps` 时最容易读成同一个数。
 *
 * 不变量：三个数与运行列表用同一组筛选算出来，所以界面上的行摘要与按 `runId` 取到的消息步数
 * 对得上（`failedSteps` 对着 `status=FAILED`）。
 */
export interface RunSummary {
  runId: number
  /** 消息数（按 messageId 去重）。 */
  messageCount: number
  /** 这条运行的消息步数。 */
  stepCount: number
  /** 失败步数（status = FAILED）。 */
  failedSteps: number
  /** 命中的业务键（运行侧筛选是「任一消息命中即保留整运行」，这里回的是命中的那几个）。 */
  matchedBusinessKeys: string[]
}

/**
 * 计数条（#130）：**整条筛选集**（不含分页）的四个数。
 *
 * 这里的 `steps` 是整个筛选集的消息步合计；每运行的那一格叫 `RunSummary.stepCount`。
 */
export interface HistoryCounts {
  runs: number
  messages: number
  steps: number
  failedSteps: number
}

/** 只读聚合接口的应答：四个计数（整条筛选集）+ 每运行摘要（当前这一页）。 */
export interface RunsSummary extends HistoryCounts {
  summaries: RunSummary[]
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
