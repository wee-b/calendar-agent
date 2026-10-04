package com.qiniu.back;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.user.User;
import com.qiniu.back.module.dailyNote.mapper.DailyNoteMapper;
import com.qiniu.back.module.mcp.tool.McpToolService;
import com.qiniu.back.module.todo.mapper.TodoDateMapper;
import com.qiniu.back.module.todo.mapper.TodoMapper;
import com.qiniu.back.module.user.mapper.UserMapper;
import com.qiniu.back.util.SaTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 加载真实业务/MCP 配置；数据库和 Redis 由 mock 隔离，不访问开发数据。 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
        "app.sa-token.token-name=yvli-token", "app.sa-token.timeout=604800",
        "app.redis.host=localhost", "app.redis.port=6379", "app.redis.database=0"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BackApplicationTests {

    @MockitoBean UserMapper userMapper;
    @MockitoBean TodoMapper todoMapper;
    @MockitoBean TodoDateMapper todoDateMapper;
    @MockitoBean DailyNoteMapper dailyNoteMapper;
    @MockitoBean RedisConnectionFactory redisConnectionFactory;
    @MockitoBean McpToolService toolService;
    @MockitoSpyBean SaTokenUtil tokenUtil;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void contextLoadsWithOnlyBusinessAndMcpRoutes() {
        var paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).toList();
        assertTrue(paths.containsAll(List.of("/mcp", "/user/info", "/todo", "/calendar/day")));
        assertFalse(paths.contains("/test/getToken"));
        assertFalse(paths.stream().anyMatch(path -> path.startsWith("/chat") || path.startsWith("/memory")));
        assertFalse(mappings.getHandlerMethods().values().stream()
                .anyMatch(method -> method.getBeanType().getPackageName().contains(".assistant")));
    }

    @Test
    void registrationRejectsInvalidInputBeforeDatabaseAccess() throws Exception {
        mvc.perform(post("/user/register").contentType("application/json")
                        .content("{\"phone\":\"13800138000\",\"password\":\"\",\"userName\":\"test\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.ok").value(false));
        verifyNoInteractions(userMapper);
    }

    @Test
    void loginRejectsInvalidPhoneBeforeDatabaseAccess() throws Exception {
        mvc.perform(post("/user/login").contentType("application/json")
                        .content("{\"phone\":\"not-a-phone\",\"password\":\"test-password\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.ok").value(false));
        verifyNoInteractions(userMapper);
    }

    @Test
    void mcpStillRequiresLogin() throws Exception {
        mvc.perform(post("/mcp").contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.ok").value(false));
        verifyNoInteractions(toolService);
    }

    @Test
    void authenticatedMcpPreservesToolCatalogAndStructuredResult() throws Exception {
        User user = new User();
        user.setUserId(23L);
        when(userMapper.selectById(23L)).thenReturn(user);
        doNothing().when(tokenUtil).updateActiveTimeout(23L);
        when(toolService.queryDayDetail("2026-10-03")).thenReturn(
                new McpToolService.DayDetailResult(LocalDate.of(2026, 10, 3), List.of(), "休息"));
        try (var auth = mockStatic(SaTokenUtil.class)) {
            auth.when(() -> SaTokenUtil.getTokenFromRequest(any(HttpServletRequest.class))).thenReturn("test-token");
            auth.when(() -> SaTokenUtil.getUserIdFromToken("test-token")).thenReturn(23L);
            auth.when(() -> SaTokenUtil.validateToken("test-token")).thenReturn(true);
            mvc.perform(post("/mcp").contentType("application/json")
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":\"list-1\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("list-1"))
                    .andExpect(jsonPath("$.result.tools.length()").value(11))
                    .andExpect(jsonPath("$.result.tools[?(@.name == 'batchCreateTodos')].metadata.confirmationRequired")
                            .value(org.hamcrest.Matchers.contains(true)));
            var response = mvc.perform(post("/mcp").contentType("application/json").content("""
                    {"jsonrpc":"2.0","method":"tools/call","id":"call-1",
                     "params":{"name":"queryDayDetail","arguments":{"date":"2026-10-03"}}}
                    """))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("call-1"))
                    .andExpect(jsonPath("$.result.isError").value(false)).andReturn();
            var result = mapper.readTree(response.getResponse().getContentAsString()).at("/result/content/0/text").asText();
            assertEquals("2026-10-03", mapper.readTree(result).get("date").asText());
            assertEquals("休息", mapper.readTree(result).get("dailyNote").asText());
            verify(toolService).queryDayDetail("2026-10-03");
        }
    }
}
