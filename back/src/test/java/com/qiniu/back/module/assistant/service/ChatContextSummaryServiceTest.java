package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.assistant.domain.model.AiDialogue;
import com.qiniu.back.module.assistant.domain.model.ChatContextSummary;
import com.qiniu.back.module.assistant.mapper.AiDialogueMapper;
import com.qiniu.back.module.assistant.mapper.ChatContextSummaryMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatContextSummaryServiceTest {

    @Mock private AiDialogueMapper aiDialogueMapper;
    @Mock private ChatContextSummaryMapper summaryMapper;
    @Mock private ChatModel chatModel;
    @InjectMocks private ChatContextSummaryService service;

    @BeforeAll
    static void initMybatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ChatContextSummary.class);
    }

    @Test
    void doesNotCompressTwentyRoundsOrTheNextExtraRound() {
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(aiDialogueMapper.selectList(any())).thenReturn(rounds(1, 21));

        service.refreshSummary(1L, "session-1");

        verify(chatModel, never()).chat(any(ChatRequest.class));
        verify(summaryMapper, never()).insert(any(ChatContextSummary.class));
    }

    @Test
    void compressesExactlyTwentyRoundsWhenFortyRoundsAccumulated() {
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(aiDialogueMapper.selectList(any())).thenReturn(rounds(1, 40));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("压缩后的摘要"))
                .build());

        service.refreshSummary(1L, "session-1");

        ArgumentCaptor<ChatContextSummary> inserted = ArgumentCaptor.forClass(ChatContextSummary.class);
        verify(summaryMapper).insert(inserted.capture());
        ChatContextSummary summary = inserted.getValue();
        assertEquals(40L, summary.getLastDialogueId());
        assertEquals(40, summary.getMessageCount());
        assertEquals("压缩后的摘要", summary.getSummaryText());
        verify(aiDialogueMapper, never()).delete(any());
        verify(aiDialogueMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void doesNotCompressAgainUntilTwentyMoreRoundsArrive() {
        ChatContextSummary previous = new ChatContextSummary();
        previous.setLastDialogueId(40L);
        previous.setMessageCount(40);
        previous.setSummaryText("已有摘要");
        previous.setStatus("active");
        when(summaryMapper.selectOne(any())).thenReturn(previous);
        when(aiDialogueMapper.selectList(any())).thenReturn(rounds(41, 21));

        service.refreshSummary(1L, "session-1");

        verify(chatModel, never()).chat(any(ChatRequest.class));
        verify(summaryMapper, never()).insert(any(ChatContextSummary.class));
    }

    @Test
    void modelHistoryUsesActiveSummaryAndLatestTwentyRounds() {
        ChatContextSummary summary = new ChatContextSummary();
        summary.setLastDialogueId(40L);
        summary.setSummaryText("更早的20轮已被压缩");
        summary.setStatus("active");
        when(summaryMapper.selectOne(any())).thenReturn(summary);
        when(aiDialogueMapper.selectList(any())).thenReturn(rounds(41, 20));

        List<ChatMessage> history = service.buildCompressedReadonlyHistory(1L, "session-1", 82L);

        assertEquals(1, history.size());
        String text = ((SystemMessage) history.get(0)).text();
        assertTrue(text.contains("更早的20轮已被压缩"));
        assertTrue(text.contains("用户历史：user-41"));
        assertTrue(text.contains("助手历史：assistant-80"));
        assertTrue(!text.contains("user-39"));
    }

    private List<AiDialogue> rounds(long startId, int roundCount) {
        List<AiDialogue> list = new ArrayList<>();
        long id = startId;
        for (int i = 0; i < roundCount; i++) {
            AiDialogue user = new AiDialogue();
            user.setDialogueId(id);
            user.setUserId(1L);
            user.setSessionId("session-1");
            user.setRole("user");
            user.setUserText("user-" + id);
            list.add(user);
            id++;

            AiDialogue assistant = new AiDialogue();
            assistant.setDialogueId(id);
            assistant.setUserId(1L);
            assistant.setSessionId("session-1");
            assistant.setRole("assistant");
            assistant.setAiResult("assistant-" + id);
            list.add(assistant);
            id++;
        }
        return list;
    }
}
