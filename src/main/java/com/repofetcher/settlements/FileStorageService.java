package com.repofetcher.settlements;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service odpowiedzialny za przyjmowanie MultipartFile i
 * natychmiastowe zapisywanie ich do katalogu "uploads".
 * Przechowuje również listę Path do tych plików.
 */
@Service
public class FileStorageService {

    // Katalog, w którym trzymamy wszystkie wgrane pliki
    private final Path uploadDir = Paths.get("uploads");

    // Lista ścieżek do wgranych plików
    private final List<Path> storedFiles = new ArrayList<>();

    public FileStorageService() throws IOException {
        // Utwórz katalog, jeśli nie istnieje
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }
    }

    /**
     * Zapisuje przekazany MultipartFile do katalogu uploads/
     * i dodaje ścieżkę do storedFiles.
     */
    public void storeFile(MultipartFile file) throws IOException {
        // Sprawdzamy, czy plik jest CSV
        String original = file.getOriginalFilename();
        if (original == null || !original.toLowerCase().endsWith(".csv")) {
            throw new IOException("Niepoprawny format pliku. Proszę wgrać plik .csv.");
        }

        // Ścieżka docelowa, powinna zostać unikalna (dodajemy timestamp, jeśli nazwa się powtórzy)
        Path target = uploadDir.resolve(original);
        if (Files.exists(target)) {
            String baseName = original.replaceFirst("(?i)\\.csv$", "");
            String newName = baseName + "_" + System.currentTimeMillis() + ".csv";
            target = uploadDir.resolve(newName);
        }

        // Kopiujemy zawartość MultipartFile na dysk
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        // Dodajemy Path do listy
        storedFiles.add(target);
    }

    /**
     * Zwraca niemodyfikowalną listę wszystkich Path wgranych plików.
     */
    public List<Path> getAllFilePaths() {
        return Collections.unmodifiableList(storedFiles);
    }

    /**
     * Zwraca liczbę wgranych plików.
     */
    public int getFilesCount() {
        return storedFiles.size();
    }

    /**
     * Czyści zarówno katalog uploads/, jak i wewnętrzną listę Path.
     */
    public void clearFiles() throws IOException {
        for (Path p : storedFiles) {
            Files.deleteIfExists(p);
        }
        storedFiles.clear();
    }
}