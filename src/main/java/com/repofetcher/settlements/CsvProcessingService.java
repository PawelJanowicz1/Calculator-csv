package com.repofetcher.settlements;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class CsvProcessingService {

    private static final Logger LOGGER = Logger.getLogger(CsvProcessingService.class.getName());

    private static final List<String> NET_EARNINGS_HEADERS_BOLT = List.of("zarobki netto|zł");
    private static final List<String> NET_EARNINGS_HEADERS_UBER = List.of(
            "wypłacono ci : twój przychód",
            "wypłacono ci:twój przychód"
    );
    private static final List<String> NET_EARNINGS_HEADERS_FREENOW = List.of("suma zarobków");

    private static final List<String> TIPS_HEADERS_BOLT = List.of("napiwki od pasażerów|zł");
    private static final List<String> TIPS_HEADERS_UBER = Collections.emptyList();
    private static final List<String> TIPS_HEADERS_FREENOW = List.of("napiwki");

    private static final List<String> CASH_HEADERS_BOLT = List.of("zarobki brutto (płatności gotówkowe)|zł");
    private static final List<String> CASH_HEADERS_UBER = List.of(
            "wypłacono ci : bilans przejazdu : wypłaty : odebrana gotówka",
            "wypłacono ci:bilans przejazdu:wypłaty:odebrana gotówka"
    );
    private static final List<String> CASH_HEADERS_FREENOW = List.of("płatności gotówką/kartą");

    private static final List<String> BONUS_CANCEL_HEADERS_BOLT = List.of("zarobki z kampanii|zł", "opłaty za anulowanie|zł");
    private static final List<String> BONUS_CANCEL_HEADERS_UBER = Collections.emptyList();
    private static final List<String> BONUS_CANCEL_HEADERS_FREENOW = List.of("bonusy");

    private static final String BOLT_DRIVER_NAME_COL = "kierowca";
    private static final String UBER_DRIVER_FNAME_COL = "imię kierowcy";
    private static final String UBER_DRIVER_LNAME_COL = "nazwisko kierowcy";
    private static final String FREENOW_DRIVER_NAME_COL = "kierowca";

    private static final Set<String> SPLIT_DRIVERS;
    private static final Map<String, BigDecimal> COMPANY_BONUS_MAP;
    private static final Map<String, BigDecimal> RENTAL_FEE_MAP;

    static {
        Map<String, BigDecimal> bonusMap = new HashMap<>();
        bonusMap.put(normalizeNameKey("Jan Kowalski"), new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("Anna Nowak"), new BigDecimal("50.00"));
        bonusMap.put(normalizeNameKey("Piotr Zielinski"), new BigDecimal("0.00"));
        COMPANY_BONUS_MAP = Collections.unmodifiableMap(bonusMap);

        Map<String, BigDecimal> rentalMap = new HashMap<>();
        rentalMap.put(normalizeNameKey("Jan Kowalski"), new BigDecimal("500.00"));
        rentalMap.put(normalizeNameKey("Anna Nowak"), BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("Piotr Zielinski"), new BigDecimal("300.00"));
        RENTAL_FEE_MAP = Collections.unmodifiableMap(rentalMap);

        Set<String> splitSet = new HashSet<>();
        splitSet.add(normalizeNameKey("Jan Kowalski"));
        splitSet.add(normalizeNameKey("Piotr Zielinski"));
        SPLIT_DRIVERS = Collections.unmodifiableSet(splitSet);
    }

    public Map<String, DriverData> processFiles(List<Path> paths) throws IOException {
        Map<String, DriverData> allDriversData = new HashMap<>();

        for (Path path : paths) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                CSVFormat format = CSVFormat.EXCEL.builder()
                        .setDelimiter(';')
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setTrim(true)
                        .setIgnoreHeaderCase(true)
                        .setAllowMissingColumnNames(true)
                        .build();

                CSVParser parser = new CSVParser(reader, format);
                Map<String, Integer> headerMap = parser.getHeaderMap();
                if (headerMap == null || headerMap.isEmpty()) {
                    LOGGER.warning("File \"" + path.getFileName() + "\" has empty headers. Skipping.");
                    continue;
                }

                Map<String, Integer> normalizedHeaderMap = headerMap.entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .collect(Collectors.toMap(
                                e -> e.getKey().trim().toLowerCase(),
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        ));

                for (CSVRecord record : parser) {
                    String driverIdentifier = extractDriverIdentifier(record, normalizedHeaderMap);
                    if (driverIdentifier == null) {
                        LOGGER.warning("Failed to read driver in record #" + record.getRecordNumber()
                                + " of file " + path.getFileName() + ". Skipping.");
                        continue;
                    }

                    DriverData currentDriverData = allDriversData
                            .computeIfAbsent(driverIdentifier, DriverData::new);

                    if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_FREENOW)) {
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_FREENOW, DriverData::addFreeNowNetEarningsRaw,
                                TIPS_HEADERS_FREENOW, (d, v) -> {
                                },
                                CASH_HEADERS_FREENOW, DriverData::addFreeNowCashRaw,
                                BONUS_CANCEL_HEADERS_FREENOW, (d, v) -> {
                                }
                        );
                    } else if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_UBER)
                            || (hasAnyHeader(normalizedHeaderMap, UBER_DRIVER_FNAME_COL)
                            && hasAnyHeader(normalizedHeaderMap, UBER_DRIVER_LNAME_COL))) {
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_UBER, DriverData::addUberNetEarningsRaw,
                                TIPS_HEADERS_UBER, (d, v) -> {
                                },
                                CASH_HEADERS_UBER, (d, v) -> d.addUberCashRaw(v.abs()),
                                BONUS_CANCEL_HEADERS_UBER, (d, v) -> {
                                }
                        );
                    } else if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_BOLT)
                            || hasAnyHeader(normalizedHeaderMap, BOLT_DRIVER_NAME_COL)) {
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_BOLT, DriverData::addBoltNetEarningsRaw,
                                TIPS_HEADERS_BOLT, DriverData::addBoltTipsRaw,
                                CASH_HEADERS_BOLT, DriverData::addBoltCashRaw,
                                BONUS_CANCEL_HEADERS_BOLT, DriverData::addBoltBonusOrCancellationRaw
                        );
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error processing file: " + path.getFileName(), e);
            }
        }

        return allDriversData;
    }

    private boolean hasAnyHeader(Map<String, Integer> headerMap, List<String> targetHeaders) {
        return targetHeaders.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(headerMap::containsKey);
    }

    private boolean hasAnyHeader(Map<String, Integer> headerMap, String targetHeader) {
        return headerMap.containsKey(targetHeader.trim().toLowerCase());
    }

    private String extractDriverIdentifier(CSVRecord record, Map<String, Integer> headerMap) {
        String driverName = null;

        if (headerMap.containsKey(UBER_DRIVER_FNAME_COL.toLowerCase())
                && headerMap.containsKey(UBER_DRIVER_LNAME_COL.toLowerCase())) {
            String firstName = record.get(headerMap.get(UBER_DRIVER_FNAME_COL.toLowerCase()));
            String lastName = record.get(headerMap.get(UBER_DRIVER_LNAME_COL.toLowerCase()));
            if (firstName != null && lastName != null
                    && !firstName.trim().isEmpty()
                    && !lastName.trim().isEmpty()) {
                driverName = firstName.trim() + " " + lastName.trim();
            }
        }

        if (driverName == null && headerMap.containsKey(BOLT_DRIVER_NAME_COL.toLowerCase())) {
            String name = record.get(headerMap.get(BOLT_DRIVER_NAME_COL.toLowerCase()));
            if (name != null && !name.trim().isEmpty()) {
                driverName = name.trim();
            }
        }

        if (driverName == null && headerMap.containsKey(FREENOW_DRIVER_NAME_COL.toLowerCase())) {
            String name = record.get(headerMap.get(FREENOW_DRIVER_NAME_COL.toLowerCase()));
            if (name != null && !name.trim().isEmpty()) {
                driverName = name.trim();
            }
        }

        if (driverName == null) {
            return null;
        }
        return normalizeNameKey(driverName);
    }

    private void processRecordForPlatform(
            CSVRecord record,
            Map<String, Integer> headerMap,
            DriverData driverData,
            List<String> netHeaders, BiConsumer<DriverData, BigDecimal> netAdder,
            List<String> tipsHeaders, BiConsumer<DriverData, BigDecimal> tipsAdder,
            List<String> cashHeaders, BiConsumer<DriverData, BigDecimal> cashAdder,
            List<String> bonusHeaders, BiConsumer<DriverData, BigDecimal> bonusAdder
    ) {
        addAmountFromMatchingColumns(record, headerMap, netHeaders, driverData, netAdder);
        addAmountFromMatchingColumns(record, headerMap, tipsHeaders, driverData, tipsAdder);
        addAmountFromMatchingColumns(record, headerMap, cashHeaders, driverData, cashAdder);
        addAmountFromMatchingColumns(record, headerMap, bonusHeaders, driverData, bonusAdder);
    }

    private void addAmountFromMatchingColumns(
            CSVRecord record,
            Map<String, Integer> headerMap,
            List<String> targetHeaders,
            DriverData driverData,
            BiConsumer<DriverData, BigDecimal> valueConsumer
    ) {
        for (String targetHeader : targetHeaders) {
            String norm = targetHeader.trim().toLowerCase();
            if (headerMap.containsKey(norm)) {
                int idx = headerMap.get(norm);
                if (idx < record.size()) {
                    String raw = record.get(idx);
                    try {
                        BigDecimal value = parseDecimal(raw);
                        valueConsumer.accept(driverData, value);
                    } catch (NumberFormatException e) {
                        LOGGER.warning("Invalid number \"" + raw + "\" in column \"" + targetHeader + "\". Skipping value.");
                    }
                }
            }
        }
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        String standardized = value
                .replace(',', '.')
                .replaceAll("[^0-9.\\-]", "");
        if (standardized.isEmpty() || standardized.equals("-") || standardized.equals(".")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(standardized);
    }

    private static String normalizeNameKey(String rawName) {
        String lower = rawName.trim().toLowerCase();
        String noDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String[] parts = noDiacritics.split("\\s+");
        if (parts.length >= 2) {
            return parts[0] + " " + parts[parts.length - 1];
        } else {
            return noDiacritics;
        }
    }

    public void writeDriverDataToCsv(
            Map<String, DriverData> allDriversData,
            Writer writer
    ) throws IOException {
        CSVFormat csvFormat = CSVFormat.EXCEL.builder()
                .setDelimiter(';')
                .setHeader(
                        "Driver",
                        "Total net earnings (after 8% VAT and tip tax)",
                        "Total tips+bonus (after 23% tax)",
                        "Total cash payments",
                        "Payout (after all deductions)"
                )
                .build();

        CSVPrinter printer = new CSVPrinter(writer, csvFormat);

        for (var entry : allDriversData.entrySet()) {
            String driver = entry.getKey();
            DriverData data = entry.getValue();

            BigDecimal totalBoltNet = data.getBoltNetRaw();
            BigDecimal totalBoltCash = data.getBoltCashRaw();
            BigDecimal totalBoltTips = data.getBoltTipsRaw();
            BigDecimal totalBoltBonus = data.getBoltBonusOrCancelRaw();

            BigDecimal totalUberNet = data.getUberNetRaw();
            BigDecimal totalUberCash = data.getUberCashRaw();

            BigDecimal totalFreeNowNet = data.getFreeNowNetRaw();
            BigDecimal totalFreeNowCash = data.getFreeNowCashRaw();

            BigDecimal totalNetRaw = totalBoltNet
                    .add(totalUberNet)
                    .add(totalFreeNowNet);

            BigDecimal totalCashRaw = totalBoltCash
                    .add(totalUberCash)
                    .add(totalFreeNowCash);

            BigDecimal tipsBonusSum = totalBoltTips.add(totalBoltBonus);
            BigDecimal tipsBonusAfterTax = tipsBonusSum.multiply(new BigDecimal("0.77"));

            BigDecimal netAfterVat = totalNetRaw.multiply(new BigDecimal("0.92"));

            BigDecimal rawFinalNet = netAfterVat
                    .add(tipsBonusAfterTax)
                    .subtract(totalCashRaw);

            BigDecimal finalNet = rawFinalNet.setScale(2, BigDecimal.ROUND_HALF_UP);

            BigDecimal finalTips = tipsBonusAfterTax.setScale(2, BigDecimal.ROUND_HALF_UP);

            BigDecimal cashInfo = totalCashRaw.setScale(2, BigDecimal.ROUND_HALF_UP);

            BigDecimal afterDeductions = finalNet.subtract(new BigDecimal("90.00"));

            BigDecimal afterSplit;
            if (SPLIT_DRIVERS.contains(driver)) {
                afterSplit = afterDeductions.divide(BigDecimal.valueOf(2), 2, BigDecimal.ROUND_HALF_UP);
            } else {
                afterSplit = afterDeductions;
            }

            BigDecimal rentalFee = RENTAL_FEE_MAP.getOrDefault(driver, BigDecimal.ZERO);
            BigDecimal afterRental = afterSplit.subtract(rentalFee);

            BigDecimal companyBonus = COMPANY_BONUS_MAP.getOrDefault(driver, BigDecimal.ZERO);
            BigDecimal payout = afterRental
                    .add(companyBonus)
                    .setScale(2, BigDecimal.ROUND_HALF_UP);

            printer.printRecord(
                    driver,
                    finalNet,
                    finalTips,
                    cashInfo,
                    payout
            );
        }

        printer.flush();
    }
}