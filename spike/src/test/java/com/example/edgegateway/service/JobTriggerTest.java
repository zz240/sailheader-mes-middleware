package com.example.edgegateway.service;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JobTrigger 是“任务如何被触发”的 adapter seam：持有 direct:job-&lt;id&gt; 拼串与 producer 调用。 */
class JobTriggerTest {

    private final CamelContext camelContext = mock(CamelContext.class);
    private final ProducerTemplate producer = mock(ProducerTemplate.class);
    private final JobTrigger jobTrigger = new JobTrigger(camelContext);

    @Test
    void triggersViaDirectEndpointAndReturnsBody() {
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        when(producer.requestBody(eq("direct:job-7"), eq("INPUT"))).thenReturn("REPLY");

        Object reply = jobTrigger.trigger(7L, "INPUT");

        assertThat(reply).isEqualTo("REPLY");
        verify(producer).requestBody("direct:job-7", "INPUT");
    }

    @Test
    void propagatesFailure() {
        when(camelContext.createProducerTemplate()).thenReturn(producer);
        doThrow(new RuntimeException("boom")).when(producer).requestBody(anyString(), any(Object.class));

        assertThatThrownBy(() -> jobTrigger.trigger(7L, "x"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
    }
}
