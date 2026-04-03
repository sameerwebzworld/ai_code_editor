package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.ChatRequestDto;
import com.webzworld.codingai.auth.dto.ChatResponseDto;
import com.webzworld.codingai.auth.dto.FolderDto;
import com.webzworld.codingai.auth.dto.MessageDto;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {

    ChatResponseDto sendMessage(ChatRequestDto request, String userId);

    SseEmitter sendMessageStream(ChatRequestDto request, String userId);

    List<MessageDto> getHistory(String conversationId, String userId);

    List<FolderDto> getSidebarData(String userId);
}