package com.webzworld.codingai.auth.controller;

import com.webzworld.codingai.auth.dto.FolderDto;
import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.service.FolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/apiv1/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @PostMapping
    public ResponseEntity<FolderDto> create(
            @RequestBody FolderDto dto,
            @AuthenticationPrincipal User user) {
        System.out.println("folder controllwer");
        return ResponseEntity.ok(
                folderService.createFolder(user.getId(), dto.getName(), dto.getPath())
        );
    }

    @GetMapping
    public ResponseEntity<List<FolderDto>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(folderService.getAllByUserId(user.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FolderDto> getById(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(folderService.getByIdAndUserId(id, user.getId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FolderDto> update(
            @PathVariable String id,
            @RequestBody FolderDto dto,
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(
                folderService.updateFolder(id, user.getId(), dto.getName(), dto.getPath())
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @AuthenticationPrincipal User user) {

        folderService.deleteFolder(id, user.getId());
        return ResponseEntity.ok().build();
    }
}