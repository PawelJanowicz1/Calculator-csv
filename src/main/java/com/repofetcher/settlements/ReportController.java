package com.repofetcher.settlements;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Controller
public class ReportController {

    private static final Logger LOGGER = Logger.getLogger(ReportController.class.getName());

    private final FileStorageService fileStorageService;
    private final CsvProcessingService csvProcessingService;

    @Autowired
    public ReportController(FileStorageService fileStorageService,
                            CsvProcessingService csvProcessingService) {
        this.fileStorageService = fileStorageService;
        this.csvProcessingService = csvProcessingService;
    }

    /**
     * Strona główna – pokazuje liczbę wgranych plików.
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("filesCount", fileStorageService.getFilesCount());
        return "index";
    }

    /**
     * Endpoint do wgrywania pojedynczego pliku CSV.
     * Zapisuje go natychmiast w katalogu uploads/.
     */
    @PostMapping("/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Proszę wybrać plik CSV do wgrania.");
            return "redirect:/";
        }
        try {
            fileStorageService.storeFile(file);
            LOGGER.info("File uploaded successfully: " + file.getOriginalFilename());
            redirectAttributes.addFlashAttribute("successMessage", "Plik wgrany: " + file.getOriginalFilename());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to upload file: " + file.getOriginalFilename(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Nie udało się wgrać pliku: " + file.getOriginalFilename() + ". " + e.getMessage());
        }
        return "redirect:/";
    }

    /**
     * Po kliknięciu "Generuj CSV" – bierzemy z FileStorageService listę Path
     * i przekazujemy je do processFiles(...). Następnie strumieniujemy wynik jako
     * plik CSV do przeglądarki.
     */
    @PostMapping("/generate")
    public void generateCsv(HttpServletResponse response, RedirectAttributes redirectAttributes) {
        List<Path> paths = fileStorageService.getAllFilePaths();
        if (paths.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Wgraj przynajmniej jeden plik przed generowaniem CSV.");
            try {
                response.sendRedirect("/");
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Error redirecting", ex);
            }
            return;
        }

        try {
            // Przetwarzamy pliki CSV z dysku
            Map<String, DriverData> processedData = csvProcessingService.processFiles(paths);

            // Ustawienia odpowiedzi jako plik CSV
            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"podsumowanie_kierowcow.csv\"");

            // Dodajemy BOM UTF-8, by Excel rozpoznał polskie znaki
            response.getOutputStream().write(0xEF);
            response.getOutputStream().write(0xBB);
            response.getOutputStream().write(0xBF);

            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            csvProcessingService.writeDriverDataToCsv(processedData, writer);
            writer.flush();

            LOGGER.info("CSV report for drivers generated successfully.");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating CSV file for drivers", e);
            try {
                // Jeżeli już część odpowiedzi wysłaliśmy, nie można ponownie zrobić redirectu
                if (!response.isCommitted()) {
                    redirectAttributes.addFlashAttribute("errorMessage", "Błąd podczas generowania pliku CSV: " + e.getMessage());
                    response.sendRedirect("/");
                }
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Error redirecting after CSV generation failure", ex);
            }
        }
    }

    /**
     * Po kliknięciu "Resetuj": usuwamy wszystkie pliki z katalogu i czyścimy listę.
     */
    @PostMapping("/reset")
    public String resetApplication(RedirectAttributes redirectAttributes) {
        try {
            fileStorageService.clearFiles();
            redirectAttributes.addFlashAttribute("successMessage", "Aplikacja zresetowana, wszystkie pliki usunięte.");
            LOGGER.info("Application reset. All uploaded files cleared.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error clearing files on reset", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Błąd podczas resetowania aplikacji: " + e.getMessage());
        }
        return "redirect:/";
    }
}