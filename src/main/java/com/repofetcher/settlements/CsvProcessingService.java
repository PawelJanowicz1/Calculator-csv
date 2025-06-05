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

/**
 * Serwis do parsowania plików CSV (Bolt / Uber / FreeNow), obliczania wynagrodzeń
 * i generowania raportu CSV „Do wypłaty” z uwzględnieniem:
 *  - 8% VAT od netto,
 *  - 23% podatku od napiwków + bonusów (Bolt),
 *  - odjęcia płatności gotówką,
 *  - odjęcia stałych kosztów (50 zł aplikacja + 40 zł ZUS = 90 zł),
 *  - podziału 50/50 dla wybranych kierowców (SPLIT_DRIVERS),
 *  - odliczenia stałego kosztu wynajmu auta (700 zł lub 1050 zł) dla wybranych kierowców (RENTAL_FEE_MAP),
 *  - dodania bonusu od firmy (COMPANY_BONUS_MAP).
 */
@Service
public class CsvProcessingService {

    private static final Logger LOGGER = Logger.getLogger(CsvProcessingService.class.getName());

    // ───── Nagłówki do rozpoznania kolumn „netto”, „napiwki”, „gotówka” i „bonus/anulowanie” ─────
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

    // ───── Kolumny z nazwą kierowcy ─────
    private static final String BOLT_DRIVER_NAME_COL    = "kierowca";
    private static final String UBER_DRIVER_FNAME_COL   = "imię kierowcy";
    private static final String UBER_DRIVER_LNAME_COL   = "nazwisko kierowcy";
    private static final String FREENOW_DRIVER_NAME_COL = "kierowca";

    // ───── Kierowcy, których wypłatę dzielimy 50/50 ─────
    // klucz = „imię + spacja + nazwisko” w lowercase
    private static final Set<String> SPLIT_DRIVERS = Set.of(
            "michał fopke",
            "andrzej netkowski",
            "maciej dziórawiec",
            "maciej opara",
            "kinga mikołajczyk",
            "przemysław tartanus"
            // (w razie potrzeby dopisz tutaj kolejnych)
    );

    // ───── Stałe bonusy od firmy ─────
    // klucz = „imię + spacja + nazwisko” w lowercase
    private static final Map<String, BigDecimal> COMPANY_BONUS_MAP;

    // ───── Stałe opłaty za wynajem auta ─────
    // klucz = „imię + spacja + nazwisko” w lowercase
    private static final Map<String, BigDecimal> RENTAL_FEE_MAP;

    static {
        // ► COMPANY_BONUS_MAP: kto ile dostał bonusu
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

        // ► RENTAL_FEE_MAP: kto ile płaci za wynajem auta (700, 1050 lub 0)
        Map<String, BigDecimal> rentalMap = new HashMap<>();
        // Fopke Michał → 0 zł
        rentalMap.put("michał fopke",            BigDecimal.ZERO);
        rentalMap.put("fopke michał",            BigDecimal.ZERO);
        // Opara Maciej → 0 zł
        rentalMap.put("maciej opara",            BigDecimal.ZERO);
        rentalMap.put("opara maciej",            BigDecimal.ZERO);
        // Olszewski Piotr → 700 zł
        rentalMap.put("piotr marek olszowski",   new BigDecimal("700.00"));
        rentalMap.put("piotr olszowski",         new BigDecimal("700.00"));
        // Dziórawiec Maciej → 0 zł
        rentalMap.put("maciej dziórawiec",       BigDecimal.ZERO);
        rentalMap.put("dziórawiec maciej",       BigDecimal.ZERO);
        // Śliwiński Janusz → 700 zł
        rentalMap.put("janusz śliwiński",        new BigDecimal("700.00"));
        rentalMap.put("śliwiński janusz",        new BigDecimal("700.00"));
        // Mikołajczyk Kinga → 0 zł
        rentalMap.put("kinga mikołajczyk",       BigDecimal.ZERO);
        rentalMap.put("mikołajczyk kinga",       BigDecimal.ZERO);
        // Tartanus Przemysław → 0 zł
        rentalMap.put("przemysław tartanus",     BigDecimal.ZERO);
        rentalMap.put("tartanus przemysław",     BigDecimal.ZERO);
        // Lukianenko Artem → 700 zł
        rentalMap.put("artem lukianenko",        new BigDecimal("700.00"));
        rentalMap.put("lukianenko artem",        new BigDecimal("700.00"));
        // Netkowski Andrzej → 0 zł
        rentalMap.put("andrzej netkowski",       BigDecimal.ZERO);
        rentalMap.put("netkowski andrzej",       BigDecimal.ZERO);
        // Plenikowski Paweł → 1050 zł
        rentalMap.put("paweł plenikowski",       new BigDecimal("1050.00"));
        rentalMap.put("plenikowski paweł",       new BigDecimal("1050.00"));
        // Zygmunt Żmuda Trzebiatowski → 0 zł (jeśli w ogóle pojawi się w płatnościach)
        rentalMap.put("zygmunt zmuda trzebiatowski", BigDecimal.ZERO);
        rentalMap.put("zmuda trzebiatowski zygmunt", BigDecimal.ZERO);

        RENTAL_FEE_MAP = Collections.unmodifiableMap(rentalMap);
    }


    /**
     * Przetwarza listę plików CSV (Bolt, FreeNow, Uber) i zwraca mapę:
     *   key = „imię + spacja + nazwisko (lowercase)”, value = DriverData z surowymi wartościami raw.
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
                    LOGGER.warning("Plik „" + path.getFileName() + "” ma puste nagłówki. Pomijam.");
                    continue;
                }

                // Normalizujemy wszystkie klucze nagłówków (trim, lowercase)
                Map<String, Integer> normalizedHeaderMap = headerMap.entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .collect(Collectors.toMap(
                                e -> e.getKey().trim().toLowerCase(),
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        ));

                for (CSVRecord record : parser) {
                    // Wyciągamy „imię + nazwisko” w lowercase
                    String driverIdentifier = extractDriverIdentifier(record, normalizedHeaderMap);
                    if (driverIdentifier == null) {
                        LOGGER.warning("Nie udało się odczytać kierowcy w wierszu #"
                                + record.getRecordNumber() + " pliku " + path.getFileName() + ". Pomijam.");
                        continue;
                    }

                    // Pobieramy / tworzymy obiekt DriverData
                    DriverData currentDriverData = allDriversData
                            .computeIfAbsent(driverIdentifier, DriverData::new);

                    // Sprawdzamy, z której platformy jest bieżący wiersz (po nagłówkach):
                    if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_FREENOW)) {
                        // ► FreeNow: dodajemy tylko net i cash, pomijamy tips/bonus (FreeNow ich nie ma w tym raporcie)
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
                        // ► Uber: dodajemy net i cash (wartość cash bierzemy v.abs()), pomijamy tips/bonus
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
                        // ► Bolt: dodajemy net, tips, cash i bonus/anulowanie
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
                LOGGER.log(Level.SEVERE, "Błąd przetwarzania pliku: " + path.getFileName(), e);
            }
        }

        return allDriversData;
    }

    /**
     * Sprawdza, czy w znormalizowanej mapie nagłówków jest którakolwiek z listy targetHeaders.
     */
    private boolean hasAnyHeader(Map<String, Integer> normalizedHeaderMap, List<String> targetHeaders) {
        return targetHeaders.stream()
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(normalizedHeaderMap::containsKey);
    }

    private boolean hasAnyHeader(Map<String, Integer> normalizedHeaderMap, String targetHeader) {
        return normalizedHeaderMap.containsKey(targetHeader.trim().toLowerCase());
    }

    /**
     * Wyciąga identyfikator kierowcy (imię + spacja + nazwisko w lowercase).
     *  – Jeśli są kolumny „Imię kierowcy” + „Nazwisko kierowcy” (Uber) → łączymy;
     *  – W przeciwnym razie sprawdzamy kolumnę „Kierowca” (Bolt/FreeNow).
     * Zwracamy null, jeśli nie uda się odczytać imienia lub nazwiska.
     */
    private String extractDriverIdentifier(CSVRecord record, Map<String, Integer> headerMap) {
        String driverName = null;

        // Uber: „Imię kierowcy” + „Nazwisko kierowcy”
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

        return (driverName == null)
                ? null
                : driverName.trim().toLowerCase();
    }

    /**
     * Parsuje CSV-owy wiersz w czterech typach nagłówków:
     *  – netEarningsHeaders  (netto),
     *  – tipsHeaders         (napiwki),
     *  – cashHeaders         (gotówka),
     *  – bonusCancelHeaders  (bonusy / anulowania).
     * Dla każdej kolumny staramy się wyciągnąć tekst, skonwertować go na BigDecimal
     * (metoda parseDecimal) i przekazać do odpowiedniej metody w DriverData.
     */
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

    /**
     * Dla każdej nazwy kolumny w targetHeaders próbuje odnaleźć jej indeks w CSVRecord,
     * pobrać tekst (np. "1 234,56 zł"), przekonwertować go na BigDecimal przez parseDecimal(…),
     * a następnie wywołać valueConsumer.accept(driverData, taWartość).
     */
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
                    } catch (NumberFormatException e) {
                        LOGGER.warning("Niepoprawna liczba \"" + rawValue
                                + "\" w kolumnie \"" + targetHeader
                                + "\". Pomijam wartość.");
                    }
                }
            }
        }
    }

    /**
     * Konwertuje ciąg typu "1 234,56 zł" lub "-67,85" na BigDecimal(…), lub zwraca 0 w przypadku braku danych.
     */
    private BigDecimal parseDecimal(String value) throws NumberFormatException {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Zamień przecinki na kropki i usuń znaki inne niż cyfry, kropka, minus
        String standardized = value.replace(',', '.').replaceAll("[^0-9.\\-]", "");
        if (standardized.isEmpty() || standardized.equals("-") || standardized.equals(".")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(standardized);
    }

    /**
     * Zapisuje wyniki do CSV w następującej kolejności:
     *
     *  1) totalNetRaw       = suma wszystkich „netto” z platform (Bolt + Uber + FreeNow)
     *  2) totalCashRaw      = suma wszystkich „cash” z platform
     *  3) sumBoltTipsBonus  = totalBoltTipsRaw + totalBoltBonusRaw
     *  4) totalTipsBonusAfterTax = sumBoltTipsBonus * 0.77  (23% podatek od napiwków/bonusów)
     *  5) totalNetAfterVat  = totalNetRaw * 0.92  (8% VAT od netto)
     *  6) rawFinalNet       = totalNetAfterVat + totalTipsBonusAfterTax – totalCashRaw
     *  7) finalNet          = rawFinalNet zaokrąglone do 2 miejsc (HALF_UP)
     *  8) finalTips         = totalTipsBonusAfterTax zaokrąglone do 2 miejsc
     *  9) cashInfo          = totalCashRaw zaokrąglone do 2 miejsc
     * 10) afterDeductions   = finalNet – 90 (50 zł aplikacja + 40 zł ZUS)
     * 11) afterSplit        = (jeśli kierowca ∈ SPLIT_DRIVERS) → afterDeductions / 2; w przeciwnym razie = afterDeductions
     * 12) afterRental       = afterSplit – RENTAL_FEE_MAP.getOrDefault(kierowca, 0)
     * 13) payout            = afterRental + COMPANY_BONUS_MAP.getOrDefault(kierowca, 0), zaokrąglone do 2 miejsc
     *
     * W kolumnach CSV wypisujemy:
     *   - „Łączne zarobki netto (po VAT 8% i opodatkowaniu napiwków)” (kolumna finalNet),
     *   - „Łączne napiwki+bonusy (po 23% podatku)” (kolumna finalTips),
     *   - „Łączne płatności gotówką” (kolumna cashInfo),
     *   - „Do wypłaty (po wszystkich odliczeniach)” (kolumna payout).
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
                        "Do wypłaty (po wszystkich odliczeniach)"
                )
                .build();

        CSVPrinter printer = new CSVPrinter(writer, csvFormat);

        for (var entry : allDriversData.entrySet()) {
            String kierowca = entry.getKey();
            DriverData data = entry.getValue();

            // ─── Pobranie „surowych” sum z DriverData ───
            BigDecimal totalBoltNetRaw     = data.getBoltNetRaw();
            BigDecimal totalBoltCashRaw    = data.getBoltCashRaw();
            BigDecimal totalBoltTipsRaw    = data.getBoltTipsRaw();
            BigDecimal totalBoltBonusRaw   = data.getBoltBonusOrCancelRaw();

            BigDecimal totalUberNetRaw     = data.getUberNetRaw();
            BigDecimal totalUberCashRaw    = data.getUberCashRaw();

            BigDecimal totalFreeNowNetRaw  = data.getFreeNowNetRaw();
            BigDecimal totalFreeNowCashRaw = data.getFreeNowCashRaw();

            // ─── (debug) wyświetlenie surowych wartości ───
            LOGGER.info(String.format(
                    "DEBUG [%s] surowe: BoltNet=%s, BoltCash=%s, BoltTips=%s, BoltBonus=%s | " +
                            "UberNet=%s, UberCash=%s | FreeNowNet=%s, FreeNowCash=%s",
                    kierowca,
                    totalBoltNetRaw, totalBoltCashRaw, totalBoltTipsRaw, totalBoltBonusRaw,
                    totalUberNetRaw, totalUberCashRaw,
                    totalFreeNowNetRaw, totalFreeNowCashRaw
            ));

            // 1) Łączne „netto” (bez VAT) z każdej platformy
            BigDecimal totalNetRaw = totalBoltNetRaw
                    .add(totalUberNetRaw)
                    .add(totalFreeNowNetRaw);

            // 2) Łączne „cash” (bez VAT)
            BigDecimal totalCashRaw = totalBoltCashRaw
                    .add(totalUberCashRaw)
                    .add(totalFreeNowCashRaw);

            // 3) Sumujemy BoltTips + BoltBonus, potem odejmujemy 23% (mnożymy przez 0.77)
            BigDecimal sumBoltTipsBonus      = totalBoltTipsRaw.add(totalBoltBonusRaw);
            BigDecimal totalTipsBonusAfterTax = sumBoltTipsBonus.multiply(new BigDecimal("0.77"));

            // 4) Odejmujemy 8% VAT od netto (mnożymy przez 0.92)
            BigDecimal totalNetAfterVat = totalNetRaw.multiply(new BigDecimal("0.92"));

            // 5) rawFinalNet = totalNetAfterVat + totalTipsBonusAfterTax – totalCashRaw
            BigDecimal rawFinalNet = totalNetAfterVat
                    .add(totalTipsBonusAfterTax)
                    .subtract(totalCashRaw);

            // 6) finalNet (po opodatkowaniu i odjęciu gotówki), zaokrąglone do 2 miejsc
            BigDecimal finalNet = rawFinalNet.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 7) finalTips: Łączne „napiwki+bonus” po 23% podatku, zaokrąglone do 2 miejsc
            BigDecimal finalTips = totalTipsBonusAfterTax.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 8) cashInfo: Łączne płatności gotówką (surowo), zaokrąglone do 2 miejsc
            BigDecimal cashInfo = totalCashRaw.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 9) Odejmujemy stałe 90 zł (50 zł aplikacja + 40 zł ZUS):
            BigDecimal afterDeductions = finalNet.subtract(new BigDecimal("90.00"));

            // 10) Jeżeli kierowca należy do SPLIT_DRIVERS, dzielimy wynik na pół:
            BigDecimal afterSplit;
            if (SPLIT_DRIVERS.contains(kierowca)) {
                afterSplit = afterDeductions.divide(BigDecimal.valueOf(2), 2, BigDecimal.ROUND_HALF_UP);
            } else {
                afterSplit = afterDeductions;
            }

            // 11) Odejmujemy opłatę za wynajem auta (700 lub 1050 zł, jeśli jest w mapie):
            BigDecimal rentalFee = RENTAL_FEE_MAP.getOrDefault(kierowca, BigDecimal.ZERO);
            BigDecimal afterRental = afterSplit.subtract(rentalFee);

            // 12) Dodajemy bonus od firmy (jeśli istnieje w COMPANY_BONUS_MAP):
            BigDecimal companyBonus = COMPANY_BONUS_MAP.getOrDefault(kierowca, BigDecimal.ZERO);
            BigDecimal payout = afterRental
                    .add(companyBonus)
                    .setScale(2, BigDecimal.ROUND_HALF_UP);

            // 13) Zapisujemy wiersz do CSV:
            printer.printRecord(
                    kierowca,
                    finalNet,    // „Łączne zarobki netto (po VAT i opodatkowaniu napiwków)”
                    finalTips,   // „Łączne napiwki+bonusy (po 23% podatku)”
                    cashInfo,    // „Łączne płatności gotówką”
                    payout       // „Do wypłaty (po wszystkich odliczeniach, w tym wynajem auta)”
            );
        }

        printer.flush();
    }
}