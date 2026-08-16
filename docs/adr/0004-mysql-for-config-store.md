# 统一使用 MySQL 作为配置库

边缘网关自身的配置库（任务、数据源、模板、水位、死信）与客户数据库统一使用 MySQL，减少现场需要维护的数据库种类。

## Considered Options

- PostgreSQL：原设计采用的配置库，但会额外引入第二套数据库。
- MySQL：与客户数据库统一，现场运维只维护一种数据库，是最终选择。

## Consequences

- 水位、死信、任务、模板等均存 MySQL。
- MVP 的 JDBC 连接器以 MySQL 为目标，其他数据库后续按需增加。
