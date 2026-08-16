# 以 Apache Camel 作为嵌入内核

边缘网关不自研集成引擎，而是把 Apache Camel 作为内嵌内核跑在 Spring Boot 应用里；自研部分只保留"领域配置 → Camel 路由"的翻译层和表单式配置界面。

## Considered Options

- 自研引擎：灵活但重复造轮子，连接器、错误处理、调度都要长期自行维护。
- Node-RED：可嵌入且自带编辑器，但引入 Node/JS 技术栈，与现有 Java/Spring 栈割裂。
- n8n：Sustainable Use License（fair-code，非 OSI），嵌入商业产品有许可风险。
- Apache Camel：Apache 2.0、Java、组件按需引入、可嵌入、路由可写 Java/YAML/XML，最契合本项目的 Java 栈和"配置即路由"需求。

## Consequences

- 现场配置界面采用表单式，不使用 Kaoto 画布作为现场配置入口。
- Camel 组件按需引入（http/sql/jdbc/mq/timer/log/jackson/jsonpath），不引入 camel-all。
- 错误处理、重试、类型转换等运行机制复用 Camel 内置能力，不自研。
