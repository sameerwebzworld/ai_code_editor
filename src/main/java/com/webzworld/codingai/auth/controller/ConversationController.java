package com.webzworld.codingai.auth.controller;


import com.webzworld.codingai.auth.dto.ConversationDto;
import com.webzworld.codingai.auth.dto.GenericResponseDto;
import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.service.Impl.ConversationServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/apiv1/conversations")
public class ConversationController {

    private final ConversationServiceImpl conversationService;

    public ConversationController(ConversationServiceImpl conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ResponseEntity<List<ConversationDto>> getAll(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(conversationService.getAllByUserId(user.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDto> getOne(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(conversationService.getByIdAndUserId(id, user.getId()));
    }

    @PatchMapping("/{id}/title")
    public ResponseEntity<ConversationDto> updateTitle(
            @PathVariable String id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal User user) {
        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(conversationService.updateTitle(id, user.getId(), newTitle));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<GenericResponseDto> delete(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {
        conversationService.delete(id, user.getId());
        return ResponseEntity.ok(new GenericResponseDto("success", "Conversation deleted"));
    }
}