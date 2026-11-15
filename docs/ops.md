# 运维手册（部署 / 升级 / 排障）

面向：部署这套网关的人、盯现场的人。想知道"系统为什么这么设计"看根 `README.md`；想知道"怎么配一条链"看 `user-guide.md`。

## 1. 它长什么样

三个进程，一套 compose：

| 容器 | 端口 | 作用 |
|---|---|---|
| `edge-gateway-app` | 8080 | 应用本体；前端 `dist` 打进同一个镜像，同源访问 |
| `edge-gateway-mysql` | 3306 | 配置库与客户库（可以是同一个实例） |
| `edge-gateway-rabbitmq` | 5672 / 15672 | 南向消息；15672 是管理台 |

## 2. 起停

```bash
cd spike
docker compose up -d --build     # 首次会构建镜像，之后只重建改动的层
docker compose ps                # 三个都 Up
curl http://localhost:8080/actuator/health
```

返回 `{"status":"UP"}` 即可。业务侧验证有两条（README 里也有）：北向看 `GET /mock/mes/received`，南向往队列 `mes.to.edge` 发一条 JSON，看是否落进 `po_receipt`。

## 3. 配置分三层，别混

| 层 | 放哪 | 改完要不要重启 |
|---|---|---|
| 应用配置 | `spike/src/main/resources/application.yml`（库地址、MQ 地址、端口） | 要 |
| 业务配置 | 界面里配：数据源 / 任务 / 动作链 | 不要（启用时自动重建路由） |
| 客户库连接 | 「数据源」页里配 | 不要 |

现场最常犯的错：去改 `application.yml` 想加一个客户库。客户库是**数据源**，不是 yml。ADR-0007 定了连接级粒度。

## 4. 升级

- Flyway 在启动时自动执行 `spike/src/main/resources/db/migration/` 下的迁移，当前到 **V9**。
- **V8、V9 是删表删列**（`mapping_history`、`template`），不可逆。
- 升级前先备份库：`mysqldump -h127.0.0.1 -ugateway -p edge_gateway > backup.sql`。
- 回滚只能回滚镜像版本，库不会自己回退。所以"能回滚"的前提是你先备份了。

## 5. 排障四步

1. 进「消息历史」，找失败的那次运行。
2. 点进去，找状态不是成功的**消息步**——失败发生在哪一步，就在那一步。
3. 分三类原因：
   - **配置错**：SQL 写错、映射字段对不上。这类本该在配置期被拦住，漏到运行时说明校验有缺口。
   - **对端错**：客户库 / HTTP / MQ 连不上或返回错误。
   - **数据错**：某一条消息的内容不合法。
4. 处置：
   - 水位没推进 → 「重跑任务」从未完成的那一批重走。
   - 单条消息 → 断点重投。
   - **两种处置都会让目标端可能再收到一次**，靠业务键 upsert 幂等兜住。不要手工去删对端数据。

这条链的设计依据是「失败即停」：任何一步失败，水位不推进。所以"重跑"永远是安全的动作，"手工修水位"永远不是。

## 6. 数据在哪

| 表 | 存什么 |
|---|---|
| `job_run` | 每次运行 |
| `message_step` | 每条消息穿过每个动作的结果（排障主战场） |
| `watermark` | 增量水位 |
| `job` / `job_action` / `data_source` / `site` | 配置 |
| `mapping_sample` | 映射用的来源样例 |

## 7. 常见故障

| 现象 | 多半是 | 看哪 |
|---|---|---|
| 容器起来又退出 | 配置库连不上，或 Flyway 迁移失败 | `docker logs edge-gateway-app` |
| MQ 启动报声明冲突 | exchange / queue 的类型或持久化参数与已存在的不一致 | ADR-0010；管理台 15672 |
| 某任务一直失败、水位不动 | 对端不可用，或映射字段对不上 | 消息历史里那条失败步的报错 |
| 外部触发返回 401 | 站点 API Key 不对或已轮换 | 「站点」页重新生成 |
| 时间显示不对 | 站点时区没配 | 「站点」页 |

## 8. 日志

```bash
docker logs -f edge-gateway-app
docker logs --tail 100 edge-gateway-mysql
docker logs --tail 100 edge-gateway-rabbitmq
```

应用日志里一次运行的完整链路能对上 `message_step` 的顺序；排查时以数据库为准，日志只用来定位异常堆栈。
