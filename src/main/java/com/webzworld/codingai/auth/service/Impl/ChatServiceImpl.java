package com.webzworld.codingai.auth.service.Impl;

import com.webzworld.codingai.auth.dto.ChatRequestDto;
import com.webzworld.codingai.auth.dto.ChatResponseDto;
import com.webzworld.codingai.auth.dto.FileDto;
import com.webzworld.codingai.auth.dto.MessageDto;
import com.webzworld.codingai.auth.entity.Conversation;
import com.webzworld.codingai.auth.entity.Message;
import com.webzworld.codingai.auth.repo.MessageRepository;
import com.webzworld.codingai.auth.service.ChatService;
import com.webzworld.codingai.auth.service.FileService;
import com.webzworld.codingai.auth.utils.SmartFileSelector;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements ChatService {

    private final ConversationServiceImpl conversationService;
    private final MessageRepository messageRepository;
    private final BedrockServiceImpl bedrockService;
    private final FileService fileService;
    private final SmartFileSelector smartFileSelector;

    private static final int HISTORY_LIMIT = 10;
    private static final int MAX_ITERATIONS = 5;

    public ChatServiceImpl(SmartFileSelector smartFileSelector,
                           ConversationServiceImpl conversationService,
                           MessageRepository messageRepository,
                           BedrockServiceImpl bedrockService,
                           FileService fileService) {
        this.smartFileSelector = smartFileSelector;
        this.conversationService = conversationService;
        this.messageRepository = messageRepository;
        this.bedrockService = bedrockService;
        this.fileService = fileService;
    }

    private BedrockServiceImpl.BedrockResponse callBedrockWithRetry(
            String prompt,
            List<FileDto> files,
            List<BedrockServiceImpl.HistoryMessage> history) {
        String currentPrompt = prompt;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                BedrockServiceImpl.BedrockResponse response =
                        bedrockService.chat(currentPrompt, files, history);
                if (response.explanation() != null
                        && !response.explanation().startsWith("⚠️ AI response parsing failed")
                        && !response.explanation().isBlank()) {
                    return response;
                }
                System.out.println("⚠️ Attempt " + (attempt + 1) + " — bad response, retrying...");
                currentPrompt = prompt
                        + "\n\nYour last response could not be parsed as valid JSON. "
                        + "Return ONLY a valid JSON object starting with { and ending with }. "
                        + "No markdown, no extra text.";

            } catch (Exception e) {
                System.out.println("⚠️ Attempt " + (attempt + 1) + " threw: " + e.getMessage());
                currentPrompt = prompt
                        + "\n\nYour last response caused a parsing error: " + e.getMessage()
                        + "\nReturn ONLY valid JSON.";
            }
        }
        return new BedrockServiceImpl.BedrockResponse(
                "⚠️ AI failed to respond correctly after 3 attempts. Please try again.",
                new ArrayList<>()
        );
    }

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
            conversation = conversationService.create(userId, title, request.getFolderPath());
        }
        List<FileDto> allFiles = fileService.loadProjectFiles(conversation.getFolderPath(),request.getMessage());
        System.out.println("📂 Total project files: " + allFiles.size());
        System.out.println("🗂 Chunks being sent to AI:");
        for (FileDto file : allFiles) {
            System.out.println("\n========================================");
            System.out.println("File: " + file.getPath());
            System.out.println("Length: " + file.getContent().length() + " chars");
            System.out.println("----------------------------------------");
            System.out.println(file.getContent());
            System.out.println("========================================\n");
        }
        Message userMessageEntity = new Message();
        userMessageEntity.setConversationId(conversation.getId());
        userMessageEntity.setRole("user");
        userMessageEntity.setContent(request.getMessage());
        final Message savedUserMessage = messageRepository.save(userMessageEntity);
        List<Message> recentMessages = messageRepository
                .findLastNByConversationId(conversation.getId(), HISTORY_LIMIT);
        List<BedrockServiceImpl.HistoryMessage> history = recentMessages
                .stream()
                .filter(m -> !m.getId().equals(savedUserMessage.getId()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(m -> new BedrockServiceImpl.HistoryMessage(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
        List<FileDto> initialFiles = smartFileSelector.findRelevantFiles(
                request.getMessage(), allFiles
        );
        System.out.println("🎯 Pre-selected files for AI: "
                + initialFiles.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
        BedrockServiceImpl.BedrockResponse bedrockResponse = null;
        Map<String, FileDto> seenFiles = new LinkedHashMap<>();
        for (FileDto f : initialFiles) seenFiles.put(f.getPath(), f);
        String runningPrompt = request.getMessage();
        Set<String> calledTools = new HashSet<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            System.out.println("🔄 Iteration " + (i + 1) + " — files in context: " + seenFiles.size());
//            bedrockResponse = callBedrockWithRetry(runningPrompt, new ArrayList<>(seenFiles.values()), history);
            bedrockResponse = bedrockService.chat(
                    runningPrompt,
                    new ArrayList<>(seenFiles.values()),
                    history
            );
            String responseText = bedrockResponse.explanation();
            String toolJson = extractToolJson(responseText);
            if (toolJson == null || !toolJson.contains("\"tool\"")) {
                System.out.println("✅ Final answer received on iteration " + (i + 1));
                break;
            }
            System.out.println("🛠 Tool call: " + toolJson);
            if (calledTools.contains(toolJson)) {
                System.out.println("⚠️ Same tool called twice — forcing final answer");

                runningPrompt += "\n\nYou already have all required files. Return final JSON.";

                calledTools.clear();
                continue;
            }
            if (seenFiles.size() > 8) {
                System.out.println("🚫 Enough context gathered — stopping tool calls");

                runningPrompt += "\n\nYou now have enough context. Return final JSON.";
                continue;
            }
            calledTools.add(toolJson);
            if (toolJson.contains("find_files")) {
                String query = extractQuery(toolJson);
                List<FileDto> matched = smartFileSelector.findRelevantFiles(query, allFiles);
                System.out.println("🔍 find_files '" + query + "' → "
                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
                for (FileDto f : matched) {
                    seenFiles.put(f.getPath(), f);
                }
                String toolResult = "TOOL RESULT (find_files for '" + query + "'):\n"
                        + "Found " + matched.size() + " file(s). "
                        + "Their content is included in the PROJECT FILES above:\n"
                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining("\n"));
                runningPrompt += "\n\n" + toolResult;
            }
            else if (toolJson.contains("read_files")) {
                List<String> paths = extractPaths(toolJson);
                List<FileDto> filesToRead = allFiles.stream()
                        .filter(f -> paths.contains(f.getPath()))
                        .collect(Collectors.toList());
                System.out.println("📖 read_files → "
                        + filesToRead.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
                for (FileDto f : filesToRead) {
                    seenFiles.put(f.getPath(), f);
                }
                String toolResult = "TOOL RESULT (read_files): "
                        + "File content is included in PROJECT FILES above.";
                runningPrompt  += "\n\n" + toolResult;
            }
            else {
                System.out.println("⚠️ Unknown tool call, stopping loop: " + toolJson);
                break;
            }
        }
        Set<String> validPaths = allFiles.stream()
                .map(FileDto::getPath)
                .collect(Collectors.toSet());
        List<FileDto> safeFiles = bedrockResponse.changedFiles()
                .stream()
                .filter(f -> {
                    String path = f.getPath().replace("\\", "/");
                    if (validPaths.contains(path)) return true;
                    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
                        System.out.println("🚫 Blocked absolute path: " + path);
                        return false;
                    }
                    if (path.contains("..")) {
                        System.out.println("🚫 Blocked traversal: " + path);
                        return false;
                    }
                    System.out.println("✅ New file allowed: " + path);
                    return true;
                })
                .collect(Collectors.toList());
        System.out.println("📝 Changed files: "
                + safeFiles.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
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
                savedAiMessage.getId()
        );
    }

    // ── History ───────────────────────────────────────────────────────────────

    public List<MessageDto> getHistory(String conversationId, String userId) {
        Conversation conv = conversationService.findRaw(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            throw new RuntimeException("Conversation not found");
        }
        return messageRepository.findAllByConversationId(conversationId)
                .stream()
                .map(m -> new MessageDto(
                        m.getId(), m.getConversationId(),
                        m.getRole(), m.getContent(), m.getCreatedAt()
                ))
                .collect(Collectors.toList());
    }

    // ── JSON extraction helpers ───────────────────────────────────────────────

    private String extractToolJson(String text) {
        if (text == null) return null;
        int start = text.indexOf("{");
        if (start == -1) return null;
        // Find the matching closing brace
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    if (candidate.contains("\"tool\"")) return candidate;
                    return null; // first JSON block doesn't have "tool" → it's a final answer
                }
            }
        }
        return null;
    }

    private String extractQuery(String json) {
        int start = json.indexOf("\"query\"");
        if (start == -1) return "";
        int colon = json.indexOf(":", start);
        int firstQuote = json.indexOf("\"", colon + 1);
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        if (firstQuote == -1 || secondQuote == -1) return "";
        return json.substring(firstQuote + 1, secondQuote);
    }

    private List<String> extractPaths(String json) {
        List<String> paths = new ArrayList<>();
        int start = json.indexOf("[");
        int end = json.lastIndexOf("]");
        if (start == -1 || end == -1 || end <= start) return paths;
        String inside = json.substring(start + 1, end);
        for (String p : inside.split(",")) {
            p = p.replace("\"", "").trim();
            if (!p.isEmpty()) paths.add(p);
        }
        return paths;
    }
}

//new funcatinality

//package com.webzworld.codingai.auth.service.Impl;
//
//import com.webzworld.codingai.auth.dto.ChatRequestDto;
//import com.webzworld.codingai.auth.dto.ChatResponseDto;
//import com.webzworld.codingai.auth.dto.FileDto;
//import com.webzworld.codingai.auth.dto.MessageDto;
//import com.webzworld.codingai.auth.entity.Conversation;
//import com.webzworld.codingai.auth.entity.Message;
//import com.webzworld.codingai.auth.repo.MessageRepository;
//import com.webzworld.codingai.auth.service.ChatService;
//import com.webzworld.codingai.auth.service.FileService;
//import com.webzworld.codingai.auth.utils.SmartFileSelector;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//import java.util.stream.Collectors;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//@Service
//public class ChatServiceImpl implements ChatService {
//
//    private final ConversationServiceImpl conversationService;
//    private final MessageRepository messageRepository;
//    private final BedrockServiceImpl bedrockService;
//    private final FileService fileService;
//    private final SmartFileSelector smartFileSelector;
//
//    private static final int HISTORY_LIMIT = 10;
//    private static final int MAX_ITERATIONS = 5;
//
//    public ChatServiceImpl(SmartFileSelector smartFileSelector,
//                           ConversationServiceImpl conversationService,
//                           MessageRepository messageRepository,
//                           BedrockServiceImpl bedrockService,
//                           FileService fileService) {
//        this.smartFileSelector = smartFileSelector;
//        this.conversationService = conversationService;
//        this.messageRepository = messageRepository;
//        this.bedrockService = bedrockService;
//        this.fileService = fileService;
//    }
//
//    private BedrockServiceImpl.BedrockResponse callBedrockWithRetry(
//            String prompt,
//            List<FileDto> files,
//            List<BedrockServiceImpl.HistoryMessage> history) {
//        String currentPrompt = prompt;
//        for (int attempt = 0; attempt < 3; attempt++) {
//            try {
//                BedrockServiceImpl.BedrockResponse response =
//                        bedrockService.chat(currentPrompt, files, history);
//                if (response.explanation() != null
//                        && !response.explanation().startsWith("⚠️ AI response parsing failed")
//                        && !response.explanation().isBlank()) {
//                    return response;
//                }
//                System.out.println("⚠️ Attempt " + (attempt + 1) + " — bad response, retrying...");
//                currentPrompt = prompt
//                        + "\n\nYour last response could not be parsed as valid JSON. "
//                        + "Return ONLY a valid JSON object starting with { and ending with }. "
//                        + "No markdown, no extra text.";
//
//            } catch (Exception e) {
//                System.out.println("⚠️ Attempt " + (attempt + 1) + " threw: " + e.getMessage());
//                currentPrompt = prompt
//                        + "\n\nYour last response caused a parsing error: " + e.getMessage()
//                        + "\nReturn ONLY valid JSON.";
//            }
//        }
//        return new BedrockServiceImpl.BedrockResponse(
//                "⚠️ AI failed to respond correctly after 3 attempts. Please try again.",
//                new ArrayList<>()
//        );
//    }
//
//    public ChatResponseDto sendMessage(ChatRequestDto request, String userId) {
//        System.out.println("conversion id : " + request.getConversationId());
//        Conversation conversation;
//        if (request.getConversationId() != null && !request.getConversationId().isBlank()) {
//            conversation = conversationService.findRaw(request.getConversationId());
//            if (conversation == null || !conversation.getUserId().equals(userId)) {
//                throw new RuntimeException("Conversation not found");
//            }
//        } else {
//            String title = request.getMessage().length() > 60
//                    ? request.getMessage().substring(0, 60) + "..."
//                    : request.getMessage();
//            conversation = conversationService.create(userId, title, request.getFolderPath());
//        }
//        List<FileDto> allFiles = fileService.loadProjectFiles(conversation.getFolderPath());
//        System.out.println("📂 Total project files: " + allFiles.size());
//        Message userMessageEntity = new Message();
//        userMessageEntity.setConversationId(conversation.getId());
//        userMessageEntity.setRole("user");
//        userMessageEntity.setContent(request.getMessage());
//        final Message savedUserMessage = messageRepository.save(userMessageEntity);
//        List<Message> recentMessages = messageRepository
//                .findLastNByConversationId(conversation.getId(), HISTORY_LIMIT);
//        List<BedrockServiceImpl.HistoryMessage> history = recentMessages
//                .stream()
//                .filter(m -> !m.getId().equals(savedUserMessage.getId()))
//                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
//                .map(m -> new BedrockServiceImpl.HistoryMessage(m.getRole(), m.getContent()))
//                .collect(Collectors.toList());
////        List<FileDto> initialFiles = smartFileSelector.findRelevantFiles(
////                request.getMessage(), allFiles
////        );
////        System.out.println("🎯 Pre-selected files for AI: "
////                + initialFiles.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
////        BedrockServiceImpl.BedrockResponse bedrockResponse = null;
////        Map<String, FileDto> seenFiles = new LinkedHashMap<>();
////        for (FileDto f : initialFiles) seenFiles.put(f.getPath(), f);
//        // Pre-select + resolve imports
//        List<FileDto> initialFiles = smartFileSelector.findRelevantFiles(
//                request.getMessage(), allFiles
//        );
//        System.out.println("🎯 Pre-selected: "
//                + initialFiles.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
//
//        // ✅ NEW: auto-include files that initialFiles import
//        List<FileDto> withDeps = resolveImports(initialFiles, allFiles);
//        System.out.println("📎 With dependencies: "
//                + withDeps.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
//
//        Map<String, FileDto> seenFiles = new LinkedHashMap<>();
//        for (FileDto f : withDeps) seenFiles.put(f.getPath(), f);  // ← changed from initialFiles
//        String runningPrompt = request.getMessage();
//        Set<String> calledTools = new HashSet<>();
//        BedrockServiceImpl.BedrockResponse bedrockResponse = null;
//        for (int i = 0; i < MAX_ITERATIONS; i++) {
//            System.out.println("🔄 Iteration " + (i + 1) + " — files in context: " + seenFiles.size());
//            bedrockResponse = callBedrockWithRetry(runningPrompt, new ArrayList<>(seenFiles.values()), history);
//            String responseText = bedrockResponse.explanation();
//            String toolJson = extractToolJson(responseText);
//            if (toolJson == null || !toolJson.contains("\"tool\"")) {
//                System.out.println("✅ Final answer received on iteration " + (i + 1));
//                break;
//            }
//            System.out.println("🛠 Tool call: " + toolJson);
//            if (calledTools.contains(toolJson)) {
//                System.out.println("⚠️ Same tool called twice — forcing final answer");
//
//                runningPrompt = request.getMessage()
//                        + "\n\nYou already have all required files. Return final JSON.";
//
//                calledTools.clear();
//                continue;
//            }
//            if (seenFiles.size() > 8) {
//                System.out.println("🚫 Enough context gathered — stopping tool calls");
//                runningPrompt = request.getMessage()
//                        + "\n\nYou now have enough context. Return final JSON.";
//                continue;
//            }
//            calledTools.add(toolJson);
//            if (toolJson.contains("find_files")) {
//                String query = extractQuery(toolJson);
//                List<FileDto> matched = smartFileSelector.findRelevantFiles(query, allFiles);
//                System.out.println("🔍 find_files '" + query + "' → "
//                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
//                for (FileDto f : matched) {
//                    seenFiles.put(f.getPath(), f);
//                }
//                String toolResult = "TOOL RESULT (find_files for '" + query + "'):\n"
//                        + "Found " + matched.size() + " file(s). "
//                        + "Their content is included in the PROJECT FILES above:\n"
//                        + matched.stream().map(FileDto::getPath).collect(Collectors.joining("\n"));
//                runningPrompt = request.getMessage() + "\n\n" + toolResult;
//            }
//            else if (toolJson.contains("read_files")) {
//                List<String> paths = extractPaths(toolJson);
//                List<FileDto> filesToRead = allFiles.stream()
//                        .filter(f -> paths.contains(f.getPath()))
//                        .collect(Collectors.toList());
//                System.out.println("📖 read_files → "
//                        + filesToRead.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
//                for (FileDto f : filesToRead) {
//                    seenFiles.put(f.getPath(), f);
//                }
//                String toolResult = "TOOL RESULT (read_files): "
//                        + "File content is included in PROJECT FILES above.";
//                runningPrompt = request.getMessage() + "\n\n" + toolResult;
//            }
//            else {
//                System.out.println("⚠️ Unknown tool call, stopping loop: " + toolJson);
//                break;
//            }
//        }
//        Set<String> validPaths = allFiles.stream()
//                .map(FileDto::getPath)
//                .collect(Collectors.toSet());
//        List<FileDto> safeFiles = bedrockResponse.changedFiles()
//                .stream()
//                .filter(f -> {
//                    String path = f.getPath().replace("\\", "/");
//                    if (validPaths.contains(path)) return true;
//                    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
//                        System.out.println("🚫 Blocked absolute path: " + path);
//                        return false;
//                    }
//                    if (path.contains("..")) {
//                        System.out.println("🚫 Blocked traversal: " + path);
//                        return false;
//                    }
//                    System.out.println("✅ New file allowed: " + path);
//                    return true;
//                })
//                .collect(Collectors.toList());
//        System.out.println("📝 Changed files: "
//                + safeFiles.stream().map(FileDto::getPath).collect(Collectors.joining(", ")));
//        Message aiMessageEntity = new Message();
//        aiMessageEntity.setConversationId(conversation.getId());
//        aiMessageEntity.setRole("assistant");
//        aiMessageEntity.setContent(bedrockResponse.explanation());
//        final Message savedAiMessage = messageRepository.save(aiMessageEntity);
//        conversationService.save(conversation);
//        return new ChatResponseDto(
//                conversation.getId(),
//                bedrockResponse.explanation(),
//                safeFiles,
//                savedUserMessage.getId(),
//                savedAiMessage.getId()
//        );
//    }
//
//    // ── History ───────────────────────────────────────────────────────────────
//
//    public List<MessageDto> getHistory(String conversationId, String userId) {
//        Conversation conv = conversationService.findRaw(conversationId);
//        if (conv == null || !conv.getUserId().equals(userId)) {
//            throw new RuntimeException("Conversation not found");
//        }
//        return messageRepository.findAllByConversationId(conversationId)
//                .stream()
//                .map(m -> new MessageDto(
//                        m.getId(), m.getConversationId(),
//                        m.getRole(), m.getContent(), m.getCreatedAt()
//                ))
//                .collect(Collectors.toList());
//    }
//
//    // ── JSON extraction helpers ───────────────────────────────────────────────
//
//    private String extractToolJson(String text) {
//        if (text == null) return null;
//        int start = text.indexOf("{");
//        if (start == -1) return null;
//        // Find the matching closing brace
//        int depth = 0;
//        for (int i = start; i < text.length(); i++) {
//            if (text.charAt(i) == '{') depth++;
//            else if (text.charAt(i) == '}') {
//                depth--;
//                if (depth == 0) {
//                    String candidate = text.substring(start, i + 1);
//                    if (candidate.contains("\"tool\"")) return candidate;
//                    return null; // first JSON block doesn't have "tool" → it's a final answer
//                }
//            }
//        }
//        return null;
//    }
//
//    private String extractQuery(String json) {
//        int start = json.indexOf("\"query\"");
//        if (start == -1) return "";
//        int colon = json.indexOf(":", start);
//        int firstQuote = json.indexOf("\"", colon + 1);
//        int secondQuote = json.indexOf("\"", firstQuote + 1);
//        if (firstQuote == -1 || secondQuote == -1) return "";
//        return json.substring(firstQuote + 1, secondQuote);
//    }
//
//    private List<String> extractPaths(String json) {
//        List<String> paths = new ArrayList<>();
//        int start = json.indexOf("[");
//        int end = json.lastIndexOf("]");
//        if (start == -1 || end == -1 || end <= start) return paths;
//        String inside = json.substring(start + 1, end);
//        for (String p : inside.split(",")) {
//            p = p.replace("\"", "").trim();
//            if (!p.isEmpty()) paths.add(p);
//        }
//        return paths;
//    }
//
//    private List<FileDto> resolveImports(List<FileDto> selected, List<FileDto> allFiles) {
//        Map<String, FileDto> allByPath = new HashMap<>();
//        for (FileDto f : allFiles) allByPath.put(f.getPath(), f);
//        Set<String> toAdd = new HashSet<>();
//        Pattern importPattern = Pattern.compile("(?:from\\s+['\"](\\.{1,2}/[^'\"]+)['\"]|import\\s+['\"](\\.{1,2}/[^'\"]+)['\"])");
//        for (FileDto file : selected) {
//            if (file.getContent() == null) continue;
//            Matcher m = importPattern.matcher(file.getContent());
//            String dir = file.getPath().contains("/")
//                    ? file.getPath().substring(0, file.getPath().lastIndexOf("/"))
//                    : "";
//            while (m.find()) {
//                String imp = m.group(1) != null ? m.group(1) : m.group(2);
//                String base = dir.isEmpty() ? imp : dir + "/" + imp;
//                String[] parts = base.split("/");
//                List<String> resolved = new ArrayList<>();
//                for (String part : parts) {
//                    if (part.equals("..") && !resolved.isEmpty()) {
//                        resolved.remove(resolved.size() - 1);
//                    } else if (!part.equals(".") && !part.isEmpty()) {
//                        resolved.add(part);
//                    }
//                }
//                String resolvedPath = String.join("/", resolved);
//                for (String ext : List.of(".jsx", ".tsx", ".js", ".ts", ".css")) {
//                    String candidate = resolvedPath + ext;
//                    if (allByPath.containsKey(candidate)) {
//                        toAdd.add(candidate);
//                        break;
//                    }
//                }
//                String indexCandidate = resolvedPath + "/index.jsx";
//                if (allByPath.containsKey(indexCandidate)) toAdd.add(indexCandidate);
//            }
//        }
//
//        List<FileDto> result = new ArrayList<>(selected);
//        Set<String> alreadyIn = selected.stream()
//                .map(FileDto::getPath)
//                .collect(Collectors.toSet());
//
//        for (String path : toAdd) {
//            if (!alreadyIn.contains(path)) {
//                FileDto dep = allByPath.get(path);
//                if (dep != null) {
//                    result.add(dep);
//                    System.out.println("📎 Auto-included: " + path);
//                }
//            }
//        }
//        return result;
//    }
//}