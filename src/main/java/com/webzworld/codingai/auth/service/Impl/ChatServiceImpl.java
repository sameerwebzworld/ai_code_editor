package com.webzworld.codingai.auth.service.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webzworld.codingai.auth.dto.*;
import com.webzworld.codingai.auth.entity.Conversation;
import com.webzworld.codingai.auth.entity.Folder;
import com.webzworld.codingai.auth.entity.Message;
import com.webzworld.codingai.auth.repo.ConversationRepository;
import com.webzworld.codingai.auth.repo.FolderRepository;
import com.webzworld.codingai.auth.repo.MessageRepository;
import com.webzworld.codingai.auth.service.BedrockService;
import com.webzworld.codingai.auth.service.ChatService;
import com.webzworld.codingai.auth.utils.SmartChunker;
import com.webzworld.codingai.auth.utils.SmartFileSelector;
import com.webzworld.codingai.auth.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements ChatService {

    private final ConversationServiceImpl conversationService;
    private final MessageRepository messageRepository;
    private final BedrockServiceImpl bedrockService;
    private final FileService fileService;
    private final SmartFileSelector smartFileSelector;
    private final FolderRepository folderRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int  HISTORY_LIMIT   = 10;
    private static final int  MAX_ITERATIONS  = 5;
    private static final long SSE_TIMEOUT_MS  = 5 * 60 * 1000L;

    public ChatServiceImpl(ConversationServiceImpl conversationService,MessageRepository messageRepository,BedrockServiceImpl bedrockService,FileService fileService,SmartFileSelector smartFileSelector,FolderRepository folderRepository,ConversationRepository conversationRepository) {
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.bedrockService = bedrockService;
        this.fileService = fileService;
        this.smartFileSelector = smartFileSelector;
        this.folderRepository = folderRepository;
        this.conversationRepository = conversationRepository;
    }

    @Override
    public SseEmitter sendMessageStream(ChatRequestDto request, String userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                runStreamingSession(emitter, request, userId);
            } catch (Exception e) {
                try {
                    emit(emitter, "error", new StreamEvent.Error("Fatal error: " + e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        });
        return emitter;
    }

    private void runStreamingSession(SseEmitter emitter, ChatRequestDto request, String userId) throws Exception {
        Conversation conversation;
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            conversation = conversationService.findRaw(request.getConversationId());
            if (conversation == null || !conversation.getUserId().equals(userId)) {
                emit(emitter, "error", new StreamEvent.Error("Conversation not found"));
                emitter.complete();
                return;
            }
        } else {
            String title = request.getMessage().length() > 60
                    ? request.getMessage().substring(0, 60) + "..."
                    : request.getMessage();
            conversation = conversationService.create(
                    userId, title, request.getFolderId(), request.getFolderPath());
        }
        // ── 2. Load project files ─────────────────────────────────────────────
        emit(emitter, "status", new StreamEvent.Status("📂 Loading project files..."));
        List<FileDto> allFiles = fileService.loadProjectFiles(
                conversation.getFolderPath(), request.getMessage());
        System.out.println("📂 Total files: " + allFiles.size());
        // ── 3. Save user message ──────────────────────────────────────────────
        Message userMsg = new Message();
        userMsg.setConversationId(conversation.getId());
        userMsg.setRole("user");
        userMsg.setContent(request.getMessage());
        final Message savedUserMsg = messageRepository.save(userMsg);
        // ── 4. Build history ──────────────────────────────────────────────────
        List<Message> recent = messageRepository
                .findLastNByConversationId(conversation.getId(), HISTORY_LIMIT);
        List<BedrockService.HistoryMessage> history = recent.stream()
                .filter(m -> !m.getId().equals(savedUserMsg.getId()))
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .map(m -> new BedrockServiceImpl.HistoryMessage(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
        // ── 5. Pre-select relevant files ──────────────────────────────────────
        List<FileDto> initialFiles = smartFileSelector.findRelevantFiles(
                request.getMessage(), allFiles);
        Map<String, FileDto> seenFiles = new LinkedHashMap<>();
        for (FileDto f : initialFiles) seenFiles.put(f.getPath(), f);
        // ── 6. Tool-call loop ─────────────────────────────────────────────────
        String runningPrompt       = request.getMessage();
        Set<String> calledTools    = new HashSet<>();
        StringBuilder fullAiText   = new StringBuilder();
        List<FileDto> changedFiles = new ArrayList<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            System.out.println("🔄 Stream iteration " + (i + 1));
            SmartChunker chunker = new SmartChunker();
            List<FileDto> chunkedFiles = chunker.chunkFiles(
                    new ArrayList<>(seenFiles.values()), request.getMessage());
            // Stream this iteration — onChunk fires for every text delta
            StringBuilder iterationText = new StringBuilder();
            String completeText = bedrockService.chatStream(
                    runningPrompt, chunkedFiles, history,
                    chunk -> iterationText.append(chunk)  // collect only, don't emit
            );
            fullAiText.append(completeText);
            String toolJson = extractToolJson(completeText);
            if (toolJson == null || !toolJson.contains("\"tool\"")) {
                System.out.println("✅ Final answer on iteration " + (i + 1));
                changedFiles = parseChangedFiles(completeText, allFiles);
                // ✅ Extract clean explanation and stream it word by word
                String explanation = extractExplanationForDisplay(completeText);
                emit(emitter, "status", new StreamEvent.Status("✍️ Writing response..."));
                // Stream explanation as chunks for typewriter effect
                String[] words = explanation.split("(?<=[ ,\\.!\\?])");
                for (String word : words) {
                    try {
                        emit(emitter, "text_chunk", new StreamEvent.TextChunk(word));
                        Thread.sleep(18);
                    } catch (Exception ignored) {}
                }
                break;
            }
            System.out.println("🛠 Tool call: " + toolJson);
            if (calledTools.contains(toolJson)) {
                System.out.println("⚠️ Duplicate tool — forcing final answer");
                runningPrompt += "\n\nYou already have all required files. Return final JSON.";
                calledTools.clear();
                continue;
            }
            if (seenFiles.size() > 8) {
                runningPrompt += "\n\nYou now have enough context. Return final JSON.";
                continue;
            }
            calledTools.add(toolJson);
            // ── Execute tool ────────────────────────────────────────────────
            if (toolJson.contains("find_files")) {
                String query = extractQuery(toolJson);
                emit(emitter, "status",
                        new StreamEvent.Status("🔍 Searching files: " + query + "..."));
                List<FileDto> matched = smartFileSelector.findRelevantFiles(query, allFiles);
                for (FileDto f : matched) seenFiles.put(f.getPath(), f);
                runningPrompt += "\n\nTOOL RESULT (find_files for '" + query + "'):\n"
                        + "Found " + matched.size() + " file(s):\n"
                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining("\n"));
            } else if (toolJson.contains("read_files")) {
                List<String> paths = extractPaths(toolJson);
                emit(emitter, "status",
                        new StreamEvent.Status("📖 Reading " + paths.size() + " file(s)..."));
                allFiles.stream()
                        .filter(f -> paths.contains(f.getPath()))
                        .forEach(f -> seenFiles.put(f.getPath(), f));
                runningPrompt += "\n\nTOOL RESULT (read_files): content included above.";
            } else if (toolJson.contains("run_command")) {
                String cmd     = extractField(toolJson, "cmd");
                String workDir = conversation.getFolderPath();
                if (cmd == null || cmd.isBlank()) {
                    System.out.println("⚠️ Missing cmd");
                    continue;
                }
                if (cmd.contains("rm ") || cmd.contains("del ") || cmd.contains("shutdown")) {
                    System.out.println("🚫 Blocked: " + cmd);
                    continue;
                }
                emit(emitter, "status", new StreamEvent.Status("⚡ Running: " + cmd));
                try {
                    ProcessBuilder pb = System.getProperty("os.name")
                            .toLowerCase().contains("win")
                            ? new ProcessBuilder("cmd.exe", "/c", cmd)
                            : new ProcessBuilder("bash", "-c", cmd);
                    pb.directory(new File(workDir));
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output = new String(p.getInputStream().readAllBytes());
                    boolean finished = p.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) { p.destroyForcibly(); output += "\n⚠️ Timed out."; }
                    allFiles = fileService.loadProjectFiles(workDir, request.getMessage());
                    seenFiles.clear();
                    for (FileDto f : allFiles) seenFiles.put(f.getPath(), f);
                    runningPrompt += "\n\nTOOL RESULT:\n" + output
                            + "\n\nDecide next step:\n"
                            + "- If dependencies missing → run npm install\n"
                            + "- If project not started → run npm run dev\n"
                            + "- If setup complete → return final JSON\n";
                } catch (IOException ex) {
                    runningPrompt += "\n\nTOOL RESULT: IO error — " + ex.getMessage();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    runningPrompt += "\n\nTOOL RESULT: Command interrupted.";
                }
            } else {
                System.out.println("⚠️ Unknown tool — stopping loop");
                break;
            }
        }
        // ── 7. Filter unsafe paths ────────────────────────────────────────────
        Set<String> validPaths = allFiles.stream()
                .map(FileDto::getPath).collect(Collectors.toSet());
        List<FileDto> safeFiles = changedFiles.stream()
                .filter(f -> {
                    String path = f.getPath().replace("\\", "/");
                    if (validPaths.contains(path)) return true;
                    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
                        System.out.println("🚫 Blocked absolute: " + path);
                        return false;
                    }
                    if (path.contains("..")) {
                        System.out.println("🚫 Blocked traversal: " + path);
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        // ── 8. Save AI message ────────────────────────────────────────────────
        String displayMessage = extractExplanationForDisplay(fullAiText.toString());
        Message aiMsg = new Message();
        aiMsg.setConversationId(conversation.getId());
        aiMsg.setRole("assistant");
        aiMsg.setContent(displayMessage);
        final Message savedAiMsg = messageRepository.save(aiMsg);
        conversationService.save(conversation);
        // ── 9. Final SSE events ───────────────────────────────────────────────
        if (!safeFiles.isEmpty()) {
            emit(emitter, "changed_files", new StreamEvent.ChangedFiles(safeFiles));
        }
        emit(emitter, "done", new StreamEvent.Done(
                conversation.getId(), savedUserMsg.getId(), savedAiMsg.getId()));
        emitter.complete();
        System.out.println("✅ SSE stream completed");
    }

    @Override
    public ChatResponseDto sendMessage(ChatRequestDto request, String userId) {
        System.out.println("conversion id : " + request.getConversationId());
        Conversation conversation;
        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
            conversation = conversationService.findRaw(request.getConversationId());
            if (conversation == null || !conversation.getUserId().equals(userId)) {
                throw new RuntimeException("Conversation not found");
            }
        } else {
            String title = request.getMessage().length() > 60
                    ? request.getMessage().substring(0, 60) + "..."
                    : request.getMessage();
            conversation = conversationService.create(
                    userId, title, request.getFolderId(), request.getFolderPath());
        }
        List<FileDto> allFiles = fileService.loadProjectFiles(
                conversation.getFolderPath(), request.getMessage());
        Message userMessageEntity = new Message();
        userMessageEntity.setConversationId(conversation.getId());
        userMessageEntity.setRole("user");
        userMessageEntity.setContent(request.getMessage());
        final Message savedUserMessage = messageRepository.save(userMessageEntity);
        List<Message> recentMessages = messageRepository
                .findLastNByConversationId(conversation.getId(), HISTORY_LIMIT);
        List<BedrockService.HistoryMessage> history = recentMessages.stream()
                .filter(m -> !m.getId().equals(savedUserMessage.getId()))
                .sorted(Comparator.comparing(Message::getCreatedAt))
                .map(m -> new BedrockService.HistoryMessage(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
        List<FileDto> initialFiles = smartFileSelector.findRelevantFiles(
                request.getMessage(), allFiles);
        Map<String, FileDto> seenFiles = new LinkedHashMap<>();
        for (FileDto f : initialFiles) seenFiles.put(f.getPath(), f);
        BedrockServiceImpl.BedrockResponse bedrockResponse = null;
        String runningPrompt = request.getMessage();
        Set<String> calledTools = new HashSet<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            SmartChunker chunker = new SmartChunker();
            List<FileDto> chunkedFiles = chunker.chunkFiles(
                    new ArrayList<>(seenFiles.values()), request.getMessage());
            bedrockResponse = callBedrockWithRetry(runningPrompt, chunkedFiles, history);
            String responseText = bedrockResponse.explanation();
            String toolJson     = extractToolJson(responseText);
            if (toolJson == null || !toolJson.contains("\"tool\"")) break;
            if (calledTools.contains(toolJson)) {
                runningPrompt += "\n\nYou already have all required files. Return final JSON.";
                calledTools.clear();
                continue;
            }
            if (seenFiles.size() > 8) {
                runningPrompt += "\n\nYou now have enough context. Return final JSON.";
                continue;
            }
            calledTools.add(toolJson);
            if (toolJson.contains("find_files")) {
                String query   = extractQuery(toolJson);
                List<FileDto> matched = smartFileSelector.findRelevantFiles(query, allFiles);
                for (FileDto f : matched) seenFiles.put(f.getPath(), f);
                runningPrompt += "\n\nTOOL RESULT (find_files for '" + query + "'):\n"
                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining("\n"));
            } else if (toolJson.contains("run_command")) {
                String cmd     = extractField(toolJson, "cmd");
                String workDir = conversation.getFolderPath();
                if (cmd == null || cmd.isBlank()) continue;
                if (cmd.contains("rm ") || cmd.contains("del ") || cmd.contains("shutdown")) continue;
                try {
                    ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                            ? new ProcessBuilder("cmd.exe", "/c", cmd)
                            : new ProcessBuilder("bash", "-c", cmd);
                    pb.directory(new File(workDir));
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output = new String(p.getInputStream().readAllBytes());
                    boolean fin = p.waitFor(120, TimeUnit.SECONDS);
                    if (!fin) { p.destroyForcibly(); output += "\n⚠️ Timed out."; }
                    allFiles = fileService.loadProjectFiles(workDir, request.getMessage());
                    seenFiles.clear();
                    for (FileDto f : allFiles) seenFiles.put(f.getPath(), f);
                    runningPrompt += "\n\nTOOL RESULT:\n" + output
                            + "\n\nDecide next: npm install / npm run dev / return JSON\n";
                } catch (IOException | InterruptedException e) {
                    runningPrompt += "\n\nTOOL RESULT: Error — " + e.getMessage();
                }
            } else if (toolJson.contains("read_files")) {
                List<String> paths = extractPaths(toolJson);
                allFiles.stream().filter(f -> paths.contains(f.getPath()))
                        .forEach(f -> seenFiles.put(f.getPath(), f));
                runningPrompt += "\n\nTOOL RESULT (read_files): content included above.";
            }
        }
        Set<String> validPaths = allFiles.stream()
                .map(FileDto::getPath).collect(Collectors.toSet());
        List<FileDto> safeFiles = bedrockResponse.changedFiles().stream()
                .filter(f -> {
                    String path = f.getPath().replace("\\", "/");
                    if (validPaths.contains(path)) return true;
                    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) return false;
                    return !path.contains("..");
                })
                .collect(Collectors.toList());
        Message aiMessageEntity = new Message();
        aiMessageEntity.setConversationId(conversation.getId());
        aiMessageEntity.setRole("assistant");
        aiMessageEntity.setContent(bedrockResponse.explanation());
        final Message savedAiMessage = messageRepository.save(aiMessageEntity);
        conversationService.save(conversation);
        return new ChatResponseDto(
                conversation.getId(),
                bedrockResponse.explanation(),
                safeFiles,
                savedUserMessage.getId(),
                savedAiMessage.getId());
    }

    private void emit(SseEmitter emitter, String eventName, StreamEvent event) throws Exception {
        Map<String, Object> payload = toMap(event);
        String json = objectMapper.writeValueAsString(payload);
        emitter.send(SseEmitter.event().name(eventName).data(json));
    }

    private Map<String, Object> toMap(StreamEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (event instanceof StreamEvent.TextChunk) {
            StreamEvent.TextChunk e = (StreamEvent.TextChunk) event;
            map.put("type", "text_chunk");
            map.put("text", e.getText());
        } else if (event instanceof StreamEvent.Status) {
            StreamEvent.Status e = (StreamEvent.Status) event;
            map.put("type", "status");
            map.put("text", e.getText());
        } else if (event instanceof StreamEvent.ChangedFiles) {
            StreamEvent.ChangedFiles e = (StreamEvent.ChangedFiles) event;
            map.put("type", "changed_files");
            map.put("files", e.getFiles());
        } else if (event instanceof StreamEvent.Done) {
            StreamEvent.Done e = (StreamEvent.Done) event;
            map.put("type", "done");
            map.put("conversationId", e.getConversationId());
            map.put("userMessageId",  e.getUserMessageId());
            map.put("aiMessageId",    e.getAiMessageId());
        } else if (event instanceof StreamEvent.Error) {
            StreamEvent.Error e = (StreamEvent.Error) event;
            map.put("type", "error");
            map.put("text", e.getText());
        }
        return map;
    }

    private List<FileDto> parseChangedFiles(String completeText, List<FileDto> originalFiles) {
        try {
            String cleaned = completeText.trim();
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
            // ✅ Use last JSON block — not first (which might be a tool call)
            String jsonPart = extractLastJsonBlock(cleaned);
            JsonNode root = objectMapper.readTree(jsonPart);
            JsonNode filesNode = root.get("changedFiles");
            if (filesNode == null || !filesNode.isArray()) return new ArrayList<>();
            List<FileDto> result = new ArrayList<>();
            for (JsonNode fileNode : filesNode) {
                String path = fileNode.path("path").asText("");
                String content = fileNode.path("content").asText("");
                if (!path.isEmpty()) {
                    FileDto dto = new FileDto();
                    dto.setPath(path);
                    dto.setContent(content);
                    result.add(dto);
                }
            }
            System.out.println("📄 parseChangedFiles found: " + result.size() + " files");
            return result;
        } catch (Exception e) {
            System.out.println("⚠️ parseChangedFiles failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private BedrockService.BedrockResponse callBedrockWithRetry(String prompt, List<FileDto> files, List<BedrockService.HistoryMessage> history) {
        String currentPrompt = prompt;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                BedrockService.BedrockResponse response =
                        bedrockService.chat(currentPrompt, files, history);
                if (response.explanation() != null
                        && !response.explanation().startsWith("⚠️ AI response parsing failed")
                        && !response.explanation().isBlank()) {
                    return response;
                }
                currentPrompt = prompt
                        + "\n\nYour last response could not be parsed. "
                        + "Return ONLY valid JSON starting with { ending with }.";
            } catch (Exception e) {
                currentPrompt = prompt + "\n\nParsing error: " + e.getMessage()
                        + "\nReturn ONLY valid JSON.";
            }
        }
        return new BedrockService.BedrockResponse(
                "⚠️ AI failed after 3 attempts.", new ArrayList<>());
    }

    @Override
    public List<MessageDto> getHistory(String conversationId, String userId) {
        Conversation conv = conversationService.findRaw(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            throw new RuntimeException("Conversation not found");
        }
        return messageRepository.findAllByConversationId(conversationId).stream()
                .map(m -> new MessageDto(m.getId(), m.getConversationId(),
                        m.getRole(), m.getContent(), m.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public List<FolderDto> getSidebarData(String userId) {
        List<Folder> folders = folderRepository.findByUserId(userId);
        return folders.stream().map(folder -> {
            List<Conversation> conversations =
                    conversationRepository.findByFolderId(folder.getId());
            List<ConversationDto> convDtos = conversations.stream()
                    .map(c -> new ConversationDto(c.getId(), c.getTitle(),
                            c.getFolderPath(), c.getCreatedAt(), c.getUpdatedAt()))
                    .toList();
            return new FolderDto(folder.getId(), folder.getName(),
                    convDtos, folder.getPath());
        }).toList();
    }

    private String extractToolJson(String text) {
        if (text == null) return null;
        int start = text.indexOf("{");
        if (start == -1) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    return candidate.contains("\"tool\"") ? candidate : null;
                }
            }
        }
        return null;
    }

    private String extractQuery(String json) {
        int start = json.indexOf("\"query\"");
        if (start == -1) return "";
        int colon       = json.indexOf(":", start);
        int firstQuote  = json.indexOf("\"", colon + 1);
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        if (firstQuote == -1 || secondQuote == -1) return "";
        return json.substring(firstQuote + 1, secondQuote);
    }

    private List<String> extractPaths(String json) {
        List<String> paths = new ArrayList<>();
        int start = json.indexOf("["), end = json.lastIndexOf("]");
        if (start == -1 || end == -1 || end <= start) return paths;
        for (String p : json.substring(start + 1, end).split(",")) {
            p = p.replace("\"", "").trim();
            if (!p.isEmpty()) paths.add(p);
        }
        return paths;
    }

    private String extractField(String json, String fieldName) {
        try {
            java.util.regex.Pattern r = java.util.regex.Pattern.compile(
                    "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Matcher m = r.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception e) {
            System.out.println("⚠️ extractField failed for " + fieldName);
        }
        return null;
    }

    private String extractExplanationForDisplay(String fullText) {
        try {
            String cleaned = fullText.trim();
            cleaned = cleaned.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
            // ✅ Take the LAST JSON block — skips any tool call JSON at the start
            String jsonPart = extractLastJsonBlock(cleaned);
            JsonNode root = objectMapper.readTree(jsonPart);
            if (root.has("explanation")) {
                String explanation = root.get("explanation").asText().trim();
                // Remove any code blocks inside explanation
                explanation = explanation.replaceAll("(?s)```[a-zA-Z]*\\s*.*?```", "").trim();
                explanation = explanation.replaceAll("\\n{3,}", "\n\n").trim();
                return explanation;
            }
        } catch (Exception e) {
            System.out.println("⚠️ extractExplanationForDisplay: " + e.getMessage());
        }
        return fullText.replaceAll("(?s)```.*?```", "").replaceAll("(?s)\\{.*\\}", "").trim();
    }

    private String extractLastJsonBlock(String text) {
        // Find the last { ... } block — that's the final answer
        int lastEnd = text.lastIndexOf("}");
        if (lastEnd == -1) return text;
        // Walk backwards to find matching opening brace
        int depth = 0;
        for (int i = lastEnd; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                depth--;
                if (depth == 0) return text.substring(i, lastEnd + 1);
            }
        }
        return text;
    }
}