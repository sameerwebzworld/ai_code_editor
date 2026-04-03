package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.FolderDto;

import java.util.List;

public interface FolderService {

    List<FolderDto> getAllByUserId(String userId);

    FolderDto getByIdAndUserId(String id, String userId);

    FolderDto createFolder(String userId, String name, String path);

    FolderDto updateFolder(String id, String userId, String name, String path);

    void deleteFolder(String id, String userId);
}