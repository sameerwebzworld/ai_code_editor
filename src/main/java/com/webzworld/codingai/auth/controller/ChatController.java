//package com.webzworld.codingai.auth.controller;
//
//import com.webzworld.codingai.auth.dto.ChatRequestDto;
//import com.webzworld.codingai.auth.dto.ChatResponseDto;
//import com.webzworld.codingai.auth.dto.FolderDto;
//import com.webzworld.codingai.auth.dto.MessageDto;
//import com.webzworld.codingai.auth.entity.User;
//import com.webzworld.codingai.auth.service.ChatService;
//import jakarta.validation.Valid;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/apiv1/chat")
//public class ChatController {
//
//    private final ChatService chatService;
//
//    public ChatController(ChatService chatService) {
//        this.chatService = chatService;
//    }
//
//    @PostMapping("/message")
//    public ResponseEntity<ChatResponseDto> sendMessage(
//            @Valid @RequestBody ChatRequestDto request,
//            @AuthenticationPrincipal User user) {
//        ChatResponseDto response = chatService.sendMessage(request, user.getId());
//        return ResponseEntity.ok(response);
//    }
//
//    @GetMapping("/history/{conversationId}")
//    public ResponseEntity<List<MessageDto>> getHistory(
//            @PathVariable String conversationId,
//            @AuthenticationPrincipal User user) {
//        List<MessageDto> messages = chatService.getHistory(conversationId, user.getId());
//        return ResponseEntity.ok(messages);
//    }
//
//    @GetMapping("/sidebar")
//    public ResponseEntity<List<FolderDto>> getSidebar(
//            @AuthenticationPrincipal(expression = "id") String userId
//    ) {
//        return ResponseEntity.ok(chatService.getSidebarData(userId));
//    }
//}

package com.webzworld.codingai.auth.controller;

import com.webzworld.codingai.auth.dto.*;
import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/apiv1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/message")
    public ResponseEntity<ChatResponseDto> sendMessage(
            @Valid @RequestBody ChatRequestDto request,
            @AuthenticationPrincipal User user) {
        ChatResponseDto response = chatService.sendMessage(request, user.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @Valid @RequestBody ChatRequestDto request,
            @AuthenticationPrincipal User user) {
        return chatService.sendMessageStream(request, user.getId());
    }

    @GetMapping("/history/{conversationId}")
    public ResponseEntity<List<MessageDto>> getHistory(
            @PathVariable String conversationId,
            @AuthenticationPrincipal User user) {
        List<MessageDto> messages = chatService.getHistory(conversationId, user.getId());
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/sidebar")
    public ResponseEntity<List<FolderDto>> getSidebar(
            @AuthenticationPrincipal(expression = "id") String userId) {
        return ResponseEntity.ok(chatService.getSidebarData(userId));
    }
}