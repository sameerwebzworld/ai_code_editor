package com.webzworld.codingai.auth.service.Impl;


import com.webzworld.codingai.auth.dto.FolderDto;
import com.webzworld.codingai.auth.entity.Folder;
import com.webzworld.codingai.auth.repo.FolderRepository;
import com.webzworld.codingai.auth.service.FolderService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;


@Service
public class FolderServiceImpl implements FolderService {

    private final FolderRepository folderRepository;

    public FolderServiceImpl(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    @Override
    public List<FolderDto> getAllByUserId(String userId) {
        return folderRepository.findByUserId(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public FolderDto getByIdAndUserId(String id, String userId) {
        Folder folder = folderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
        return toDto(folder);
    }

    @Override
    public FolderDto createFolder(String userId, String name, String path) {
        Folder folder = new Folder();
        folder.setUserId(userId);
        folder.setName(name);
        folder.setPath(path);
        folder = folderRepository.save(folder);
        return toDto(folder);
    }

    @Override
    public FolderDto updateFolder(String id, String userId, String name, String path) {
        Folder folder = folderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));

        if (name != null && !name.isBlank()) folder.setName(name);
        if (path != null) folder.setPath(path);

        folder = folderRepository.save(folder);
        return toDto(folder);
    }

    @Override
    public void deleteFolder(String id, String userId) {
        Folder folder = folderRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new RuntimeException("Folder not found"));
        folderRepository.delete(folder);
    }

    private FolderDto toDto(Folder folder) {
        FolderDto dto = new FolderDto();
        dto.setId(folder.getId());
        dto.setName(folder.getName());
        // For now, conversations can be empty or fetched from a service if needed
        dto.setConversations(List.of());
        return dto;
    }
}