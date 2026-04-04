package com.webzworld.codingai.auth.service.Impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webzworld.codingai.auth.dto.FileDto;
import com.webzworld.codingai.auth.service.BedrockService;
import com.webzworld.codingai.auth.utils.BedrockParseHelper;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
public class BedrockServiceImpl implements BedrockService {

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockRuntimeAsyncClient bedrockAsyncClient;
    private final ObjectMapper objectMapper;

    private static final String MODEL_ID =
            "arn:aws:bedrock:ap-south-1:186958336125:inference-profile/global.anthropic.claude-sonnet-4-5-20250929-v1:0";

    public BedrockServiceImpl(
            @Value("${aws.accessKey}") String accessKey,
            @Value("${aws.secretKey}") String secretKey,
            @Value("${aws.region}") String region) {

        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        this.bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(creds)
                .httpClientBuilder(ApacheHttpClient.builder()
                        .socketTimeout(Duration.ofSeconds(120))
                        .connectionTimeout(Duration.ofSeconds(60)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(360))
                        .apiCallAttemptTimeout(Duration.ofSeconds(360))
                        .build())
                .build();

        // Async client — needed for invokeModelWithResponseStream
        this.bedrockAsyncClient = BedrockRuntimeAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(creds)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .readTimeout(Duration.ofSeconds(300))
                        .connectionTimeout(Duration.ofSeconds(60)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(360))
                        .apiCallAttemptTimeout(Duration.ofSeconds(360))
                        .build())
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public BedrockService.BedrockResponse chat(String userPrompt, List<FileDto> files, List<BedrockService.HistoryMessage> chatHistory){
        try {
            String requestBodyJson = buildRequestBody(userPrompt, files, chatHistory);
            System.out.println("🚀 [SYNC] Calling Bedrock — body size: "
                    + requestBodyJson.length() + " chars");
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

    public String chatStream(String userPrompt, List<FileDto> files, List<BedrockService.HistoryMessage> chatHistory, Consumer<String> onChunk) {
        try {
            String requestBodyJson = buildRequestBody(userPrompt, files, chatHistory);
            System.out.println("🚀 [STREAM] Bedrock call — body: "
                    + requestBodyJson.length() + " chars");
            StringBuilder fullText = new StringBuilder();
            // Use CompletableFuture to propagate errors cleanly — no latch needed
            CompletableFuture<Void> streamFuture = new CompletableFuture<>();
            InvokeModelWithResponseStreamRequest streamRequest =
                    InvokeModelWithResponseStreamRequest.builder()
                            .modelId(MODEL_ID)
                            .contentType("application/json")
                            .accept("application/json")
                            .body(SdkBytes.fromUtf8String(requestBodyJson))
                            .build();
            InvokeModelWithResponseStreamResponseHandler handler = InvokeModelWithResponseStreamResponseHandler.builder()
                            .onEventStream(publisher -> publisher.subscribe(new Subscriber<ResponseStream>() {
                                        @Override
                                        public void onSubscribe(Subscription s) {
                                            s.request(Long.MAX_VALUE);
                                        }
                                        @Override
                                        public void onNext(ResponseStream event) {
                                            if (event instanceof PayloadPart) {
                                                PayloadPart pp = (PayloadPart) event;
                                                String chunkJson = pp.bytes().asUtf8String();
                                                String delta = extractTextDelta(chunkJson);
                                                if (delta != null && !delta.isEmpty()) {
                                                    fullText.append(delta);
                                                    onChunk.accept(delta);
                                                }
                                            }
                                        }
                                        @Override
                                        public void onError(Throwable t) {
                                            streamFuture.completeExceptionally(t);
                                        }
                                        @Override
                                        public void onComplete() {
                                            streamFuture.complete(null);
                                        }
                                    }))
                            .build();
            // invokeModelWithResponseStream returns its own CompletableFuture.
            // Chain our streamFuture to it so errors from the SDK level also propagate.
            bedrockAsyncClient.invokeModelWithResponseStream(streamRequest, handler)
                    .whenComplete((result, ex) -> {
                        if (ex != null) streamFuture.completeExceptionally(ex);
                        // onComplete() in subscriber handles the success path
                    });
            // Block current thread until stream is done (max 5 min)
            streamFuture.get(300, java.util.concurrent.TimeUnit.SECONDS);
            String complete = fullText.toString().trim();
            System.out.println("✅ [STREAM] Complete — " + complete.length() + " chars");
            return complete;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RuntimeException("Bedrock stream timed out after 5 minutes", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("Bedrock stream failed: " + e.getCause().getMessage(),
                    e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("Bedrock stream failed: " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String userPrompt, List<FileDto> files, List<BedrockService.HistoryMessage> chatHistory) throws Exception {
        String systemPrompt = buildSystemPrompt();
        String userMessage  = buildUserMessage(userPrompt, files);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("anthropic_version", "bedrock-2023-05-31");
        requestBody.put("max_tokens", 8096);
        requestBody.put("temperature", 0);
        requestBody.put("system", systemPrompt);

        ArrayNode messagesArray = objectMapper.createArrayNode();

        for (BedrockService.HistoryMessage h : chatHistory) {
            ObjectNode msg        = objectMapper.createObjectNode();
            ArrayNode  contentArr = objectMapper.createArrayNode();
            ObjectNode textBlock  = objectMapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", h.content());
            contentArr.add(textBlock);
            msg.put("role", h.role());
            msg.set("content", contentArr);
            messagesArray.add(msg);
        }

        if (messagesArray.isEmpty() ||
                !"user".equals(messagesArray.get(0).get("role").asText())) {
            messagesArray.insert(0, makeUserMessage(userMessage));
        }

        if (!"user".equals(messagesArray.get(messagesArray.size() - 1)
                .get("role").asText())) {
            messagesArray.add(makeUserMessage(userMessage));
        }

        requestBody.set("messages", messagesArray);
        return objectMapper.writeValueAsString(requestBody);
    }

    private ObjectNode makeUserMessage(String text) {
        ObjectNode msg        = objectMapper.createObjectNode();
        ArrayNode  contentArr = objectMapper.createArrayNode();
        ObjectNode textBlock  = objectMapper.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        contentArr.add(textBlock);
        msg.put("role", "user");
        msg.set("content", contentArr);
        return msg;
    }

    private String extractTextDelta(String chunkJson) {
        try {
            JsonNode node = objectMapper.readTree(chunkJson);
            String type = node.path("type").asText("");
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText(""))) {
                    return delta.path("text").asText("");
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("⚠️ Unparseable stream chunk: " + chunkJson);
            return null;
        }
    }

    private String buildSystemPrompt() {
        return """
                You are an expert AI coding assistant inside a code editor.
                
                ─────────────────────────────────────────
                ⚠️ CRITICAL — EMPTY PROJECT BEHAVIOR:
                
                If PROJECT FILES is empty or has 0 files:
                - This is a NEW empty project
                - DO NOT call find_files or read_files
                - You MAY call run_command to scaffold the project
                
                Examples:
                - React → run_command "npm create vite@latest my-app"
                - Node → run_command "npm init -y"
                - Express → run_command "npm init -y"
                
                    After running a command:
                    
                    - Check if setup is COMPLETE
                    - If dependencies are missing → run npm install
                    - If project not running → run npm run dev
                    - You MAY call multiple run_command steps in sequence
                    
                    ONLY return final JSON when:
                    - Project is fully ready OR
                    - Code changes are required
                
                ─────────────────────────────────────────
                TOOLS AVAILABLE:
                
                To search:
                { "tool": "find_files", "query": "navbar" }
                
                To read:
                { "tool": "read_files", "paths": ["src/App.jsx"] }
                
                To run terminal:
                { "tool": "run_command", "cmd": "npm install axios" }
                
                ─────────────────────────────────────────
                ⚠️ TOOL RULES:
                
                - If PROJECT FILES is empty:
                  → ONLY use run_command (if needed)
                
                - If PROJECT FILES is NOT empty:
                  → Use find_files / read_files
                
                - Call ONLY ONE tool at a time
                - Return ONLY tool JSON when calling a tool
                
                ─────────────────────────────────────────
                FINAL ANSWER FORMAT:
                
                {
                  "explanation": "what you did",
                  "changedFiles": [
                    { "path": "file", "content": "full content" }
                  ]
                }
                
                ─────────────────────────────────────────
                ⚠️ JSON RULES:
                
                - Start with {, end with }
                - No markdown
                - No extra text
                - Escape \\n and \\" properly
                - Return FULL files only
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