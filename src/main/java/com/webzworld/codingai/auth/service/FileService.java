package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.FileDto;

import java.util.List;

public interface FileService {
    List<FileDto> loadProjectFiles(String folderPath,String message);
}
