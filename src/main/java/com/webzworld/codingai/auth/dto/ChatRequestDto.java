package com.webzworld.codingai.auth.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ChatRequestDto {

    private String conversationId;
    private String folderPath;

    @NotNull(message = "message is required")
    @NotBlank(message = "message cannot be blank")
    private String message;

    @NotNull(message = "files list is required")
    private List<FileDto> files;
}
