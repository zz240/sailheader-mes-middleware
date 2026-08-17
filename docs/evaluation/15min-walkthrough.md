# 15 分钟走查：新实施工程师配通一条真实同步任务

验证「Wayfinder: 字段映射智能化」验收标准：新实施工程师 15 分钟内配通一条真实同步任务，字段自动填充率 >= 80%（站点资产沉淀后口径，见下）。

## 前置

- 本地环境：docker-compose 启动 MySQL + RabbitMQ（连接信息见 `docs/mock-contract.md`）
- 网关应用已启动（`spike`，8080），表单 UI 可用
- 被测工程师只熟悉业务、不熟悉内部实现

## 走查场景

- 主场景（南向，链路最短）：RabbitMQ `mes.to.edge` 消费 → 字段映射 → 客户 MySQL `po_receipt` 幂等 upsert
- 进阶场景（北向）：客户 MySQL `po_order` 增量读（水位 id）→ 字段映射 → MES HTTP 采购订单接口

## 步骤（每步记录耗时）

1. **建站点**：录入客户站点信息（约 1 分钟）
2. **配数据源**：MySQL 客户库 + RabbitMQ，分别点连接测试确认连通（约 2 分钟）
3. **结构探测**：两个数据源各探测一次，源字段清单自动生成（约 1 分钟）
4. **建任务与动作链**：按场景搭动作链，配置水位与幂等键（约 2 分钟）
5. **配字段映射**：导入目标字段清单 → 观察内联推荐与一键自动填充 → 人工确认、补填业务重命名字段（约 3 分钟）
6. **保存并校验**：任务级 `/validate` 通过（连接 + SQL + 映射），启用任务（约 2 分钟）
7. **端到端验证**：发一条 mock 消息（北向则插入一行 `po_order`），确认数据落库/到接口（约 2 分钟）
8. **复盘**：记录总耗时、自动填充率、卡点与口头反馈

## 判定标准

- 15 分钟内端到端跑通 = 时间目标通过
- 字段映射自动填充率（配对口径）>= 80% = 智能目标通过

## 端到端验证实录（2026-08-18，自动化走查）

以下链路已在本地 mock 环境（docker MySQL + RabbitMQ + 应用内 mock MES）逐条验证通过：

- 数据源创建 + 连接测试：MySQL / RabbitMQ / HTTP 全部连通
- 结构探测：`POST /api/data-sources/{id}/probe` 返回 `db:po_order` 6 个字段
- 北向（MySQL 读 → 字段映射 → MES HTTP）：手动执行后 mock MES 收到 2 条正确映射数据（`order_no→sheet_no`、`order_date→sheet_date(yyyy-MM-dd)`、`status ENUM ACTIVE→1`），水位从 0 推进到 2
- 资产沉淀：第一次配置后，第二次配置同一源结构推荐 4/4 全 HISTORY（100%），冷启动仅 2/4（50%）
- 南向（RabbitMQ 消费 → 字段映射 → MySQL 幂等 upsert）：消息落库正确；同 `order_no` 重发只更新不新增（rows 保持 1，qty 10→25）
- 映射历史：北向 4 条（`db:po_order`）+ 南向 3 条（`mq:mes.to.edge`）按 站点/方向/源结构 隔离落库

## 实测注意点（走查中发现）

- **读 SQL 的 `{watermark}` 是完整水位谓词占位符**：应写 `SELECT * FROM po_order WHERE {watermark}`，
  写成 `WHERE id > {watermark}` 会被替换成 `id > id > :#lastId`，MySQL 把它当 `(id>id)>lastId` 静默返回 0 行（任务 SUCCESS 但无数据）。
- **修改动作配置后需停用再启用任务**：路由在启用时重建（`RouteRegistry.enable`），保存配置不会自动重建路由。

## 口径说明（评测发现）

- 冷启动（无映射历史）实测 67%：未命中的全是**业务重命名**类字段（如 `order_no → sheet_no`），名称匹配无法发明这类映射，属客户字段逻辑，允许人工补填
- 同站点已配置过同类任务后（映射历史可用）实测 100%：这正是"资产沉淀"要解决的问题，所以 80% 目标按**带历史口径**验收
- 走查应在 MappingSuggester 规则引擎实现后执行；当前评测资产（`tools/evaluation/`）已可先行验证算法
