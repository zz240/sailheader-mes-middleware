# Camel 4.10.2 spring-rabbitmq 组件：exchange 声明/绑定能力事实报告

> 用途：作为代码改造依据。背景：Spring Boot 3.4.5 + Camel 4.10.2，现有消费者端点
> `from("spring-rabbitmq:<exchangeName>?queues=<q>&routingKey=<rk>&autoDeclare=true")`，
> 想支持"自定义 exchange 名称 + 类型（direct/topic/fanout）+ bindingKey"全部显式配置。
> 本文只做事实调查，不含代码改动。
>
> 说明：camel.apache.org 的 4.10.x 旧版文档页已下线（访问返回 404）；
> 4.10.2 的官方文档以仓库 `camel-4.10.2` 标签下的组件 adoc 为准（线上 4.18.x LTS 渲染页与之同源，可对照）。
> 相关源码均以 Apache Camel GitHub 仓库 `camel-4.10.2` 标签为准。

## 0. 构件与版本事实

| 事实 | 来源 |
| --- | --- |
| Starter 真实 GAV 是 `org.apache.camel.springboot:camel-spring-rabbitmq-starter`（不是 `org.apache.camel` 组），4.10.2 版本存在（同组下 `org.apache.camel:camel-spring-rabbitmq` 无此 starter；`camel-spring-rabbitmq-starter` 的 4.10.2 在 `org.apache.camel.springboot` 组发布） | [Maven Central metadata](https://repo1.maven.org/maven2/org/apache/camel/springboot/camel-spring-rabbitmq-starter/maven-metadata.xml) |
| starter 基于核心组件 `org.apache.camel:camel-spring-rabbitmq`（自 Camel 3.8 起），底层使用 spring-rabbit/spring-amqp（`org.springframework.amqp`） | [组件 adoc（tag camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/docs/spring-rabbitmq-component.adoc) |
| Spring Boot 3.4.5 BOM 管理 `spring-amqp.version=3.2.5`（spring-rabbit 属 spring-amqp 家族构件） | [spring-boot-dependencies 3.4.5 pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.5/spring-boot-dependencies-3.4.5.pom) |

## 1. Consumer（@from）支持的 exchange 声明/绑定相关端点参数

来源（参数定义全部来自 @UriParam 注解）：[SpringRabbitMQEndpoint.java（tag camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQEndpoint.java)

| 参数 | 默认值 | 作用（源码英文描述要点） | 标签 |
| --- | --- | --- | --- |
| `exchangeName`（URI 路径段，必填） | 无 | 生产者发送的目标 exchange；**消费者场景下 = 队列将被绑定到的 exchange**。RabbitMQ 默认 exchange 用 `default` 表示（组件内部映射为空串） | common/path |
| `exchangeType` | **`direct`**（enums: `direct,fanout,headers,topic`） | **exchange 的类型**，消费端声明 exchange 时使用 | consumer |
| `autoDeclare` | **`true`**（端点与组件级均默认 true） | "Specifies whether the consumer should auto declare binding between exchange, queue and routing key when starting."——启动时自动声明 exchange/queue/binding | consumer |
| `routingKey` | `""` | 路由键。**在 autoDeclare 时它同时被用作 binding 的 routing key**；在生产者端它是模板默认发送 key | common |
| `queues` | 无（未配置则生成唯一名队列） | 消费/发送的队列名，逗号分隔多个；未配置时 Camel 生成唯一队列名 | common |
| `args`（多值，前缀 `arg.`） | 无 | 声明各元素的额外参数，子前缀：`arg.consumer.`、`arg.exchange.`、`arg.queue.`、`arg.binding.`、`arg.dlq.exchange.`、`arg.dlq.queue.`、`arg.dlq.binding.`（文档示例：`arg.queue.x-message-ttl=60000`）。**没有 `declareArgs`/`arguments` 参数，多值声明参数就是这个 `args`** | advanced |
| `deadLetterExchange` / `deadLetterQueue` / `deadLetterRoutingKey` | 无 | 死信 exchange/queue/routing key（autoDeclare 时会一并声明 DLQ） | common |
| `deadLetterExchangeType` | `direct`（enums: `direct,fanout,headers,topic`） | 死信 exchange 的类型 | common |
| （仅 producer 侧）`autoDeclareProducer`、`skipDeclareExchange`、`skipDeclareQueue`、`skipBindQueue` | false / false / false / false | 控制生产者侧自动声明的开关与跳过项，**与消费者无关** | producer |

**关键否定事实：**
- **不存在 `bindingKey` 端点参数**。auto-declare 的 binding 使用的就是 `routingKey`（见第 3 节代码）。
- 不存在独立的 `declareArgs`/`arguments` 参数（`ExchangeBuilder`/`QueueBuilder` 的参数由 `args` + 前缀表达式提供）。
- exchange 名称不需要"新参数"：URI 路径第一段（`exchangeName`）就是它，消费端绑定到该 exchange——这正是现有端点的形态。

文档出处（含 "Auto declare exchanges, queues and bindings" 章节，说明 `args` 的 `exchange.*`/`queue.*` 规则与默认值：exchange durable 默认 true / autoDelete 默认 false；queue durable 默认 false / autoDelete 默认 false / exclusive 默认 false）：
- [组件 adoc（camel-4.10.2 tag）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/docs/spring-rabbitmq-component.adoc)
- [线上渲染版（4.18.x LTS，4.10.x 页已下线）](https://camel.apache.org/components/4.18.x/spring-rabbitmq-component.html)

## 2. autoDeclare=true 时底层如何声明 exchange（类型可配吗？）

**声明主体不是组件级 RabbitAdmin bean 常驻声明，而是消费端启动时逐项调用 `AmqpAdmin`（即 `RabbitAdmin`）**：

- 调用链：`SpringRabbitMQConsumer.doStart → prepareAndStartListenerContainer → listenerContainer.afterPropertiesSet() → if (isAutoDeclare()) getEndpoint().declareElements(listenerContainer)`。
  来源：[SpringRabbitMQConsumer.java（camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQConsumer.java)
- `AmqpAdmin` 来源（二选一）：
  1. 组件级 `amqpAdmin` 属性（`@Metadata(autowired = true)`，"Optional AMQP Admin service to use for auto declaring elements"）——Spring 容器中有 `AmqpAdmin`/`RabbitAdmin` bean 时被注入（Spring Boot 默认配置会提供，见下）；来源 [SpringRabbitMQComponent.java（camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQComponent.java)
  2. 否则由 [DefaultListenerContainerFactory.java（camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/DefaultListenerContainerFactory.java) 兜底创建 `new RabbitAdmin(connectionFactory)`（并设置 `ignoreDeclarationExceptions`）。
- **exchange 声明代码**（SpringRabbitMQEndpoint.declareElements）：
  ```
  ExchangeBuilder eb = new ExchangeBuilder(exchangeName, getExchangeType());  // 类型 = exchangeType 参数！
  eb.durable(durable);        // 默认 true，来自 arg.exchange.durable
  if (autoDelete) eb.autoDelete();  // 默认 false，来自 arg.exchange.autoDelete
  eb.withArguments(map);      // 其余 x-args 来自 arg.exchange.*
  admin.declareExchange(eb.build());
  ```
  来源：[SpringRabbitMQEndpoint.java（camel-4.10.2），declareElements 方法](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQEndpoint.java)

**结论：**
- **autoDeclare 默认声明的是 `direct` exchange**（因为 `exchangeType` 默认 `direct`），且 durable=true、autoDelete=false。
- **类型完全可配**：直接传 `exchangeType=topic` / `exchangeType=fanout` / `exchangeType=headers`（enums 限定了这四种），`ExchangeBuilder(exchangeName, "topic")` 就会声明 topic exchange。无需改 Camel 代码。
- exchange 名称即 URI 第一段（`exchangeName`），已是显式配置。

## 3. binding 在 autoDeclare 下如何建立；fanout 是否需要 routingKey

**binding 声明代码**（同一个 `declareElements` 方法，对 `queues` 逗号分割的每个队列执行一次）：

```java
Binding binding = new Binding(
        qn, Binding.DestinationType.QUEUE, exchangeName, routingKey, getBindingArgs());
admin.declareBinding(binding);
```

来源：[SpringRabbitMQEndpoint.java（camel-4.10.2），declareElements 方法](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQEndpoint.java)

事实：
- **binding 的 routing key = 端点参数 `routingKey`（默认空串）**；**没有独立的 `bindingKey` 参数**。binding 的 arguments 来自 `arg.binding.*`（如 headers exchange 的 `x-match`）。
- 端点未配 `queues` 时，Camel 会生成唯一名队列（非 durable），并把该队列名回填到 listener container。
- 队列声明默认值：durable=false、autoDelete=false、exclusive=false（来自 `arg.queue.*`，见第 1 节 adoc 表）。
- **fanout 不需要（也没用）routingKey**：RabbitMQ 官方文档明确 "Fanout exchanges ... route a copy of every message published to them to every queue ... bound to it. The message's routing key is completely ignored."（https://www.rabbitmq.com/docs/exchanges#fanout）。因此 fanout 场景 `routingKey` 留空即可，Camel 会用空串建 binding，broker 忽略它。
- **topic** 的 binding key 是模式串（如 `order.#`、`regions.na.cities.*`，`*` 匹配一段、`#` 匹配零或多段），见 https://www.rabbitmq.com/docs/exchanges#topic ；**direct** 的 binding key 需与发布路由键精确一致，见 https://www.rabbitmq.com/docs/exchanges#direct 。
- **表达力边界**：一个消费者端点 = 一个 exchange 名 + 一个 exchange 类型 + 一个 routingKey（binding key），该端点声明的**所有**队列共用同一个 binding key。若需要"同一队列多个 binding"或"不同队列不同 binding key"，端点参数就不够了；此时用 Spring AMQP 的 `RabbitAdmin`/`Declarables`/`QueueBinding`（`Binding`）自建声明（见第 4 节 b 路径）。

## 4. 若需自建声明：最低添加点（依赖已具备）

- 依赖已就位：`camel-spring-rabbitmq-starter` → `camel-spring-rabbitmq` → `spring-rabbit`（spring-amqp），不需要新增依赖。
- Spring Boot 3.4.5 **默认自动配置 `AmqpAdmin`（`RabbitAdmin`）bean**：`@ConditionalOnProperty(prefix="spring.rabbitmq", name="dynamic", matchIfMissing=true)` 且 `@ConditionalOnMissingBean`，即 `spring.rabbitmq.dynamic` 未配置（默认 true）时自动注册 `new RabbitAdmin(connectionFactory)`。来源：[RabbitAutoConfiguration.java（v3.4.5）](https://github.com/spring-projects/spring-boot/blob/v3.4.5/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/amqp/RabbitAutoConfiguration.java)
- 该 `RabbitAdmin`（Spring bean）在连接建立后会自动声明**应用上下文中的所有 Declarable**：javadoc 原文 "afterPropertiesSet: If autoStartup is set to true, registers a callback on the ConnectionFactory to declare all exchanges and queues in the enclosing application context"；"initialize(): Declares all the exchanges, queues and bindings in the enclosing application context"。来源：[RabbitAdmin javadoc（spring-amqp 3.1.x API）](https://docs.spring.io/spring-amqp/docs/3.1.x/api/org/springframework/amqp/rabbit/core/RabbitAdmin.html)
- 因此自建声明的最低添加点 = 定义 Spring bean：`Exchange`（`DirectExchange`/`TopicExchange`/`FanoutExchange`/`HeadersExchange`，或泛化 `ExchangeBuilder(...)`）+ `Queue` + `Binding`（含 routing key 与 arguments），可打包成一个 `Declarables` bean；由上述自动配置的 `RabbitAdmin` 完成声明。
- 注意：Camel 组件级 `amqpAdmin`（Autowired）在 Spring Boot 项目里通常就指向这个 `RabbitAdmin` bean，即 Camel 与 Spring 声明走同一管理员。若同时开启 Camel `autoDeclare=true` 又自建同一 exchange/queue 的 Declarables，且定义不一致（如同名 exchange 类型不同、队列参数不同），会触发 RabbitMQ `PRECONDITION_FAILED`（406）错误；建议二选一（Camel 端点声明 或 Spring 声明），或使用组件级 `ignoreDeclarationExceptions`（[SpringRabbitMQComponent.java](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQComponent.java)）容忍声明异常。
- 另一个可选项：给 Camel 组件配置 `amqpAdmin` 或自定义 `ListenerContainerFactory`（[DefaultListenerContainerFactory.java](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/DefaultListenerContainerFactory.java)），替换声明行为——一般不需要。

## 5. 结论：改动路径

**(a) 只加端点参数即可满足"自定义 exchange 名称 + 类型 + binding"——推荐，也是最小改法。**

| 需求 | 现有/需新增参数 | 说明 |
| --- | --- | --- |
| 自定义 exchange 名称 | URI 路径（`exchangeName`，已支持） | 现有端点已是"路径=exchange 名" |
| 自定义 exchange 类型 | `exchangeType=direct\|topic\|fanout\|headers`（新增；默认 direct） | 唯一需要新增的"类型"参数 |
| 自定义 binding key | `routingKey=<pattern>`（已支持，默认空串） | 组件无 `bindingKey`；binding key 就是 `routingKey` |
| 队列 | `queues=<q>`（已支持） | 逗号分隔可多队列，但共用同一 binding key |
| 自动声明开关 | `autoDeclare=true`（已支持，默认 true） | 关闭后需自建声明 |
| 声明附加参数 | `args=arg.exchange.*` / `arg.queue.*` / `arg.binding.*`（可选） | durable/autoDelete/exclusive/x-args、headers 的 x-match |

示例（topic 场景）：`from("spring-rabbitmq:my.topic.x?exchangeType=topic&queues=my.queue&routingKey=order.#&autoDeclare=true")`。
现有端点 `spring-rabbitmq:<n>?queues=<q>&routingKey=<rk>&autoDeclare=true` 保持则仍声明 **direct** exchange + binding(rk)，行为与前提背景一致。

注意事项：
- 端点同时被 `to()` 复用时，`routingKey` 也会成为生产者默认发送 key（`createInOnlyTemplate`/`createInOutTemplate` 中 `template.setRoutingKey(getRoutingKey())`），来源 [SpringRabbitMQEndpoint.java](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQEndpoint.java)；消费端单独使用时无冲突。
- 生产者侧有独立的 `autoDeclareProducer` + `skipDeclareExchange`/`skipDeclareQueue`/`skipBindQueue` 开关，来源 [SpringRabbitMQProducer.java（camel-4.10.2）](https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQProducer.java)。

**(b) 必须自建 RabbitAdmin/Declarables 声明的场景（否则不必）：**
- 同一队列需要多个 binding（多个 routing key/多个 exchange）；
- 一个端点的不同队列需要不同 binding key；
- 生产环境希望拓扑统一由 Spring 声明、Camel 关掉 `autoDeclare`（集中管理、双保险）；
- headers exchange 需要多组 `x-match` 绑定参数等 `args` 前缀不方便表达的情形。

对应声明对象（均已随 starter 在 classpath）：`Exchange`（`DirectExchange`/`TopicExchange`/`FanoutExchange`/`HeadersExchange`）、`Queue`、`Binding`（或聚合为 `Declarables`），由 Spring Boot 自动配置的 `RabbitAdmin` 声明；无需新增依赖。若改用自建声明，应同时把 Camel 端点 `autoDeclare` 置为 `false`（或保持声明参数完全一致），避免 406/重复声明。

## 参考来源索引（全部官方/一手）

- Camel 组件文档（adoc，camel-4.10.2 tag）：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/docs/spring-rabbitmq-component.adoc
- Camel 线上渲染文档（4.18.x LTS；4.10.x 页已下线）：https://camel.apache.org/components/4.18.x/spring-rabbitmq-component.html
- SpringRabbitMQEndpoint.java：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQEndpoint.java
- SpringRabbitMQConsumer.java：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQConsumer.java
- SpringRabbitMQComponent.java：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQComponent.java
- DefaultListenerContainerFactory.java：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/DefaultListenerContainerFactory.java
- SpringRabbitMQProducer.java：https://github.com/apache/camel/blob/camel-4.10.2/components/camel-spring-rabbitmq/src/main/java/org/apache/camel/component/springrabbit/SpringRabbitMQProducer.java
- starter 版本元数据（Maven Central）：https://repo1.maven.org/maven2/org/apache/camel/springboot/camel-spring-rabbitmq-starter/maven-metadata.xml
- Spring Boot 3.4.5 BOM：https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/3.4.5/spring-boot-dependencies-3.4.5.pom
- RabbitAutoConfiguration（v3.4.5）：https://github.com/spring-projects/spring-boot/blob/v3.4.5/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/amqp/RabbitAutoConfiguration.java
- RabbitAdmin javadoc（spring-amqp 3.1.x）：https://docs.spring.io/spring-amqp/docs/3.1.x/api/org/springframework/amqp/rabbit/core/RabbitAdmin.html
- RabbitMQ Exchanges 官方文档（fanout/topic/direct/binding 语义）：https://www.rabbitmq.com/docs/exchanges
