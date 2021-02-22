package com.sailheader.middleware.rest.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class MockActionTests {
    @Test
    void exampleTest(@Autowired MockMvc mvc) throws Exception {
        mvc.perform(get("/mw/api/action/stream/query/all")).andExpect(status().isOk());
//        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string("Hello World"));
    }

}
