package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.ChatRequestDto;
import com.webzworld.codingai.auth.dto.ChatResponseDto;
import com.webzworld.codingai.auth.dto.MessageDto;

import java.util.List;

public interface ChatService {

    ChatResponseDto sendMessage(ChatRequestDto request, String userId);

    List<MessageDto> getHistory(String conversationId, String userId);
}