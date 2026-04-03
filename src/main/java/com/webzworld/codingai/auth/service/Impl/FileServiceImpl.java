//package com.webzworld.codingai.auth.service.Impl;
//
//import com.webzworld.codingai.auth.dto.FileDto;
//import com.webzworld.codingai.auth.service.FileService;
//import org.springframework.stereotype.Service;
//
//import java.nio.file.Files;
//import java.nio.file.Paths;
//import java.util.ArrayList;
//import java.util.List;
//
//@Service
//public class FileServiceImpl implements FileService {
//    @Override
//    public List<FileDto> loadProjectFiles(String folderPath) {
//        List<FileDto> files = new ArrayList<>();
//        try {
//            Files.walk(Paths.get(folderPath))
//                    .filter(Files::isRegularFile)
//                    .forEach(path -> {
//                        try {
//                            String fullPath = path.toString();
//                            if (fullPath.contains("node_modules") ||
//                                    fullPath.contains(".git") ||
//                                    fullPath.endsWith(".lock") ||
//                                    fullPath.contains("dist") ||
//                                    fullPath.contains("build")) return;
//                            String content = Files.readString(path);
//                            FileDto file = new FileDto();
//                            file.setPath(
//                                    Paths.get(folderPath)
//                                            .relativize(path)
//                                            .toString()
//                                            .replace("\\", "/")
//                            );
//                            file.setContent(content);
//                            files.add(file);
//                        } catch (Exception ignored) {}
//                    });
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to load project files", e);
//        }
//        return files;
//    }
//}

package com.webzworld.codingai.auth.service.Impl;

import com.webzworld.codingai.auth.dto.FileDto;
import com.webzworld.codingai.auth.service.FileService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileServiceImpl implements FileService {

    // Max chars per file before we truncate
    // Code files: 6000 chars (~1500 tokens) — enough for most components
    // CSS files: 3000 chars — we rarely need the whole stylesheet
    private static final int MAX_CODE_CHARS = 6000;
    private static final int MAX_CSS_CHARS  = 3000;

    private static final List<String> SKIP_PATHS = List.of(
            "node_modules", ".git", "dist", "build", ".next", "out", ".cache"
    );

    private static final List<String> SKIP_EXTENSIONS = List.of(
            ".lock", ".map", ".snap", ".log"
    );

    private static final List<String> SKIP_FILENAMES = List.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            ".gitignore", ".prettierrc", "eslint.config.js",
            "README.md", "CHANGELOG.md"
    );

    @Override
    public List<FileDto> loadProjectFiles(String folderPath,String message) {
        List<FileDto> files = new ArrayList<>();
        try {
            System.out.println("FOLDER PATH = " + folderPath);
            Files.walk(Paths.get(folderPath))
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String fullPath = path.toString().replace("\\", "/");

                            // Skip unwanted directories
                            if (SKIP_PATHS.stream().anyMatch(fullPath::contains)) return;

                            String fileName = path.getFileName().toString();

                            // Skip unwanted filenames
                            if (SKIP_FILENAMES.contains(fileName)) return;

                            // Skip unwanted extensions
                            String lower = fileName.toLowerCase();
                            if (SKIP_EXTENSIONS.stream().anyMatch(lower::endsWith)) return;

                            String content = Files.readString(path);

                            // Smart truncation per file type
                            content = extractRelevantChunk(content, message,lower);

                            FileDto file = new FileDto();
                            file.setPath(
                                    Paths.get(folderPath)
                                            .relativize(path)
                                            .toString()
                                            .replace("\\", "/")
                            );
                            file.setContent(content);
                            files.add(file);

                        } catch (Exception ignored) {}
                    });

        } catch (Exception e) {
            throw new RuntimeException("Failed to load project files", e);
        }

        System.out.println("📂 Loaded " + files.size() + " files from " + folderPath);
        return files;
    }

//    private String smartTruncate(String content, String fileNameLower) {
//        int maxChars;
//
//        if (fileNameLower.endsWith(".css") || fileNameLower.endsWith(".scss")) {
//            maxChars = MAX_CSS_CHARS;
//        } else if (fileNameLower.endsWith(".json")) {
//            maxChars = 1000; // JSON configs rarely need more
//        } else {
//            maxChars = MAX_CODE_CHARS;
//        }
//
//        if (content.length() <= maxChars) return content;
//
//        // Smart cut: try to cut at a newline so we don't break mid-line
//        int cutAt = content.lastIndexOf("\n", maxChars);
//        if (cutAt < maxChars / 2) cutAt = maxChars; // no good newline found
//
//        return content.substring(0, cutAt)
//                + "\n\n// ... [file truncated — "
//                + (content.length() - cutAt) + " chars remaining] ...";
//    }
private String extractRelevantChunk(String content, String prompt, String fileName) {
    int maxChars = fileName.endsWith(".css") || fileName.endsWith(".scss")
            ? MAX_CSS_CHARS : MAX_CODE_CHARS;

    if (content.length() <= maxChars) return content;

    // Find the best starting position based on keyword density
    String lowerContent = content.toLowerCase();
    String lowerPrompt = prompt.toLowerCase();
    String[] keywords = lowerPrompt.split("\\s+");

    int bestStart = 0;
    int bestScore = 0;
    int step = 200; // scan every 200 chars

    for (int i = 0; i < content.length() - maxChars; i += step) {
        String window = lowerContent.substring(i, Math.min(i + maxChars, content.length()));
        int score = 0;
        for (String kw : keywords) {
            if (kw.length() > 3) {
                int idx = 0;
                while ((idx = window.indexOf(kw, idx)) != -1) { score++; idx++; }
            }
        }
        if (score > bestScore) { bestScore = score; bestStart = i; }
    }

    // Snap to nearest newline
    int snapStart = content.lastIndexOf("\n", bestStart);
    if (snapStart < 0 || snapStart < bestStart - 200) snapStart = bestStart;

    String chunk = content.substring(snapStart,
            Math.min(snapStart + maxChars, content.length()));

    String prefix = snapStart > 0 ? "// ... [" + snapStart + " chars before] ...\n" : "";
    return prefix + chunk;
}
}