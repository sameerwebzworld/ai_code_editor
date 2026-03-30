package com.webzworld.codingai.auth.utils;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webzworld.codingai.auth.dto.FileDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BedrockParseHelper {

    // ─── Main entry ──────────────────────────────────────────────────────────

    public static ParseResult parseBedrockResponse(String rawResponse,
                                                   List<FileDto> originalFiles,
                                                   ObjectMapper objectMapper) {
        try {
            // Step 1: Extract AI text from Bedrock envelope
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("content").path(0).path("text").asText("");

            if (text == null || text.isBlank()) {
                return new ParseResult("⚠️ Empty AI response", new ArrayList<>());
            }

            text = text.trim();
            System.out.println("==== RAW AI TEXT (first 400 chars) ====");
            System.out.println(text.substring(0, Math.min(400, text.length())));

            // Step 2: Check for pure tool call (short, starts with {, has "tool")
            if (text.startsWith("{") && text.length() < 300 && text.contains("\"tool\"")) {
                String firstJson = extractFirstJson(text);
                if (firstJson != null && firstJson.contains("\"tool\"")) {
                    System.out.println("🛠 Pure tool call detected");
                    return new ParseResult(firstJson, new ArrayList<>());
                }
            }

            // Step 3: If text contains "tool" somewhere and is short → might be a tool call
            if (text.contains("\"tool\"") && text.length() < 500
                    && !text.contains("\"explanation\"")) {
                String toolJson = extractFirstJson(text);
                if (toolJson != null && toolJson.contains("\"tool\"")) {
                    System.out.println("🛠 Tool call (embedded): " + toolJson);
                    return new ParseResult(toolJson, new ArrayList<>());
                }
            }

            // Step 4: Strip markdown code fences that wrap the whole response
            if (text.startsWith("```")) {
                text = text.replaceAll("(?s)^```[a-zA-Z]*\\s*", "")
                        .replaceAll("```\\s*$", "")
                        .trim();
            }

            // Step 5: Use MANUAL extraction — do NOT use Jackson to parse AI output
            // because file content (CSS/JSX/JS) inside JSON strings often has
            // unescaped characters that break Jackson even after sanitization
            String explanation = extractStringField(text, "explanation");
            List<FileDto> changedFiles = extractChangedFiles(text, originalFiles);

            if (explanation == null || explanation.isBlank()) {
                // Maybe it's a tool call mixed with explanation
                if (text.contains("\"tool\"")) {
                    String toolJson = extractFirstJson(text);
                    if (toolJson != null) {
                        System.out.println("🛠 Late tool call detected");
                        return new ParseResult(toolJson, new ArrayList<>());
                    }
                }
                explanation = "Done.";
            }

            System.out.println("✅ Parsed: explanation=" + explanation.length()
                    + " chars, files=" + changedFiles.size());
            return new ParseResult(explanation, changedFiles);

        } catch (Exception e) {
            e.printStackTrace();
            return new ParseResult("⚠️ AI response parsing failed: " + e.getMessage(),
                    new ArrayList<>());
        }
    }

    // ─── Manual field extractor ───────────────────────────────────────────────
    // Extracts the value of a top-level JSON string field by finding the key
    // and then reading until the matching closing quote (handling escapes).

    private static String extractStringField(String json, String fieldName) {
        String search = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(":", keyIdx + search.length());
        if (colonIdx == -1) return null;

        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;

        // Read the string value character by character, respecting escapes
        StringBuilder value = new StringBuilder();
        int i = valueStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n'  -> { value.append('\n'); i += 2; continue; }
                    case 'r'  -> { value.append('\r'); i += 2; continue; }
                    case 't'  -> { value.append('\t'); i += 2; continue; }
                    case '"'  -> { value.append('"');  i += 2; continue; }
                    case '\\' -> { value.append('\\'); i += 2; continue; }
                    case '/'  -> { value.append('/');  i += 2; continue; }
                    default   -> { value.append(c);    i++;    continue; }
                }
            }
            if (c == '"') break; // end of string
            value.append(c);
            i++;
        }
        return value.toString();
    }

    // ─── changedFiles extractor ───────────────────────────────────────────────
    // Finds each { "path": "...", "content": "..." } object inside changedFiles array
    // by reading character-by-character to handle nested braces in file content.

    private static List<FileDto> extractChangedFiles(String json,
                                                     List<FileDto> originalFiles) {
        List<FileDto> result = new ArrayList<>();

        // Build path → full path map for auto-correction
        Map<String, String> fileNameToFullPath = new HashMap<>();
        for (FileDto f : originalFiles) {
            String name = f.getPath().substring(f.getPath().lastIndexOf("/") + 1);
            fileNameToFullPath.put(name, f.getPath());
        }

        // Find "changedFiles" array start
        int cfIdx = json.indexOf("\"changedFiles\"");
        if (cfIdx == -1) return result;

        int bracketIdx = json.indexOf("[", cfIdx);
        if (bracketIdx == -1) return result;

        // Walk through the array and extract each object
        int i = bracketIdx + 1;
        while (i < json.length()) {
            // Skip whitespace
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;

            char c = json.charAt(i);

            if (c == ']') break; // end of array
            if (c == ',') { i++; continue; }

            if (c == '{') {
                // Extract this object
                int objStart = i;
                int depth = 0;
                int objEnd = -1;

                // Find the matching closing brace, aware of strings
                boolean inStr = false;
                boolean esc = false;
                for (int j = i; j < json.length(); j++) {
                    char ch = json.charAt(j);
                    if (esc) { esc = false; continue; }
                    if (ch == '\\' && inStr) { esc = true; continue; }
                    if (ch == '"') { inStr = !inStr; continue; }
                    if (!inStr) {
                        if (ch == '{') depth++;
                        else if (ch == '}') {
                            depth--;
                            if (depth == 0) { objEnd = j; break; }
                        }
                    }
                }

                if (objEnd == -1) break;

                String objText = json.substring(objStart, objEnd + 1);

                // Extract path and content from this object
                String path    = extractStringField(objText, "path");
                String content = extractStringField(objText, "content");

                if (path != null && content != null) {
                    // Auto-correct path
                    String correctedPath = path;
                    if (!fileNameToFullPath.containsValue(path)) {
                        String fileName = path.contains("/")
                                ? path.substring(path.lastIndexOf("/") + 1)
                                : path;
                        if (fileNameToFullPath.containsKey(fileName)) {
                            correctedPath = fileNameToFullPath.get(fileName);
                            System.out.println("🔧 Path corrected: " + path + " → " + correctedPath);
                        }
                    }

                    FileDto f = new FileDto();
                    f.setPath(correctedPath);
                    f.setContent(content);
                    result.add(f);
                    System.out.println("📄 Extracted file: " + correctedPath
                            + " (" + content.length() + " chars)");
                }

                i = objEnd + 1;
            } else {
                i++;
            }
        }

        return result;
    }

    // ─── First JSON object extractor ─────────────────────────────────────────

    private static String extractFirstJson(String text) {
        int start = text.indexOf("{");
        if (start == -1) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\' && inStr) { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (!inStr) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ─── Result record ────────────────────────────────────────────────────────

    public record ParseResult(String explanation, List<FileDto> changedFiles) {}
}
