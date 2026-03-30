//package com.webzworld.codingai.auth.utils;
//
//import com.webzworld.codingai.auth.dto.FileDto;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//public class SmartFileSelector {
//    public List<FileDto> findRelevantFiles(String prompt, List<FileDto> allFiles) {
//        String lowerPrompt = prompt.toLowerCase();
//        List<FileDto> directMatches = allFiles.stream()
//                .filter(f -> lowerPrompt.contains(getFileName(f.getPath()).toLowerCase()))
//                .collect(Collectors.toList());
//        if (!directMatches.isEmpty()) {
//            return limit(directMatches);
//        }
//        List<String> keywords = extractKeywords(lowerPrompt);
//        List<FileDto> keywordMatches = allFiles.stream()
//                .filter(f ->
//                        !f.getPath().endsWith(".svg") &&
//                                !f.getPath().endsWith(".png") &&
//                                !f.getPath().endsWith(".jpg") &&
//                                !f.getPath().endsWith(".ico")
//                )
//                .filter(f ->
//                        keywords.stream().anyMatch(k ->
//                                f.getPath().toLowerCase().contains(k)
//                        )
//                )
//                .collect(Collectors.toList());
//        if (!keywordMatches.isEmpty()) {
//            return limit(keywordMatches);
//        }
//        return allFiles.stream()
//                .filter(f -> f.getPath().contains("App") ||
//                        f.getPath().contains("index") ||
//                        f.getPath().contains("main"))
//                .limit(5)
//                .collect(Collectors.toList());
//    }
//    private List<String> extractKeywords(String text) {
//        return Arrays.stream(text.split("\\s+"))
//                .filter(w -> w.length() > 2)
//                .filter(w -> !List.of("make","add","fix","update","change","more").contains(w))
//                .collect(Collectors.toList());
//    }
//    private String getFileName(String path) {
//        return path.substring(path.lastIndexOf("/") + 1);
//    }
//    private List<FileDto> limit(List<FileDto> files) {
//        return files.stream().limit(8).collect(Collectors.toList());
//    }
//}


package com.webzworld.codingai.auth.utils;

import com.webzworld.codingai.auth.dto.FileDto;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SmartFileSelector {

    private static final List<String> SKIP_EXTENSIONS = List.of(
            ".svg", ".png", ".jpg", ".jpeg", ".ico", ".gif", ".webp",
            ".lock", ".map", ".min.js", ".min.css"
    );

    private static final List<String> SKIP_FILENAMES = List.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            ".gitignore", ".prettierrc", "eslint.config.js",
            "README.md", "CHANGELOG.md", "vite.config.js",
            "tsconfig.node.json"
    );

    public List<FileDto> findRelevantFiles(String prompt, List<FileDto> allFiles) {
        String lowerPrompt = prompt.toLowerCase();
        List<String> keywords = extractKeywords(lowerPrompt);

        Map<FileDto, Integer> scores = new LinkedHashMap<>();

        for (FileDto file : allFiles) {
            if (shouldSkip(file.getPath())) continue;
            int score = scoreFile(file, lowerPrompt, keywords);
            if (score > 0) scores.put(file, score);
        }

        if (scores.isEmpty()) {
            return allFiles.stream()
                    .filter(f -> !shouldSkip(f.getPath()))
                    .filter(f -> f.getPath().contains("App") ||
                            f.getPath().contains("index") ||
                            f.getPath().contains("main"))
                    .limit(5)
                    .collect(Collectors.toList());
        }

        return scores.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private int scoreFile(FileDto file, String lowerPrompt, List<String> keywords) {
        String path    = file.getPath().toLowerCase();
        String content = file.getContent() != null ? file.getContent().toLowerCase() : "";
        String fileName = getFileName(path);
        String fileNameNoExt = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf(".")) : fileName;
        int score = 0;

        // Exact filename in prompt = strongest signal
        if (lowerPrompt.contains(fileNameNoExt)) score += 20;

        // Keywords in path
        for (String k : keywords) {
            if (path.contains(k)) score += 5;
        }

        // Keywords in content (count occurrences, capped)
        for (String k : keywords) {
            int hits = countOccurrences(content, k);
            score += Math.min(hits * 2, 10);
        }

        // Common important files
        if (path.endsWith("app.jsx") || path.endsWith("app.tsx") || path.endsWith("app.js")) score += 8;
        if (path.endsWith("index.jsx") || path.endsWith("index.tsx")) score += 5;
        if (path.contains("context/") || path.contains("contexts/")) score += 3;
        if (path.startsWith("src/")) score += 2;

        // Penalties
        if (path.endsWith(".css") && !lowerPrompt.contains("css")
                && !lowerPrompt.contains("style")) score -= 3;
        if (path.contains("test") || path.contains("spec")) score -= 5;

        return score;
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) { count++; idx += keyword.length(); }
        return count;
    }

    private boolean shouldSkip(String path) {
        String lower = path.toLowerCase();
        if (SKIP_FILENAMES.contains(getFileName(lower))) return true;
        for (String ext : SKIP_EXTENSIONS) { if (lower.endsWith(ext)) return true; }
        return false;
    }

    private List<String> extractKeywords(String text) {
        Set<String> stop = Set.of("make","add","fix","update","change","more","the","and",
                "for","that","this","with","into","from","our","all","can","you","are",
                "not","but","has","its","also","like","want","need","should","please");
        return Arrays.stream(text.split("\\s+"))
                .map(w -> w.replaceAll("[^a-z0-9]", ""))
                .filter(w -> w.length() > 2)
                .filter(w -> !stop.contains(w))
                .distinct()
                .collect(Collectors.toList());
    }

    private String getFileName(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }
}