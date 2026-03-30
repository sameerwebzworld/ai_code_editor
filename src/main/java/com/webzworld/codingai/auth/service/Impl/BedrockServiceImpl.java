package com.webzworld.codingai.auth.service.Impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webzworld.codingai.auth.dto.FileDto;
import com.webzworld.codingai.auth.service.BedrockService;
import com.webzworld.codingai.auth.utils.BedrockParseHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.time.Duration;
import java.util.List;

@Service
public class BedrockServiceImpl implements BedrockService {

    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;

    private static final String MODEL_ID = "arn:aws:bedrock:ap-south-1:186958336125:inference-profile/global.anthropic.claude-sonnet-4-5-20250929-v1:0";

    public BedrockServiceImpl(
            @Value("${aws.accessKey}") String accessKey,
            @Value("${aws.secretKey}") String secretKey,
            @Value("${aws.region}") String region
    ) {
        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .httpClientBuilder(
                        ApacheHttpClient.builder()
                                .socketTimeout(Duration.ofSeconds(120))
                                .connectionTimeout(Duration.ofSeconds(60))
                )
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(360))
                        .apiCallAttemptTimeout(Duration.ofSeconds(360))
                        .build())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public BedrockResponse chat(String userPrompt, List<FileDto> files, List<HistoryMessage> chatHistory) {
        try {
            String systemPrompt = buildSystemPrompt();
            String userMessage = buildUserMessage(userPrompt, files);
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("anthropic_version", "bedrock-2023-05-31");
            requestBody.put("max_tokens", 8096);
            requestBody.put("temperature", 0);
            requestBody.put("system", systemPrompt);
            ArrayNode messagesArray = objectMapper.createArrayNode();
            String lastRole = null;
            for (HistoryMessage h : chatHistory) {
                String role = h.role();
//                if (lastRole != null && lastRole.equals(role)) continue;
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", role);
                ArrayNode contentArr = objectMapper.createArrayNode();
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", h.content());
                contentArr.add(textBlock);
                msg.set("content", contentArr);
                messagesArray.add(msg);
                lastRole = role;
            }
            if (messagesArray.size() == 0 ||
                    !"user".equals(messagesArray.get(0).get("role").asText())) {
                ObjectNode firstMsg = objectMapper.createObjectNode();
                firstMsg.put("role", "user");
                ArrayNode contentArr = objectMapper.createArrayNode();
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", userMessage);
                contentArr.add(textBlock);
                firstMsg.set("content", contentArr);
                messagesArray.insert(0, firstMsg);
            }
            if (!"user".equals(messagesArray.get(messagesArray.size() - 1).get("role").asText())) {
                ObjectNode currentMsg = objectMapper.createObjectNode();
                currentMsg.put("role", "user");
                ArrayNode contentArr = objectMapper.createArrayNode();
                ObjectNode textBlock = objectMapper.createObjectNode();
                textBlock.put("type", "text");
                textBlock.put("text", userMessage);
                contentArr.add(textBlock);
                currentMsg.set("content", contentArr);
                messagesArray.add(currentMsg);
            }
            requestBody.set("messages", messagesArray);
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            System.out.println("🚀 Calling Bedrock with model: " + MODEL_ID);
            System.out.println("📦 Request body size: " + requestBodyJson.length() + " chars");
            InvokeModelRequest invokeRequest = InvokeModelRequest.builder()
                    .modelId(MODEL_ID)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestBodyJson))
                    .build();
            InvokeModelResponse response = bedrockClient.invokeModel(invokeRequest);
            String responseBody = response.body().asUtf8String();
            System.out.println("🧠 RAW AI RESPONSE:\n" + responseBody);
            return parseBedrockResponse(responseBody, files);
        } catch (Exception e) {
            throw new RuntimeException("Bedrock call failed: " + e.getMessage(), e);
        }
    }

    private String buildSystemPrompt() {
        return """
                You are an expert AI coding assistant inside a code editor.
                
                You will receive a TASK and PROJECT FILES.
                
                ─────────────────────────────────────────
                TOOLS (use ONLY if files you need are missing):
                
                To search: { "tool": "find_files", "query": "navbar" }
                To read:   { "tool": "read_files", "paths": ["src/components/Navbar.jsx"] }
                
                ⚠️ TOOL RULES (STRICT):
                
                - You are given PARTIAL project files (may be incomplete).
                - If you are NOT 100% sure → MUST call tools.
                - DO NOT guess missing code.
                - ALWAYS prefer calling tools before answering.
                
                - Use find_files to search
                - Use read_files to read full file
                
                - Call ONLY ONE tool at a time
                - Return ONLY tool JSON (no explanation)
                
                EXAMPLES:
                
                { "tool": "find_files", "query": "navbar" }
                
                { "tool": "read_files", "paths": ["src/components/Navbar.jsx"] }
                ─────────────────────────────────────────
                FINAL ANSWER FORMAT:
                
                {
                  "explanation": "what you did, in plain English",
                  "changedFiles": [
                    { "path": "src/components/Navbar.jsx", "content": "full file content here" }
                  ]
                }
                
                ──────────────────────────────
                CODE QUALITY & STYLE REQUIREMENTS:
                
                - Write professional, clean, and maintainable code.
                - Use industry best practices for the language and framework.
                - Follow SOLID principles, separation of concerns, and consistent naming.
                - Write responsive and user-friendly UI for frontend code.
                - Avoid placeholder text or generic layouts; produce thoughtful design.
                ──────────────────────────────
                LANGUAGE & FRAMEWORK GUIDANCE:
                
                - React frontend: use functional components, hooks, and modern CSS.
                - Java backend: use Spring Boot conventions, service layers, and DTOs.
                - Node backend: modularize code with async/await and error handling.
                - Adjust accordingly based on project files.
                ──────────────────────────────
                “When working across multiple languages or frameworks in the same project, ensure consistent design and naming conventions to maintain overall codebase quality.”
                
                ⚠️ JSON RULES (CRITICAL):
                - Start with {, end with }
                - No markdown fences
                - No text outside the JSON
                - Escape ALL special chars in content: \\n for newlines, \\" for quotes
                - Return COMPLETE file content, not partial
                - changedFiles = [] if nothing changed
                ─────────────────────────────────────────
                """;
    }


    private String buildUserMessage(String userPrompt, List<FileDto> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: ").append(userPrompt).append("\n\n");
        sb.append("PROJECT FILES:\n");
        sb.append("=".repeat(50)).append("\n\n");
        for (FileDto file : files) {
            sb.append("FILE: ").append(file.getPath()).append("\n");
            sb.append("-".repeat(40)).append("\n");
            String content = file.getContent();
            if (content.length() > 4000) {
                content = content.substring(0, 4000) + "\n...truncated...";
            }
            sb.append(content);
        }
        return sb.toString();
    }

    private BedrockResponse parseBedrockResponse(String rawResponse, List<FileDto> originalFiles) {
        BedrockParseHelper.ParseResult result =
                BedrockParseHelper.parseBedrockResponse(rawResponse, originalFiles, objectMapper);
        return new BedrockResponse(result.explanation(), result.changedFiles());
    }

}