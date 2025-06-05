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

/**
 * Serwis do parsowania plików CSV (Bolt / Uber / FreeNow), obliczania wynagrodzeń
 * i generowania raportu CSV „Do wypłaty” z uwzględnieniem:
 *  - 8% VAT od netto,
 *  - 23% podatku od napiwków + bonusów (Bolt),
 *  - odjęcia płatności gotówką,
 *  - odjęcia stałych kosztów (50 zł aplikacja + 40 zł ZUS = 90 zł),
 *  - podziału 50/50 dla wybranych kierowców (SPLIT_DRIVERS),
 *  - odliczenia stałego kosztu wynajmu auta (RENTAL_FEE_MAP),
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
    // klucz = „imię + spacja + nazwisko” po normalizacji (lowercase, bez diakrytyków, bez środkowych słów)
    private static final Set<String> SPLIT_DRIVERS;
    // ───── Stałe bonusy od firmy ─────
    private static final Map<String, BigDecimal> COMPANY_BONUS_MAP;
    // ───── Stałe opłaty za wynajem auta ─────
    private static final Map<String, BigDecimal> RENTAL_FEE_MAP;

    static {
        // ► Przygotowujemy mapę bonusów – kluczujemy już po „znormalizowanym” identyfikatorze
        Map<String, BigDecimal> bonusMap = new HashMap<>();

        // Jeśli wpisujemy „michał fopke” czy „fopke michał”, to normalizowany klucz będzie „michal fopke”
        bonusMap.put(normalizeNameKey("michał fopke"),      new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("fopke michał"),      new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("maciej opara"),      new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("opara maciej"),      new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("maciej dziórawiec"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("dziórawiec maciej"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("kinga mikołajczyk"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("mikołajczyk kinga"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("andrzej netkowski"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("netkowski andrzej"), new BigDecimal("125.00"));
        bonusMap.put(normalizeNameKey("piotr marek olszowski"), new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("piotr olszowski"),       new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("janusz śliwiński"),      new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("śliwiński janusz"),      new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("artem lukianenko"),      new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("lukianenko artem"),      new BigDecimal("100.00"));
        bonusMap.put(normalizeNameKey("paweł plenikowski"),     new BigDecimal("0.00"));
        bonusMap.put(normalizeNameKey("plenikowski paweł"),     new BigDecimal("0.00"));

        COMPANY_BONUS_MAP = Collections.unmodifiableMap(bonusMap);

        // ► Przygotowujemy mapę opłat za wynajem auta (jeśli mamy taką potrzebę):
        Map<String, BigDecimal> rentalMap = new HashMap<>();

        // Fopke Michał → 0 zł
        rentalMap.put(normalizeNameKey("michał fopke"),          BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("fopke michał"),          BigDecimal.ZERO);

        // Opara Maciej → 0 zł
        rentalMap.put(normalizeNameKey("maciej opara"),          BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("opara maciej"),          BigDecimal.ZERO);

        // Olszewski Piotr → 700 zł
        rentalMap.put(normalizeNameKey("piotr marek olszowski"), new BigDecimal("700.00"));
        rentalMap.put(normalizeNameKey("piotr olszowski"),       new BigDecimal("700.00"));

        // Dziórawiec Maciej → 0 zł
        rentalMap.put(normalizeNameKey("maciej dziórawiec"),     BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("dziórawiec maciej"),     BigDecimal.ZERO);

        // Śliwiński Janusz → 700 zł
        rentalMap.put(normalizeNameKey("janusz śliwiński"),      new BigDecimal("700.00"));
        rentalMap.put(normalizeNameKey("śliwiński janusz"),      new BigDecimal("700.00"));

        // Mikołajczyk Kinga → 0 zł
        rentalMap.put(normalizeNameKey("kinga mikołajczyk"),     BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("mikołajczyk kinga"),     BigDecimal.ZERO);

        // Tartanus Przemysław → 0 zł
        rentalMap.put(normalizeNameKey("przemysław tartanus"),   BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("tartanus przemysław"),   BigDecimal.ZERO);

        // Lukianenko Artem → 700 zł
        rentalMap.put(normalizeNameKey("artem lukianenko"),      new BigDecimal("700.00"));
        rentalMap.put(normalizeNameKey("lukianenko artem"),      new BigDecimal("700.00"));

        // Netkowski Andrzej → 0 zł
        rentalMap.put(normalizeNameKey("andrzej netkowski"),     BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("netkowski andrzej"),     BigDecimal.ZERO);

        // Plenikowski Paweł → 1050 zł
        rentalMap.put(normalizeNameKey("paweł plenikowski"),     new BigDecimal("1050.00"));
        rentalMap.put(normalizeNameKey("plenikowski paweł"),     new BigDecimal("1050.00"));

        // Zygmunt Żmuda Trzebiatowski → 0 zł (jeśli się pojawi)
        rentalMap.put(normalizeNameKey("zygmunt żmuda trzebiatowski"), BigDecimal.ZERO);
        rentalMap.put(normalizeNameKey("zmuda trzebiatowski zygmunt"), BigDecimal.ZERO);

        RENTAL_FEE_MAP = Collections.unmodifiableMap(rentalMap);

        // ► Przygotowujemy listę kierowców, którzy dzielą 50/50 – podajemy już znormalizowane klucze
        Set<String> splitSet = new HashSet<>();
        splitSet.add(normalizeNameKey("michał fopke"));
        splitSet.add(normalizeNameKey("andrzej netkowski"));
        splitSet.add(normalizeNameKey("maciej dziórawiec"));
        splitSet.add(normalizeNameKey("maciej opara"));
        splitSet.add(normalizeNameKey("kinga mikołajczyk"));
        splitSet.add(normalizeNameKey("przemysław tartanus"));
        // (w razie potrzeby dopisz tutaj kolejnych)
        SPLIT_DRIVERS = Collections.unmodifiableSet(splitSet);
    }

    /**
     * Przetwarza pliki CSV (Bolt, FreeNow, Uber) i zwraca mapę identyfikator → DriverData,
     * gdzie **identyfikator** to już „znormalizowane” imię + nazwisko.
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

                // Normalizujemy klucze nagłówków (trim, lowercase)
                Map<String, Integer> normalizedHeaderMap = headerMap.entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .collect(Collectors.toMap(
                                e -> e.getKey().trim().toLowerCase(),
                                Map.Entry::getValue,
                                (v1, v2) -> v1
                        ));

                for (CSVRecord record : parser) {
                    // Wyciągamy „imię + nazwisko” i jednocześnie normalizujemy
                    String driverIdentifier = extractDriverIdentifier(record, normalizedHeaderMap);
                    if (driverIdentifier == null) {
                        LOGGER.warning(
                                "Nie udało się odczytać kierowcy w rekordzie #"
                                        + record.getRecordNumber() + " pliku " + path.getFileName()
                                        + ". Pomijam."
                        );
                        continue;
                    }

                    // Pobieramy / tworzymy obiekt DriverData dla tego klucza
                    DriverData currentDriverData = allDriversData
                            .computeIfAbsent(driverIdentifier, DriverData::new);

                    // W zależności od nagłówków – dodajemy do surowych sum (netto/tips/cash/bonus)
                    if (hasAnyHeader(normalizedHeaderMap, NET_EARNINGS_HEADERS_FREENOW)) {
                        // ► FreeNow: tylko net i cash (FreeNow nie ma kolumn tips/bonus w tym raporcie)
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
                        // ► Uber: dodajemy net oraz cash (wartość cash robimy v.abs()), bez tips/bonus
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
     * Wyciąga identyfikator kierowcy („imię + nazwisko”) i normalizuje go:
     *  – Łączy „Imię kierowcy” + „Nazwisko kierowcy” (Uber) lub kolumnę „Kierowca” (Bolt/FreeNow).
     *  – Zamienia na lowercase.
     *  – Usuwa znaki diakrytyczne ( np. „ś” → „s”, „ł” → „l” ).
     *  – Dzieli ciąg po białych znakach i bierze **pierwszy** i **ostatni** token, łącząc je spacją.
     *    Dzięki temu:
     *      „piotr marek olszowski” → „piotr olszowski”
     *      „piotr olszowski”        → „piotr olszowski”
     *      „paweł giecold”          → „pawel giecold”
     *      „paweł giecołd”          → „pawel giecold”
     *  – Jeśli nie uda się odczytać (brak imienia lub nazwiska), zwraca null.
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

        if (driverName == null) {
            return null;
        }
        // zwracamy już „znormalizowaną” wersję klucza
        return normalizeNameKey(driverName);
    }

    /**
     * Pomocnicza metoda do parsowania poszczególnych kolumn (netto/tips/cash/bonus).
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
     * pobrać tekst (np. "1 234,56 zł"), przekonwertować na BigDecimal przez parseDecimal(…),
     * a następnie wywołać valueConsumer.accept(driverData, wartość).
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
     * Konwertuje ciąg typu "1 234,56 zł" lub "-67,85" na BigDecimal, or returning 0 if empty/invalid.
     */
    private BigDecimal parseDecimal(String value) throws NumberFormatException {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Zamieniamy przecinki na kropki i usuwamy znaki inne niż cyfry, kropka i minus
        String standardized = value
                .replace(',', '.')
                .replaceAll("[^0-9.\\-]", "");
        if (standardized.isEmpty() || standardized.equals("-") || standardized.equals(".")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(standardized);
    }

    /**
     * Normalizuje dowolne „Imię Nazwisko” do postaci klucza:
     *  - zmiana na lowercase,
     *  - usunięcie wszystkich diakrytyków („ś” → „s”, „ł” → „l” itd.),
     *  - rozdzielenie po białych znakach i wzięcie **pierwszego** i **ostatniego** słowa,
     *    połączenie ich spacją.
     *  - dzięki temu: "Piotr Marek Olszowski" → "piotr olszowski",
     *    a "piotr olszowski" → "piotr olszowski".
     */
    private static String normalizeNameKey(String rawName) {
        // 1) usuń nadmiarowe spacje, zmień na lowercase
        String lower = rawName.trim().toLowerCase();

        // 2) usuń wszystkie diakrytyki (Normalizer + regex)
        String noDiacritics = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");  // usuwa wszystkie znaki „mark” (diakrytyki)

        // 3) rozdziel po białych znakach i weź pierwszy + ostatni token
        String[] parts = noDiacritics.split("\\s+");
        if (parts.length >= 2) {
            return parts[0] + " " + parts[parts.length - 1];
        } else {
            // gdyby było samo „Imię” lub dziwnie tylko jeden wyraz – zwracamy całość
            return noDiacritics;
        }
    }

    /**
     * Zapisuje wyniki do CSV. Kolejność obliczeń:
     *  1) totalNetRaw       = suma wszystkich „netto” (Bolt + Uber + FreeNow)
     *  2) totalCashRaw      = suma wszystkich „cash” (Bolt + Uber + FreeNow)
     *  3) sumBoltTipsBonus  = totalBoltTipsRaw + totalBoltBonusRaw
     *  4) totalTipsBonusAfterTax = sumBoltTipsBonus * 0.77  (23% podatek od napiwków/bonusów)
     *  5) totalNetAfterVat  = totalNetRaw * 0.92  (8% VAT od netto)
     *  6) rawFinalNet       = totalNetAfterVat + totalTipsBonusAfterTax – totalCashRaw
     *  7) finalNet          = rawFinalNet zaokrąglone do 2 miejsc (HALF_UP)
     *  8) finalTips         = totalTipsBonusAfterTax zaokrąglone do 2 miejsc
     *  9) cashInfo          = totalCashRaw zaokrąglone do 2 miejsc
     * 10) afterDeductions   = finalNet – 90 (50 zł opłata aplikacji + 40 zł ZUS)
     * 11) afterSplit        = (jeśli kierowca ∈ SPLIT_DRIVERS) → afterDeductions / 2; w innym razie = afterDeductions
     * 12) afterRental       = afterSplit – RENTAL_FEE_MAP.getOrDefault(kierowca, 0)
     * 13) payout            = afterRental + COMPANY_BONUS_MAP.getOrDefault(kierowca, 0),
     *                        zaokrąglone do 2 miejsc (HALF_UP)
     *
     * W kolumnach CSV wypisujemy:
     *   - „Łączne zarobki netto (po VAT 8% i opodatkowaniu napiwków)” (finalNet),
     *   - „Łączne napiwki+bonusy (po 23% podatku)” (finalTips),
     *   - „Łączne płatności gotówką” (cashInfo),
     *   - „Do wypłaty (po wszystkich odliczeniach, w tym wynajem auta)” (payout).
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
            String kierowca = entry.getKey();// to jest już „znormalizowany” identyfikator
            if (kierowca.equals("pawel giecold")) {
                continue;
            }
            if (kierowca.equals("paweł giecold")) {
                continue;
            }
            if (kierowca.equals("paweł giecołd")) {
                continue;
            }
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

            // 6) finalNet = rawFinalNet zaokrąglone do 2 miejsc (HALF_UP)
            BigDecimal finalNet = rawFinalNet.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 7) finalTips: Łączne „tips+bonus” po 23% podatku, zaokrąglone do 2 miejsc
            BigDecimal finalTips = totalTipsBonusAfterTax.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 8) cashInfo: Łączne płatności gotówką (surowo), zaokrąglone do 2 miejsc
            BigDecimal cashInfo = totalCashRaw.setScale(2, BigDecimal.ROUND_HALF_UP);

            // 9) Odejmujemy stałe 90 zł (50 zł aplikacja + 40 zł ZUS)
            BigDecimal afterDeductions = finalNet.subtract(new BigDecimal("90.00"));

            // 10) Jeżeli kierowca należy do SPLIT_DRIVERS, dzielimy wynik na pół
            BigDecimal afterSplit;
            if (SPLIT_DRIVERS.contains(kierowca)) {
                afterSplit = afterDeductions.divide(BigDecimal.valueOf(2), 2, BigDecimal.ROUND_HALF_UP);
            } else {
                afterSplit = afterDeductions;
            }

            // 11) Odejmujemy opłatę za wynajem auta (700 lub 1050 zł, jeśli istnieje w mapie)
            BigDecimal rentalFee = RENTAL_FEE_MAP.getOrDefault(kierowca, BigDecimal.ZERO);
            BigDecimal afterRental = afterSplit.subtract(rentalFee);

            // 12) Dodajemy bonus od firmy (jeśli istnieje w COMPANY_BONUS_MAP)
            BigDecimal companyBonus = COMPANY_BONUS_MAP.getOrDefault(kierowca, BigDecimal.ZERO);
            BigDecimal payout = afterRental
                    .add(companyBonus)
                    .setScale(2, BigDecimal.ROUND_HALF_UP);

            // 13) Zapisujemy wiersz do CSV:
            printer.printRecord(
                    kierowca,      // znormalizowany klucz (bez diakrytyków, tylko pierwsze + ostatnie słowo)
                    finalNet,      // „Łączne zarobki netto (po VAT i opodatkowaniu napiwków)”
                    finalTips,     // „Łączne napiwki+bonusy (po 23% podatku)”
                    cashInfo,      // „Łączne płatności gotówką”
                    payout         // „Do wypłaty (po wszystkich odliczeniach, w tym wynajem auta)”
            );
        }

        printer.flush();
    }
}