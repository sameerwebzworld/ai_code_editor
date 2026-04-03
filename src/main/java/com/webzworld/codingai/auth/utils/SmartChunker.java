package com.webzworld.codingai.auth.utils;

import com.webzworld.codingai.auth.dto.FileDto;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartChunker {

    private static final int MAX_CHUNK_CHARS = 3000;

    // Split code into functions/classes (works for JS, Java, TS)
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("(function\\s+\\w+|class\\s+\\w+|public\\s+class|private\\s+\\w+\\s+\\w+\\()");

    public List<FileDto> chunkFiles(List<FileDto> files, String prompt) {
        List<FileDto> result = new ArrayList<>();

        for (FileDto file : files) {
            List<String> chunks = chunkSingleFile(file.getContent(), prompt);
            int idx = 1;
            for (String chunk : chunks) {
                FileDto newFile = new FileDto();
                newFile.setPath(file.getPath() + "_chunk" + idx++);
                newFile.setContent(chunk);
                result.add(newFile);
            }
        }

        return result;
    }

    private List<String> chunkSingleFile(String content, String prompt) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.length() <= MAX_CHUNK_CHARS) {
            chunks.add(content);
            return chunks;
        }

        String lowerPrompt = prompt.toLowerCase();

        // 1. Extract top (imports, package)
        String top = extractTop(content);

        // 2. Split into blocks
        List<String> blocks = splitIntoBlocks(content);

        // 3. Score blocks
        List<String> sortedBlocks = sortBlocksByRelevance(blocks, lowerPrompt);

        // 4. Create chunks safely
        StringBuilder currentChunk = new StringBuilder(top).append("\n\n");
        for (String block : sortedBlocks) {
            if (currentChunk.length() + block.length() + 50 > MAX_CHUNK_CHARS) {
                currentChunk.append("// ... chunk truncated ...\n");
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder(top).append("\n\n");
            }
            currentChunk.append(block).append("\n\n");
        }

        // Add remaining chunk
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    private String extractTop(String content) {
        int limit = Math.min(800, content.length());
        return content.substring(0, limit);
    }

    private List<String> splitIntoBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = BLOCK_PATTERN.matcher(content);
        List<Integer> positions = new ArrayList<>();
        while (matcher.find()) {
            positions.add(matcher.start());
        }

        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : content.length();
            blocks.add(content.substring(start, end));
        }

        if (blocks.isEmpty()) blocks.add(content);
        return blocks;
    }

    private List<String> sortBlocksByRelevance(List<String> blocks, String prompt) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String block : blocks) {
            int score = scoreBlock(block, prompt);
            scores.put(block, score);
        }

        return scores.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    private int scoreBlock(String block, String prompt) {
        String lower = block.toLowerCase();
        int score = 0;
        for (String word : prompt.split("\\s+")) {
            if (word.length() > 3 && lower.contains(word)) score += 5;
        }
        return score;
    }
}