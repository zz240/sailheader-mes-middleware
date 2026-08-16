# 环境 Mock 契约

Spike 阶段使用的四个 mock 环境事实，全部可本地启动。密码均为占位符/本地默认值。

## 1. Mock MES 北向接口

- URL: `POST http://localhost:8080/mock/mes/purchase-order`
- 鉴权: `Authorization: Bearer mock-token`
- 请求体:

```json
{
  "sheet_no": "PO-2026-001",
  "vendor_code": "V001",
  "sheet_date": "2026-08-14",
  "status": 1
}
```

- 响应体:

```json
{ "code": "0", "message": "ok" }
```

## 2. RabbitMQ

- 连接: `amqp://localhost:5672`，vhost `/`，用户名/密码 `guest/guest`
- 南向消费队列: `mes.to.edge`
- 消费方式: 长连接订阅
- 消息样例:

```json
{ "orderNo": "MO-1001", "material": "M-9", "qty": 10 }
```

## 3. 客户 MySQL

- 连接: `jdbc:mysql://localhost:3306/edge_gateway`，用户名/密码 `gateway/gateway`
- 北向源表 `po_order`:

```sql
CREATE TABLE po_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL UNIQUE,
  vendor_code VARCHAR(32),
  order_date DATETIME,
  status VARCHAR(16),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

- 增量水位字段: `id`；幂等键字段: `order_no`
- 南向目标表 `po_receipt`:

```sql
CREATE TABLE po_receipt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(32) NOT NULL UNIQUE,
  material VARCHAR(64),
  qty INT,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

## 4. 端到端验收样例

- 源数据（`po_order` 一行）:

```json
{ "id": 1, "order_no": "PO-2026-001", "vendor_code": "V001", "order_date": "2026-08-14 10:00:00", "status": "ACTIVE" }
```

- 期望的 MES 目标数据（北向字段映射后）:

```json
{ "sheet_no": "PO-2026-001", "vendor_code": "V001", "sheet_date": "2026-08-14", "status": 1 }
```

- 南向 RabbitMQ 消息 → MySQL `po_receipt` 幂等 upsert，键为 `order_no`。
