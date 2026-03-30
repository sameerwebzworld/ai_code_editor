package com.webzworld.codingai.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ConversationDto {
    private String id;
    private String title;
    private String folderPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
