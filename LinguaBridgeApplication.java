package com.linguabridge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LinguaBridge - Spring Boot Application and Complete Backend Architecture
 */
@SpringBootApplication
public class LinguaBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(LinguaBridgeApplication.class, args);
    }
}

/* ==========================================================================
   1. HTTP CLIENT & CORS CONFIGURATION
   ========================================================================== */

@Configuration
class WebSecurityAndClientConfig {

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] origins = Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .toArray(String[]::new);

                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(8));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder().requestFactory(factory).build();
    }
}

/* ==========================================================================
   2. DOMAIN MODEL (MongoDB Document)
   ========================================================================== */

@Document(collection = "translations")
class TranslationRecord {

    @Id
    private String id;
    private String originalText;
    private String translatedText;
    private String sourceLanguage;
    private String targetLanguage;
    private Instant timestamp;
    private boolean favorite;

    public TranslationRecord() {
        this.timestamp = Instant.now();
        this.favorite = false;
    }

    public TranslationRecord(String originalText, String translatedText, String sourceLanguage, String targetLanguage) {
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.sourceLanguage = sourceLanguage;
        this.targetLanguage = targetLanguage;
        this.timestamp = Instant.now();
        this.favorite = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOriginalText() { return originalText; }
    public void setOriginalText(String originalText) { this.originalText = originalText; }

    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }

    public String getSourceLanguage() { return sourceLanguage; }
    public void setSourceLanguage(String sourceLanguage) { this.sourceLanguage = sourceLanguage; }

    public String getTargetLanguage() { return targetLanguage; }
    public void setTargetLanguage(String targetLanguage) { this.targetLanguage = targetLanguage; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
}

/* ==========================================================================
   3. DATA ACCESS LAYER (Spring Data Mongo Repository)
   ========================================================================== */

@Repository
interface TranslationRepository extends MongoRepository<TranslationRecord, String> {
    List<TranslationRecord> findAllByOrderByTimestampDesc();
    List<TranslationRecord> findByFavoriteTrueOrderByTimestampDesc();
    List<TranslationRecord> findByOriginalTextContainingIgnoreCaseOrTranslatedTextContainingIgnoreCaseOrderByTimestampDesc(
            String originalText, String translatedText);
}

/* ==========================================================================
   4. DATA TRANSFER OBJECTS (Records)
   ========================================================================== */

record TranslationRequest(
        @NotBlank(message = "Text to translate cannot be blank.")
        @Size(max = 2000, message = "Text size must not exceed 2000 characters.")
        String text,

        @NotBlank(message = "Source language is required.")
        String sourceLanguage,

        @NotBlank(message = "Target language is required.")
        String targetLanguage
) {}

record TranslationResponse(
        String id,
        String originalText,
        String translatedText,
        String sourceLanguage,
        String targetLanguage,
        Instant timestamp,
        boolean favorite,
        String detectedSourceLanguage
) {}

record LanguageResponse(
        String code,
        String name,
        String nativeName
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
class MyMemoryApiResponse {
    private ResponseData responseData;
    private int responseStatus;
    private String responseDetails;

    public ResponseData getResponseData() { return responseData; }
    public void setResponseData(ResponseData responseData) { this.responseData = responseData; }

    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }

    public String getResponseDetails() { return responseDetails; }
    public void setResponseDetails(String responseDetails) { this.responseDetails = responseDetails; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseData {
        private String translatedText;
        public String getTranslatedText() { return translatedText; }
        public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    }
}

/* ==========================================================================
   5. SERVICE LAYER & EXTERNAL API INTEGRATION
   ========================================================================== */

interface TranslationProvider {
    record ProviderResult(String translatedText, String detectedSourceLanguage) {}
    ProviderResult translate(String text, String sourceLanguage, String targetLanguage);
}

@Service
class MyMemoryTranslationProvider implements TranslationProvider {

    private static final Logger log = LoggerFactory.getLogger(MyMemoryTranslationProvider.class);
    private final RestClient restClient;

    @Value("${translation.provider.mymemory.url:https://api.mymemory.translated.net/get}")
    private String apiUrl;

    @Value("${translation.provider.mymemory.email:}")
    private String emailApiKey;

    public MyMemoryTranslationProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ProviderResult translate(String text, String sourceLanguage, String targetLanguage) {
        try {
            String src = (sourceLanguage == null || sourceLanguage.trim().isEmpty() || "autodetect".equalsIgnoreCase(sourceLanguage))
                    ? "autodetect"
                    : sourceLanguage.trim().toLowerCase();
            String tgt = targetLanguage.trim().toLowerCase();
            String langPair = src + "|" + tgt;

            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("q", text)
                    .queryParam("langpair", langPair);

            if (emailApiKey != null && !emailApiKey.isBlank()) {
                builder.queryParam("de", emailApiKey);
            }

            URI requestUri = builder.build().encode().toUri();
            log.info("Requesting MyMemory translation for langpair: {}", langPair);

            MyMemoryApiResponse response = restClient.get()
                    .uri(requestUri)
                    .retrieve()
                    .body(MyMemoryApiResponse.class);

            if (response == null) {
                throw new LinguaBridgeException("Empty response payload received from MyMemory API.");
            }

            if (response.getResponseStatus() != 200 && response.getResponseStatus() != 206) {
                String errorMsg = response.getResponseDetails() != null ? response.getResponseDetails() : "Quota limit reached or API error.";
                throw new LinguaBridgeException("MyMemory provider error: " + errorMsg);
            }

            if (response.getResponseData() == null || response.getResponseData().getTranslatedText() == null) {
                throw new LinguaBridgeException("Missing translated text payload in upstream response.");
            }

            String cleanedText = decodeHtmlEntities(response.getResponseData().getTranslatedText());
            String detectedSource = src.equals("autodetect") ? "detected" : src;

            return new ProviderResult(cleanedText, detectedSource);

        } catch (LinguaBridgeException le) {
            throw le;
        } catch (Exception e) {
            log.error("Failed to execute translation: {}", e.getMessage(), e);
            throw new LinguaBridgeException("Translation failed: " + e.getMessage(), e);
        }
    }

    private String decodeHtmlEntities(String input) {
        if (input == null) return "";
        return input.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}

@Service
class TranslationService {

    private final TranslationProvider translationProvider;
    private final TranslationRepository repository;

    private static final List<LanguageResponse> SUPPORTED_LANGUAGES = List.of(
            new LanguageResponse("autodetect", "Auto Detect", "Detect Language"),
            new LanguageResponse("en", "English", "English"),
            new LanguageResponse("mr", "Marathi", "मराठी"),
            new LanguageResponse("hi", "Hindi", "हिन्दी"),
            new LanguageResponse("es", "Spanish", "Español"),
            new LanguageResponse("fr", "French", "Français"),
            new LanguageResponse("de", "German", "Deutsch")
    );

    public TranslationService(TranslationProvider translationProvider, TranslationRepository repository) {
        this.translationProvider = translationProvider;
        this.repository = repository;
    }

    public List<LanguageResponse> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    public TranslationResponse translateAndPersist(TranslationRequest request) {
        TranslationProvider.ProviderResult result = translationProvider.translate(
                request.text(),
                request.sourceLanguage(),
                request.targetLanguage()
        );

        TranslationRecord record = new TranslationRecord(
                request.text().trim(),
                result.translatedText(),
                request.sourceLanguage(),
                request.targetLanguage()
        );

        TranslationRecord saved = repository.save(record);

        return new TranslationResponse(
                saved.getId(),
                saved.getOriginalText(),
                saved.getTranslatedText(),
                saved.getSourceLanguage(),
                saved.getTargetLanguage(),
                saved.getTimestamp(),
                saved.isFavorite(),
                result.detectedSourceLanguage()
        );
    }

    public List<TranslationResponse> getHistory(String searchQuery) {
        List<TranslationRecord> records;
        if (searchQuery != null && !searchQuery.isBlank()) {
            records = repository.findByOriginalTextContainingIgnoreCaseOrTranslatedTextContainingIgnoreCaseOrderByTimestampDesc(
                    searchQuery.trim(), searchQuery.trim());
        } else {
            records = repository.findAllByOrderByTimestampDesc();
        }
        return records.stream().map(this::toResponse).toList();
    }

    public List<TranslationResponse> getFavorites() {
        return repository.findByFavoriteTrueOrderByTimestampDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TranslationResponse toggleFavorite(String id) {
        TranslationRecord record = repository.findById(id)
                .orElseThrow(() -> new RecordNotFoundException("Translation not found with ID: " + id));

        record.setFavorite(!record.isFavorite());
        TranslationRecord updated = repository.save(record);
        return toResponse(updated);
    }

    public void deleteRecord(String id) {
        if (!repository.existsById(id)) {
            throw new RecordNotFoundException("Translation record not found with ID: " + id);
        }
        repository.deleteById(id);
    }

    public void clearAllHistory() {
        repository.deleteAll();
    }

    private TranslationResponse toResponse(TranslationRecord record) {
        return new TranslationResponse(
                record.getId(),
                record.getOriginalText(),
                record.getTranslatedText(),
                record.getSourceLanguage(),
                record.getTargetLanguage(),
                record.getTimestamp(),
                record.isFavorite(),
                record.getSourceLanguage()
        );
    }
}

/* ==========================================================================
   6. REST CONTROLLERS
   ========================================================================== */

@RestController
@RequestMapping("/api")
class TranslationController {

    private final TranslationService translationService;

    public TranslationController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @PostMapping("/translate")
    public ResponseEntity<TranslationResponse> translate(@Valid @RequestBody TranslationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(translationService.translateAndPersist(request));
    }

    @GetMapping("/languages")
    public ResponseEntity<List<LanguageResponse>> getLanguages() {
        return ResponseEntity.ok(translationService.getSupportedLanguages());
    }

    @GetMapping("/history")
    public ResponseEntity<List<TranslationResponse>> getHistory(@RequestParam(value = "search", required = false) String search) {
        return ResponseEntity.ok(translationService.getHistory(search));
    }

    @DeleteMapping("/history/{id}")
    public ResponseEntity<Map<String, String>> deleteHistoryRecord(@PathVariable String id) {
        translationService.deleteRecord(id);
        return ResponseEntity.ok(Map.of("message", "Translation deleted successfully", "id", id));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Map<String, String>> clearAllHistory() {
        translationService.clearAllHistory();
        return ResponseEntity.ok(Map.of("message", "All translation history wiped successfully"));
    }

    @GetMapping("/favorites")
    public ResponseEntity<List<TranslationResponse>> getFavorites() {
        return ResponseEntity.ok(translationService.getFavorites());
    }

    @PutMapping("/favorites/{id}")
    public ResponseEntity<TranslationResponse> toggleFavorite(@PathVariable String id) {
        return ResponseEntity.ok(translationService.toggleFavorite(id));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "application", "LinguaBridge Backend",
                "timestamp", Instant.now().toString(),
                "javaVersion", System.getProperty("java.version")
        ));
    }
}

/* ==========================================================================
   7. EXCEPTIONS & GLOBAL EXCEPTION HANDLER
   ========================================================================== */

class LinguaBridgeException extends RuntimeException {
    public LinguaBridgeException(String message) { super(message); }
    public LinguaBridgeException(String message, Throwable cause) { super(message, cause); }
}

class RecordNotFoundException extends RuntimeException {
    public RecordNotFoundException(String message) { super(message); }
}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError err : ex.getBindingResult().getFieldErrors()) {
            errors.put(err.getField(), err.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "timestamp", Instant.now(),
                "status", 400,
                "error", "Bad Request",
                "message", "Validation constraint violation",
                "details", errors
        ));
    }

    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(RecordNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", Instant.now(),
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(LinguaBridgeException.class)
    public ResponseEntity<Map<String, Object>> handleTranslationProviderError(LinguaBridgeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "timestamp", Instant.now(),
                "status", 502,
                "error", "Bad Gateway",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandledException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "timestamp", Instant.now(),
                "status", 500,
                "error", "Internal Server Error",
                "message", ex.getMessage() != null ? ex.getMessage() : "An unexpected server error occurred."
        ));
    }
}
