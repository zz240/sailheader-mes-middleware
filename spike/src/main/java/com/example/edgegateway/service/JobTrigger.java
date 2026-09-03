package com.example.edgegateway.service;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;

/**
 * “任务如何被触发”的 adapter：持有触发端点（{@code direct:job-&lt;id&gt;}）的拼串规则，把 Camel
 * producer 的创建/调用藏在实现里。同步执行（requestBody 语义）：执行完返回最终 body，失败抛异常。
 * 手动/外部执行（JobExecutionService）与定时调度（JobScheduler）共用这一接口，端点规则不再散落在两处。
 */
@Component
public class JobTrigger {

    private final CamelContext camelContext;

    public JobTrigger(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    /** 触发任务并同步等待执行完成；返回该次调用的最终 body；失败抛异常（由调用方决定回写/记日志）。 */
    public Object trigger(Long jobId, Object body) {
        try (ProducerTemplate producer = camelContext.createProducerTemplate()) {
            return producer.requestBody("direct:job-" + jobId, body);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // producer 关闭等检查型异常（如 IOException）包装为运行时，保持接口只抛运行时
            throw new IllegalStateException("触发任务失败: " + e.getMessage(), e);
        }
    }
}
