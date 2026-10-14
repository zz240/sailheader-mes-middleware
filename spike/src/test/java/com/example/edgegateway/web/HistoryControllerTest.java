package com.example.edgegateway.web;

import com.example.edgegateway.model.JobRun;
import com.example.edgegateway.model.MessageStep;
import com.example.edgegateway.model.MessageStepIndex;
import com.example.edgegateway.service.JobRunGuard;
import com.example.edgegateway.service.MessageHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HistoryController.class)
class HistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 实际注册的请求映射（`@WebMvcTest` 按 `HistoryController` 的路由长出来）——「端点不存在」钉在它上面。 */
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean
    private MessageHistoryService messageHistoryService;

    @MockBean
    private JobRunGuard runGuard;

    @Test
    void listsRuns() throws Exception {
        JobRun run = new JobRun();
        run.setId(1L);
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        when(messageHistoryService.listRuns(anyLong(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(run)));

        mockMvc.perform(get("/api/history/runs").param("jobId", "7").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].jobId").value(7));
    }

    /** #129：业务键是这个接口新接的一格，得原样传下去（口径是「任一消息命中即保留整运行」）。 */
    @Test
    void passesBusinessKeyToRunList() throws Exception {
        when(messageHistoryService.listRuns(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 0L, "records", List.of()));

        mockMvc.perform(get("/api/history/runs").param("businessKey", "SO-2026-0917-0042"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(messageHistoryService).listRuns(nullable(Long.class), nullable(String.class),
                eq("SO-2026-0917-0042"), eq(1), eq(20));
    }

    /**
     * #130：计数条与运行摘要接口跟 `/history/runs` **同一组筛选参数**原样传下去
     * （多一个参数或少传一个，计数与列表就是两套口径）。
     */
    @Test
    void passesRunFiltersToSummary() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 3L, "messages", 5L, "steps", 9L, "failedSteps", 1L,
                        "summaries", List.of(Map.of("runId", 1L, "messageCount", 2L, "steps", 4L,
                                "failedSteps", 1L, "matchedBusinessKeys", List.of("SO-1")))));

        mockMvc.perform(get("/api/history/runs/summary").param("jobId", "7").param("status", "FAILED")
                        .param("businessKey", "SO-2026-0917-0042").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs").value(3))
                .andExpect(jsonPath("$.data.messages").value(5))
                .andExpect(jsonPath("$.data.steps").value(9))
                .andExpect(jsonPath("$.data.failedSteps").value(1))
                .andExpect(jsonPath("$.data.summaries[0].runId").value(1))
                .andExpect(jsonPath("$.data.summaries[0].failedSteps").value(1))
                .andExpect(jsonPath("$.data.summaries[0].matchedBusinessKeys[0]").value("SO-1"));

        // page/size 只决定摘要给哪一页（计数与它们无关），照样原样传；默认值是 1 / 20
        verify(messageHistoryService).runsSummary(eq(7L), eq("FAILED"), eq("SO-2026-0917-0042"), eq(2), eq(50));
    }

    /** 不传分页时按默认 1 / 20（与运行列表同一套默认值）。 */
    @Test
    void summaryDefaultsToFirstPage() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 0L, "messages", 0L, "steps", 0L, "failedSteps", 0L,
                        "summaries", List.of()));

        mockMvc.perform(get("/api/history/runs/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs").value(0));

        verify(messageHistoryService).runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), eq(1), eq(20));
    }

    /** 路径别被 `/runs/{id}` 抢走：`summary` 不是数字，仍要落到聚合这一条上。 */
    @Test
    void summaryPathIsNotSwallowedByRunDetail() throws Exception {
        when(messageHistoryService.runsSummary(nullable(Long.class), nullable(String.class),
                nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("runs", 0L, "messages", 0L, "steps", 0L, "failedSteps", 0L,
                        "summaries", List.of()));

        mockMvc.perform(get("/api/history/runs/summary").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaries").isEmpty());
    }

    /**
     * #131：一条运行的消息步索引（右详情用）。runId 走路径，page / size 原样传给服务层——
     * 索引行不带快照，所以这一页能给得比列表宽（列表那侧封顶 100，这里 1000）。
     */
    @Test
    void listsRunStepIndex() throws Exception {
        MessageStepIndex row = new MessageStepIndex();
        row.setId(2L);
        row.setRunId(9L);
        row.setStep(3);
        row.setStatus("FAILED");
        row.setHasInputSnapshot(true);
        row.setHasDataSnapshot(false);
        when(messageHistoryService.runStepIndex(anyLong(), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(row)));

        mockMvc.perform(get("/api/history/runs/9/steps").param("page", "2").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.records[0].hasInputSnapshot").value(true))
                .andExpect(jsonPath("$.data.records[0].hasDataSnapshot").value(false));

        verify(messageHistoryService).runStepIndex(eq(9L), eq(2), eq(1000));
    }

    /** 不传分页时给「一页尽量多」的默认值：右详情要一次拿全，不是列表那侧的每页 20。 */
    @Test
    void runStepIndexDefaultsToOneBigPage() throws Exception {
        when(messageHistoryService.runStepIndex(anyLong(), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 0L, "records", List.of()));

        mockMvc.perform(get("/api/history/runs/9/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        verify(messageHistoryService).runStepIndex(eq(9L), eq(1), eq(500));
    }

    /** 这一页宽，但也不是无界：索引接口自己的上限（1000）与列表那侧（100）分开管。 */
    @Test
    void runStepIndexClampsSize() throws Exception {
        when(messageHistoryService.runStepIndex(anyLong(), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 0L, "records", List.of()));

        mockMvc.perform(get("/api/history/runs/9/steps").param("size", "999999"))
                .andExpect(status().isOk());

        verify(messageHistoryService).runStepIndex(eq(9L), eq(1), eq(1000));
    }

    /** 索引这条路径别被 `/runs/{id}` 抢走（多一段路径，两边的匹配互不影响）。 */
    @Test
    void runStepIndexPathIsNotSwallowedByRunDetail() throws Exception {
        when(messageHistoryService.runStepIndex(anyLong(), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 0L, "records", List.of()));

        mockMvc.perform(get("/api/history/runs/9/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());

        verify(messageHistoryService, never()).getRun(anyLong());
    }

    @Test
    void getsRunDetail() throws Exception {
        JobRun run = new JobRun();
        run.setId(1L);
        run.setJobId(7L);
        run.setStatus("SUCCESS");
        when(messageHistoryService.getRun(1L)).thenReturn(run);

        mockMvc.perform(get("/api/history/runs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void defaultsStepsToFailed() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(2L);
        step.setStatus("FAILED");
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history/steps").param("jobId", "7").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].status").value("FAILED"));
    }

    /** #136：消息步列表带上所属运行的 jobId（左列表每行要写「任务 #id」）。 */
    @Test
    void carriesRunJobIdInStepList() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(2L);
        step.setStatus("FAILED");
        step.setJobId(7L);
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history/steps").param("jobId", "7").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].jobId").value(7));
    }

    @Test
    void listsHistoryRootAsSteps() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(1L);
        step.setStatus("SUCCESS");
        when(messageHistoryService.listSteps(anyLong(), nullable(Long.class), nullable(String.class),
                nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(Map.of("total", 1L, "records", List.of(step)));

        mockMvc.perform(get("/api/history").param("jobId", "7").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1));
    }

    @Test
    void getsHistoryDetailAsStep() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(9L);
        step.setStatus("FAILED");
        when(messageHistoryService.getStep(9L)).thenReturn(step);

        mockMvc.perform(get("/api/history/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
    }

    @Test
    void redeliversFailedStep() throws Exception {
        MessageStep step = new MessageStep();
        step.setId(5L);
        step.setStatus("REDELIVERED");
        // 任务标识取不到时不拦（那是「这条消息步本身有问题」，交给重投逻辑按原口径报错）
        when(messageHistoryService.jobIdOfStep(5L)).thenReturn(null);
        when(messageHistoryService.redeliver(5L)).thenReturn(step);

        mockMvc.perform(post("/api/history/steps/5/redeliver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REDELIVERED"))
                .andExpect(jsonPath("$.data.stepId").value(5));
    }

    /** #146：同一任务已有运行在跑时，重投不排队，直接 409 + 业务码。 */
    @Test
    void rejectsRedeliverWhileJobIsRunning() throws Exception {
        when(messageHistoryService.jobIdOfStep(5L)).thenReturn(7L);
        when(runGuard.tryAcquire(7L, JobRunGuard.ORIGIN_REDELIVER)).thenReturn(null);

        mockMvc.perform(post("/api/history/steps/5/redeliver"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.message").value("任务正在执行，请稍后再试"));

        verify(messageHistoryService, never()).redeliver(anyLong());
    }

    /**
     * #147：整批重发那条路径在代码与界面上都不存在——那个端点已经删掉，不再有任何处理器。
     *
     * <p>这是票面第一条验收标准的可执行落点：不再靠「全仓 grep 找不到了」交差。</p>
     *
     * <p><b>为什么把断言钉在映射表上而不是响应状态码上</b>：本仓库的全局异常面是刻意的 catch-all
     * （{@code ApiExceptionHandler} 的 {@code @ExceptionHandler(Exception.class)}，口径是「消灭漏网与
     * Spring 默认格式」），它把 Spring 6.1 对**未匹配路径**抛的 {@code NoResourceFoundException} 也收进
     * 统一信封——所以**任何**不存在的路径在这个工程里都回 500 + 业务码，而不是 404。拿 500 当断言，等于
     * 把「未匹配路径答 500」这个全局缺陷固化成期望：那样连一条错拼的路径都能让用例变绿。能真正钉住
     * 「入口不存在」的是**映射表里没有这条路径**：{@link RequestMappingHandlerMapping#getHandler} 对
     * 未匹配的请求返回 {@code null}（映射表由 {@code @WebMvcTest} 从 {@code HistoryController} 实际注册
     * 的路由长出来，不是手抄的清单）。</p>
     *
     * <p><b>全局异常面不在本票范围内</b>（票面 Non-goals）：这里只换掉「入口不存在」这条断言的落点，
     * 不动 {@code ApiExceptionHandler}、也不改其它用例对它的期望。</p>
     */
    @Test
    void batchRedeliverEndpointNoLongerExists() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/history/batches/5/redeliver");
        assertThat(handlerMapping.getHandler(request))
                .as("#147：映射表里没有整批重发这条路径，未匹配时 getHandler 返回 null")
                .isNull();

        verify(messageHistoryService, never()).jobIdOfStep(anyLong());
        verify(messageHistoryService, never()).redeliver(anyLong());
    }

    /**
     * 与上一条同一个断言的**反面**（正对照，免得上面那条因为映射表整个是空的而假绿）：
     * 同一张映射表里，端点被删掉之后**还在**的那条重投路径仍然找得到处理器。
     */
    @Test
    void redeliverEndpointIsStillRegistered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/history/steps/5/redeliver");
        // getHandler 回的是执行链（处理器 + 拦截器），处理器那一格才是 HandlerMethod
        HandlerExecutionChain chain = handlerMapping.getHandler(request);
        assertThat(chain).as("单条重投那条路径仍在映射表里（对照：删掉的是 batches 那条）").isNotNull();
        assertThat(chain.getHandler()).isInstanceOf(HandlerMethod.class);
        assertThat(((HandlerMethod) chain.getHandler()).getMethod().getName()).isEqualTo("redeliver");
    }
}
