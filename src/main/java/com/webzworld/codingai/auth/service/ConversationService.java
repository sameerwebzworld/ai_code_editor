package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.ConversationDto;
import com.webzworld.codingai.auth.entity.Conversation;

import java.util.List;

public interface ConversationService {

    List<ConversationDto> getAllByUserId(String userId);

    ConversationDto getByIdAndUserId(String id, String userId);

    Conversation create(String userId, String title,String folderId, String folderPath);

    ConversationDto updateTitle(String id, String userId, String newTitle);

    void delete(String id, String userId);

    Conversation findRaw(String id);

    Conversation save(Conversation conversation);
}