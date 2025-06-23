package com.repofetcher.settlements;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("filesCount", fileStorageService.getFilesCount());
        return "index";
    }

    @PostMapping("/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   RedirectAttributes redirectAttributes) {
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a CSV file to upload.");
            return "redirect:/";
        }
        try {
            fileStorageService.storeFile(file);
            LOGGER.info("File uploaded successfully: " + file.getOriginalFilename());
            redirectAttributes.addFlashAttribute("successMessage", "File uploaded: " + file.getOriginalFilename());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to upload file: " + file.getOriginalFilename(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Failed to upload file: " + file.getOriginalFilename() + ". " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/generate")
    public void generateCsv(HttpServletResponse response, RedirectAttributes redirectAttributes) {
        List<Path> paths = fileStorageService.getAllFilePaths();
        if (paths.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload at least one file before generating the CSV.");
            try {
                response.sendRedirect("/");
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Error redirecting to index", ex);
            }
            return;
        }

        try {
            Map<String, DriverData> processedData = csvProcessingService.processFiles(paths);

            response.setContentType("text/csv; charset=UTF-8");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"driver_summary.csv\"");

            response.getOutputStream().write(0xEF);
            response.getOutputStream().write(0xBB);
            response.getOutputStream().write(0xBF);

            OutputStreamWriter writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
            csvProcessingService.writeDriverDataToCsv(processedData, writer);
            writer.flush();

            LOGGER.info("CSV report generated successfully.");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error generating CSV report", e);
            try {
                if (!response.isCommitted()) {
                    redirectAttributes.addFlashAttribute("errorMessage", "Error generating CSV: " + e.getMessage());
                    response.sendRedirect("/");
                }
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "Error redirecting after CSV generation failure", ex);
            }
        }
    }

    @PostMapping("/reset")
    public String resetApplication(RedirectAttributes redirectAttributes) {
        try {
            fileStorageService.clearFiles();
            redirectAttributes.addFlashAttribute("successMessage", "Application reset successfully. All uploaded files have been deleted.");
            LOGGER.info("Application reset. All uploaded files cleared.");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error resetting application", e);
            redirectAttributes.addFlashAttribute("errorMessage", "Error resetting application: " + e.getMessage());
        }
        return "redirect:/";
    }
}