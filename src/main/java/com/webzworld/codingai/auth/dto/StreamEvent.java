package com.webzworld.codingai.auth.dto;

import java.util.List;

/**
 * Typed SSE events — Java 17 compatible (no sealed, no pattern switch).
 *
 * Wire format sent as SSE data field (JSON):
 *   text_chunk    → { "type": "text_chunk",    "text": "..." }
 *   status        → { "type": "status",         "text": "🔍 Searching..." }
 *   changed_files → { "type": "changed_files",  "files": [...] }
 *   done          → { "type": "done",            "conversationId":"...", ... }
 *   error         → { "type": "error",           "text": "..." }
 */
public class StreamEvent {

    // ── Text chunk ─────────────────────────────────────────────────────────
    public static class TextChunk extends StreamEvent {
        private final String text;
        public TextChunk(String text) { this.text = text; }
        public String getText() { return text; }
    }

    // ── Status (tool call status shown while backend runs a tool) ──────────
    public static class Status extends StreamEvent {
        private final String text;
        public Status(String text) { this.text = text; }
        public String getText() { return text; }
    }

    // ── Changed files (sent once at the very end) ──────────────────────────
    public static class ChangedFiles extends StreamEvent {
        private final List<FileDto> files;
        public ChangedFiles(List<FileDto> files) { this.files = files; }
        public List<FileDto> getFiles() { return files; }
    }

    // ── Done (conversation/message IDs so frontend can update state) ───────
    public static class Done extends StreamEvent {
        private final String conversationId;
        private final String userMessageId;
        private final String aiMessageId;
        public Done(String conversationId, String userMessageId, String aiMessageId) {
            this.conversationId = conversationId;
            this.userMessageId  = userMessageId;
            this.aiMessageId    = aiMessageId;
        }
        public String getConversationId() { return conversationId; }
        public String getUserMessageId()  { return userMessageId; }
        public String getAiMessageId()    { return aiMessageId; }
    }

    // ── Error ──────────────────────────────────────────────────────────────
    public static class Error extends StreamEvent {
        private final String text;
        public Error(String text) { this.text = text; }
        public String getText() { return text; }
    }
}