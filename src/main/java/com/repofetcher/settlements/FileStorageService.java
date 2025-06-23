package com.repofetcher.settlements;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class FileStorageService {

    private final Path uploadDir = Paths.get("uploads");
    private final List<Path> storedFiles = new ArrayList<>();

    public FileStorageService() throws IOException {
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    public void storeFile(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        if (original == null || !original.toLowerCase().endsWith(".csv")) {
            throw new IOException("Niepoprawny format pliku. Proszę wgrać plik .csv.");
        }
        Path target = uploadDir.resolve(original);
        if (Files.exists(target)) {
            String baseName = original.replaceFirst("(?i)\\.csv$", "");
            String newName = baseName + "_" + System.currentTimeMillis() + ".csv";
            target = uploadDir.resolve(newName);
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        storedFiles.add(target);
    }

    public List<Path> getAllFilePaths() {
        return Collections.unmodifiableList(storedFiles);
    }

    public int getFilesCount() {
        return storedFiles.size();
    }

    public void clearFiles() throws IOException {
        for (Path p : storedFiles) {
            Files.deleteIfExists(p);
        }
        storedFiles.clear();
    }
}