package com.qiniu.back.module.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.config.AiProperties;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ImageAgentTest {

    @Test
    void generatesImageThroughConfiguredImageAgent() {
        AiProperties properties = properties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ark.example.com/api/v3/images/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ark-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"seedream-test",
                          "size":"2K",
                          "output_format":"jpeg",
                          "response_format":"url",
                          "stream":false,
                          "watermark":true
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"data":[{"url":"https://images.example.com/plan.jpg","size":"2048x2048"}]}
                        """, MediaType.APPLICATION_JSON));
        ImageAgent agent = new ImageAgent(properties, new ObjectMapper(), builder);
        PlanDraft draft = new PlanDraft();
        draft.setPlanJson("{\"goal\":\"复习\",\"todos\":[]}");

        String reply = agent.generate(draft);

        assertEquals("已生成规划示意图：\n\n![规划示意图](https://images.example.com/plan.jpg)", reply);
        server.verify();
    }

    @Test
    void requiresImageProviderCredentials() {
        AiProperties properties = properties();
        properties.getProviders().get("ark").setApiKey("");
        ImageAgent agent = new ImageAgent(properties, new ObjectMapper(), RestClient.builder());

        BusinessException error = assertThrows(BusinessException.class,
                () -> agent.generate(new PlanDraft()));

        assertEquals("ImageAgent provider 的 api-key 和 base-url 必须配置", error.getMessage());
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        AiProperties.Provider provider = new AiProperties.Provider();
        provider.setType("image-generation");
        provider.setApiKey("ark-key");
        provider.setBaseUrl("https://ark.example.com/api/v3");
        properties.getProviders().put("ark", provider);

        AiProperties.AgentModel image = new AiProperties.AgentModel();
        image.setProvider("ark");
        image.setModel("seedream-test");
        image.setSize("2K");
        image.setWatermark(true);
        properties.getAgents().put("image", image);
        return properties;
    }
}
