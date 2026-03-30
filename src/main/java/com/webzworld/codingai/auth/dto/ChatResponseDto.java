package com.webzworld.codingai.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponseDto {

    private String conversationId;

    private String message;
    private List<FileDto> changedFiles;
    private String userMessageId;
    private String aiMessageId;
}
