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
public class FolderDto {
    private String id;
    private String name;
    private List<ConversationDto> conversations;
    private String path;
}
