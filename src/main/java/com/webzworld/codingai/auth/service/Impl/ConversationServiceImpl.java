package com.webzworld.codingai.auth.service.Impl;


import com.webzworld.codingai.auth.dto.ConversationDto;
import com.webzworld.codingai.auth.entity.Conversation;
import com.webzworld.codingai.auth.repo.ConversationRepository;
import com.webzworld.codingai.auth.repo.MessageRepository;
import com.webzworld.codingai.auth.service.ConversationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationServiceImpl(ConversationRepository conversationRepository,
                                   MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    public List<ConversationDto> getAllByUserId(String userId) {
        return conversationRepository.findAllByUserId(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public ConversationDto getByIdAndUserId(String id, String userId) {
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        return toDto(conv);
    }

    public Conversation create(String userId, String title, String folderPath) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(title);
        conv.setFolderPath(folderPath);
        return conversationRepository.save(conv);
    }

    public ConversationDto updateTitle(String id, String userId, String newTitle) {
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        conv.setTitle(newTitle);
        return toDto(conversationRepository.save(conv));
    }

    public void delete(String id, String userId) {
        conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
        // Delete all messages first (no FK so we do it manually)
        messageRepository.deleteAllByConversationId(id);
        conversationRepository.deleteById(id);
    }

    public Conversation findRaw(String id) {
        return conversationRepository.findById(id).orElse(null);
    }

    public Conversation save(Conversation conv) {
        return conversationRepository.save(conv);
    }

    private ConversationDto toDto(Conversation c) {
        return new ConversationDto(c.getId(), c.getTitle(), c.getFolderPath(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}