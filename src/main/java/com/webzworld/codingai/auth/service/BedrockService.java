package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.FileDto;
import com.webzworld.codingai.auth.service.Impl.BedrockServiceImpl;

import java.util.List;

public interface BedrockService {

    BedrockResponse chat(String userPrompt, List<FileDto> files, List<HistoryMessage> chatHistory);

    record BedrockResponse(String explanation, List<FileDto> changedFiles) {}

    record HistoryMessage(String role, String content) {}
}