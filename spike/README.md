# Edge Gateway Spike

用 Apache Camel + Spring Boot 验证一条北向链路和一条南向链路。

## 启动依赖

```powershell
docker compose -f spike/docker-compose.yml up -d
```

## 启动应用

```powershell
cd spike
..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run
```

## 验证

- 北向（MySQL -> mock MES）：应用启动后 5 秒起，每 30 秒读一次 `po_order`，按 `id` 增量，字段映射后 POST 到 `http://localhost:8080/mock/mes/purchase-order`。
- 查看收到的 MES 数据：`GET http://localhost:8080/mock/mes/received`。
- 南向（RabbitMQ -> MySQL）：向队列 `mes.to.edge` 发一条消息，例如 `{"orderNo":"MO-1001","material":"M-9","qty":10}`，应用会幂等 upsert 到 `po_receipt`。

## 说明

- 字段映射采用结构化规则（`FieldMappingRule`），不暴露占位符字符串。
- 增量为 `id` 水位，存于 `watermark` 表；南向写入按 `order_no` 幂等 upsert。

## 数据库迁移（Flyway）

- 建表与版本化迁移由 Flyway 负责：`src/main/resources/db/migration/V1__init_schema.sql`（建表+种子）、`V2+` 增量；应用启动时自动执行。
- 存量库（旧 `db/init.sql` 建的、无 `flyway_schema_history`）自动 baseline 至 V1，只执行 V2 起的增量——无需手工迁移。
- 测试库仍用 `src/test/resources/schema.sql`（H2），不走 Flyway。
