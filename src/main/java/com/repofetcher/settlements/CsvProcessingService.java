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
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Service
public class CsvProcessingService {

    private static final Logger LOGGER = Logger.getLogger(CsvProcessingService.class.getName());

    // Nagłówki do rozpoznania kolumn „netto”, „napiwki”, „gotówka” i „bonus/anulowanie”
    private static final List<String> NET_EARNINGS_HEADERS_BOLT    = List.of("zarobki netto|zł");
    private static final List<String> NET_EARNINGS_HEADERS_UBER    = List.of(
            "wypłacono ci : twój przychód",
            "wypłacono ci:twój przychód"
    );
    private static final List<String> NET_EARNINGS_HEADERS_FREENOW = List.of("suma zarobków");

    private static final List<String> TIPS_HEADERS_BOLT            = List.of("napiwki od pasażerów|zł");
    private static final List<String> TIPS_HEADERS_UBER            = Collections.emptyList();
    private static final List<String> TIPS_HEADERS_FREENOW         = List.of("napiwki");

    private static final List<String> CASH_HEADERS_BOLT            = List.of("zarobki brutto (płatności gotówkowe)|zł");
    private static final List<String> CASH_HEADERS_UBER            = List.of(
            "wypłacono ci : bilans przejazdu : wypłaty : odebrana gotówka",
            "wypłacono ci:bilans przejazdu:wypłaty:odebrana gotówka"
    );
    private static final List<String> CASH_HEADERS_FREENOW         = List.of("płatności gotówką/kartą");

    private static final List<String> BONUS_CANCEL_HEADERS_BOLT    = List.of("zarobki z kampanii|zł", "opłaty za anulowanie|zł");
    private static final List<String> BONUS_CANCEL_HEADERS_UBER    = Collections.emptyList();
    private static final List<String> BONUS_CANCEL_HEADERS_FREENOW = List.of("bonusy");

    // Kolumny z nazwą kierowcy
    private static final String BOLT_DRIVER_NAME_COL    = "kierowca";
    private static final String UBER_DRIVER_FNAME_COL   = "imię kierowcy";
    private static final String UBER_DRIVER_LNAME_COL   = "nazwisko kierowcy";
    private static final String FREENOW_DRIVER_NAME_COL = "kierowca";

    // Zestaw kierowców, których dochód dzielimy 50/50
    // identyfikator = imię + " " + nazwisko w lowercase
    private static final Set<String> SPLIT_DRIVERS = Set.of(
            "michał fopke",
            "andrzej netkowski",
            "maciej dziórawiec",
            "maciej opara",
            "kinga mikołajczyk",
            "przemysław tartanus"
            // dodaj tu pozostałych, którzy dzielą 50/50
    );

    // Stałe bonusy od firmy (bez dzielenia paliwa ani VAT itp.)
    private static final Map<String, BigDecimal> COMPANY_BONUS_MAP;
    static {
        Map<String, BigDecimal> bonusMap = new HashMap<>();
        bonusMap.put("michał fopke",          new BigDecimal("125.00"));
        bonusMap.put("fopke michał",          new BigDecimal("125.00"));
        bonusMap.put("maciej opara",          new BigDecimal("125.00"));
        bonusMap.put("opara maciej",          new BigDecimal("125.00"));
        bonusMap.put("maciej dziórawiec",     new BigDecimal("125.00"));
        bonusMap.put("dziórawiec maciej",     new BigDecimal("125.00"));
        bonusMap.put("kinga mikołajczyk",     new BigDecimal("125.00"));
        bonusMap.put("mikołajczyk kinga",     new BigDecimal("125.00"));
        bonusMap.put("andrzej netkowski",     new BigDecimal("125.00"));
        bonusMap.put("netkowski andrzej",     new BigDecimal("125.00"));
        bonusMap.put("piotr marek olszowski", new BigDecimal("100.00"));
        bonusMap.put("piotr olszowski",       new BigDecimal("100.00"));
        bonusMap.put("janusz śliwiński",      new BigDecimal("100.00"));
        bonusMap.put("śliwiński janusz",      new BigDecimal("100.00"));
        bonusMap.put("artem lukianenko",      new BigDecimal("100.00"));
        bonusMap.put("lukianenko artem",      new BigDecimal("100.00"));
        bonusMap.put("paweł plenikowski",     new BigDecimal("0.00"));
        bonusMap.put("plenikowski paweł",     new BigDecimal("0.00"));
        COMPANY_BONUS_MAP = Collections.unmodifiableMap(bonusMap);
    }

    /**
     * Przetwarza pliki CSV (Bolt, FreeNow, Uber) i zbiera surowe wartości
     * do DriverData (bez żadnych korekt podatkowych).
     */
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
                    continue;
                }

                // Normalizujemy klucze nagłówków (trim, lowercase)
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
                        continue;
                    }
                    DriverData currentDriverData = allDriversData
                            .computeIfAbsent(driverIdentifier, DriverData::new);

                    if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_FREENOW)) {
                        // FreeNow: pobieramy net i cash, pomijamy tips/bonus
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_FREENOW,   DriverData::addFreeNowNetEarningsRaw,
                                TIPS_HEADERS_FREENOW,           (d, v) -> { },
                                CASH_HEADERS_FREENOW,           DriverData::addFreeNowCashRaw,
                                BONUS_CANCEL_HEADERS_FREENOW,   (d, v) -> { }
                        );
                    }
                    else if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_UBER)
                            || (hasAnyHeader(normalizedHeaderMap, UBER_DRIVER_FNAME_COL)
                            && hasAnyHeader(normalizedHeaderMap, UBER_DRIVER_LNAME_COL))) {
                        // Uber: pobieramy net i cash (weźmiemy wartość bezwzględną), pomijamy tips/bonus
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_UBER,      DriverData::addUberNetEarningsRaw,
                                TIPS_HEADERS_UBER,              (d, v) -> { },
                                CASH_HEADERS_UBER,              (d, v) -> d.addUberCashRaw(v.abs()),
                                BONUS_CANCEL_HEADERS_UBER,      (d, v) -> { }
                        );
                    }
                    else if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_BOLT)
                            || hasAnyHeader(normalizedHeaderMap, BOLT_DRIVER_NAME_COL)) {
                        // Bolt: pobieramy wszystkie cztery typy: net, tips, cash, bonus/cancel
                        processRecordForPlatform(
                                record, normalizedHeaderMap, currentDriverData,
                                NET_EARNINGS_HEADERS_BOLT,      DriverData::addBoltNetEarningsRaw,
                                TIPS_HEADERS_BOLT,              DriverData::addBoltTipsRaw,
                                CASH_HEADERS_BOLT,              DriverData::addBoltCashRaw,
                                BONUS_CANCEL_HEADERS_BOLT,      DriverData::addBoltBonusOrCancellationRaw
                        );
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error processing file: " + path.getFileName(), e);
            }
        }

        return allDriversData;
    }

    private boolean hasAnyHeader(Map<String, Integer> normalizedHeaderMap, List<String> targetHeaders) {
        return targetHeaders.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(normalizedHeaderMap::containsKey);
    }

    private boolean hasAnyHeader(Map<String, Integer> normalizedHeaderMap, String targetHeader) {
        return normalizedHeaderMap.containsKey(targetHeader.trim().toLowerCase());
    }

    private String extractDriverIdentifier(CSVRecord record, Map<String, Integer> headerMap) {
        String driverName = null;

        // Uber: dwie kolumny „Imię kierowcy” + „Nazwisko kierowcy”
        if (headerMap.containsKey(UBER_DRIVER_FNAME_COL.toLowerCase())
                && headerMap.containsKey(UBER_DRIVER_LNAME_COL.toLowerCase())) {
            String firstName = record.get(headerMap.get(UBER_DRIVER_FNAME_COL.toLowerCase()));
            String lastName  = record.get(headerMap.get(UBER_DRIVER_LNAME_COL.toLowerCase()));
            if (firstName != null && lastName != null
                    && !firstName.trim().isEmpty()
                    && !lastName.trim().isEmpty()) {
                driverName = firstName.trim() + " " + lastName.trim();
            }
        }

        // Bolt: pojedyncza kolumna „Kierowca”
        if (driverName == null && headerMap.containsKey(BOLT_DRIVER_NAME_COL.toLowerCase())) {
            String name = record.get(headerMap.get(BOLT_DRIVER_NAME_COL.toLowerCase()));
            if (name != null && !name.trim().isEmpty()) {
                driverName = name.trim();
            }
        }

        // FreeNow: też kolumna „Kierowca”
        if (driverName == null && headerMap.containsKey(FREENOW_DRIVER_NAME_COL.toLowerCase())) {
            String name = record.get(headerMap.get(FREENOW_DRIVER_NAME_COL.toLowerCase()));
            if (name != null && !name.trim().isEmpty()) {
                driverName = name.trim();
            }
        }

        return (driverName == null) ? null : driverName.trim().toLowerCase();
    }

    private void processRecordForPlatform(
            CSVRecord record,
            Map<String, Integer> normalizedHeaderMap,
            DriverData driverData,
            List<String> netEarningsHeaders,   BiConsumer<DriverData, BigDecimal> netEarningsAdder,
            List<String> tipsHeaders,          BiConsumer<DriverData, BigDecimal> tipsAdder,
            List<String> cashHeaders,          BiConsumer<DriverData, BigDecimal> cashAdder,
            List<String> bonusCancelHeaders,   BiConsumer<DriverData, BigDecimal> bonusCancelAdder
    ) {
        addAmountFromMatchingColumns(record, normalizedHeaderMap, netEarningsHeaders,  driverData, netEarningsAdder);
        addAmountFromMatchingColumns(record, normalizedHeaderMap, tipsHeaders,         driverData, tipsAdder);
        addAmountFromMatchingColumns(record, normalizedHeaderMap, cashHeaders,         driverData, cashAdder);
        addAmountFromMatchingColumns(record, normalizedHeaderMap, bonusCancelHeaders,  driverData, bonusCancelAdder);
    }

    private void addAmountFromMatchingColumns(
            CSVRecord record,
            Map<String, Integer> normalizedHeaderMap,
            List<String> targetHeaders,
            DriverData driverData,
            BiConsumer<DriverData, BigDecimal> valueConsumer
    ) {
        for (String targetHeader : targetHeaders) {
            String normTarget = targetHeader.trim().toLowerCase();
            if (normalizedHeaderMap.containsKey(normTarget)) {
                int colIndex = normalizedHeaderMap.get(normTarget);
                if (colIndex < record.size()) {
                    String rawValue = record.get(colIndex);
                    try {
                        BigDecimal value = parseDecimal(rawValue);
                        valueConsumer.accept(driverData, value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    private BigDecimal parseDecimal(String value) throws NumberFormatException {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Zamieniamy przecinki na kropki i usuwamy nie–cyfrowe znaki
        String standardized = value.replace(',', '.').replaceAll("[^0-9.\\-]", "");
        if (standardized.isEmpty() || standardized.equals("-") || standardized.equals(".")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(standardized);
    }

    /**
     * Zapisuje wyniki do CSV. Kolejność obliczeń:
     *  1) totalNetRaw = suma wszystkich „netto” (Bolt + Uber + FreeNow)
     *  2) totalCashRaw = suma wszystkich „cash” (Bolt + Uber + FreeNow)
     *  3) sumBoltTipsBonus = BoltTipsRaw + BoltBonusRaw
     *  4) totalTipsBonusAfterTax = sumBoltTipsBonus * 0.77
     *  5) totalNetAfterVat = totalNetRaw * 0.92
     *  6) rawFinalNet = totalNetAfterVat + totalTipsBonusAfterTax – totalCashRaw
     *  7) finalNet = rawFinalNet zaokrąglone do 2 miejsc (HALF_UP)
     *  8) finalTips = totalTipsBonusAfterTax zaokrąglone do 2 miejsc
     *  9) cashInfo = totalCashRaw zaokrąglone do 2 miejsc
     * 10) Każdemu kierowcy odejmujemy 90 zł (50 opłata aplikacji + 40 ZUS)
     * 11) Jeżeli kierowca należy do listy SPLIT_DRIVERS, dzielimy wartość przez 2
     * 12) Dodajemy bonus z COMPANY_BONUS_MAP (jeżeli jest)
     * 13) Zaokrąglamy „Do wypłaty” do 2 miejsc (HALF_UP)
     */
    public void writeDriverDataToCsv(
            Map<String, DriverData> allDriversData,
            Writer writer
    ) throws IOException {

        CSVFormat csvFormat = CSVFormat.EXCEL.builder()
                .setDelimiter(';')
                .setHeader(
                        "Kierowca",
                        "Łączne zarobki netto (po VAT 8% i opodatkowaniu napiwków)",
                        "Łączne napiwki+bonusy (po 23% podatku)",
                        "Łączne płatności gotówką",
                        "Do wypłaty(bez kosztów paliwa)"
                )
                .build();

        CSVPrinter printer = new CSVPrinter(writer, csvFormat);

        for (var entry : allDriversData.entrySet()) {
            String kierowca = entry.getKey();
            DriverData data = entry.getValue();

            BigDecimal totalBoltNetRaw     = data.getBoltNetRaw();
            BigDecimal totalBoltCashRaw    = data.getBoltCashRaw();
            BigDecimal totalBoltTipsRaw    = data.getBoltTipsRaw();
            BigDecimal totalBoltBonusRaw   = data.getBoltBonusOrCancelRaw();

            BigDecimal totalUberNetRaw     = data.getUberNetRaw();
            BigDecimal totalUberCashRaw    = data.getUberCashRaw();

            BigDecimal totalFreeNowNetRaw  = data.getFreeNowNetRaw();
            BigDecimal totalFreeNowCashRaw = data.getFreeNowCashRaw();

            // ● Surowe sumy zebrane z CSV (wyświetlane w debugowaniu)
            LOGGER.info(String.format(
                    "DEBUG [%s] surowe: BoltNet=%s, BoltCash=%s, BoltTips=%s, BoltBonus=%s | " +
                            "UberNet=%s, UberCash=%s | FreeNowNet=%s, FreeNowCash=%s",
                    kierowca,
                    totalBoltNetRaw, totalBoltCashRaw, totalBoltTipsRaw, totalBoltBonusRaw,
                    totalUberNetRaw, totalUberCashRaw,
                    totalFreeNowNetRaw, totalFreeNowCashRaw
            ));

            // 1) Łączne „netto” z każdej platformy
            BigDecimal totalNetRaw = totalBoltNetRaw
                    .add(totalUberNetRaw)
                    .add(totalFreeNowNetRaw);

            // 2) Łączne „cash” z każdej platformy
            BigDecimal totalCashRaw = totalBoltCashRaw
                    .add(totalUberCashRaw)
                    .add(totalFreeNowCashRaw);

            // 3) Sumujemy BoltTips + BoltBonus, potem odejmujemy 23% (mnożymy przez 0.77)
            BigDecimal sumBoltTipsBonus      = totalBoltTipsRaw.add(totalBoltBonusRaw);
            BigDecimal totalTipsBonusAfterTax = sumBoltTipsBonus.multiply(new BigDecimal("0.77"));

            // 4) Odejmujemy 8% VAT tylko od „netto” (mnożymy przez 0.92)
            BigDecimal totalNetAfterVat = totalNetRaw.multiply(new BigDecimal("0.92"));

            // 5) rawFinalNet = totalNetAfterVat + totalTipsBonusAfterTax – totalCashRaw
            BigDecimal rawFinalNet = totalNetAfterVat
                    .add(totalTipsBonusAfterTax)
                    .subtract(totalCashRaw);

            // 6) finalNet = rawFinalNet zaokrąglone do 2 miejsc (HALF_UP)
            BigDecimal finalNet = rawFinalNet.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 7) finalTips: Łączne „tips+bonus” po 23% podatku, zaokrąglone do 2 miejsc
            BigDecimal finalTips = totalTipsBonusAfterTax.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 8) cashInfo: Łączne płatności gotówką (surowo), zaokrąglone do 2 miejsc
            BigDecimal cashInfo = totalCashRaw.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 9) Odejmujemy 90 zł (50 zł opłata aplikacji + 40 zł ZUS)
            BigDecimal afterDeductions = finalNet.subtract(new BigDecimal("90.00"));

            // 10) Jeżeli kierowca należy do SPLIT_DRIVERS → dzielimy wynik na pół
            BigDecimal afterSplit;
            if (SPLIT_DRIVERS.contains(kierowca)) {
                afterSplit = afterDeductions.divide(BigDecimal.valueOf(2), 2, BigDecimal.ROUND_HALF_UP);
            } else {
                afterSplit = afterDeductions;
            }

            // 11) Dodajemy stały bonus od firmy (jeśli jest w mapie)
            BigDecimal companyBonus = COMPANY_BONUS_MAP.getOrDefault(kierowca, BigDecimal.ZERO);
            BigDecimal payout = afterSplit.add(companyBonus).setScale(2, BigDecimal.ROUND_HALF_UP);

            printer.printRecord(
                    kierowca,
                    finalNet,
                    finalTips,
                    cashInfo,
                    payout
            );
        }

        printer.flush();
    }
}