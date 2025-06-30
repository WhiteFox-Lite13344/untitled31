package org.example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import net.jcip.annotations.ThreadSafe;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// ========= Интерфейсы вынесены наружу ==========
interface DocumentService {
    Mono<CrptApi.DocumentResponse> createDocument(CrptApi.HonestMarkDocument document, String signature);
}

interface RateLimiter {
    <T> Mono<T> executeWithRateLimit(Supplier<Mono<T>> task);
}

interface HttpRequestExecutor extends AutoCloseable {
    Mono<CrptApi.DocumentResponse> execute(CrptApi.DocumentRequest request);
    @Override
    void close();
}

// =============== Основной класс =====================
@ThreadSafe
public final class CrptApi implements DocumentService, AutoCloseable {
    private final RateLimiter rateLimiter;
    private final HttpRequestExecutor requestExecutor;
    private final DocumentRequestFactory requestFactory;

    @Builder
    public CrptApi(@NonNull TimeUnit timeUnit,
                   int requestLimit,
                   @NonNull String authToken) {
        this.rateLimiter = new TokenBucketRateLimiter(timeUnit, requestLimit);
        this.requestExecutor = new ReactiveHttpExecutor(authToken);
        this.requestFactory = new DocumentRequestFactory();
    }

    @Override
    public Mono<DocumentResponse> createDocument(@NonNull HonestMarkDocument document,
                                                 @NonNull String signature) {
        return rateLimiter.executeWithRateLimit(() ->
                requestExecutor.execute(
                        requestFactory.createRequest(document, signature)
                )
        );
    }

    @Override
    public void close() {
        requestExecutor.close();
    }

    // =================== Domain Models ==========================

    @Getter
    @Builder
    public static class HonestMarkDocument {
        private final String productDocument;
        private final ProductGroup productGroup;
        private final DocumentFormat documentFormat;
        private final DocumentType type;
    }

    @Getter
    @Builder
    public static class DocumentRequest {
        private final String productDocument;
        private final String productGroup;
        private final DocumentFormat documentFormat;
        private final DocumentType type;
        private final String signature;
    }

    @Getter
    @Builder
    public static class DocumentResponse {
        private final String value;
        private final String errorCode;
        private final String errorMessage;
        private final String errorDescription;

        public boolean hasError() {
            return (errorCode != null && !errorCode.isEmpty())
                    || (errorMessage != null && !errorMessage.isEmpty());
        }
    }

    public enum ProductGroup {
        CLOTHES, SHOES, TOBACCO, PERFUMES, TIRES, ELECTRONICS, DAIRY
    }

    public enum DocumentFormat {
        MANUAL, CSV, XML
    }

    public enum DocumentType {
        LP_INTRODUCE_GOODS
    }

    // =================== Factory ==========================
    static class DocumentRequestFactory {
        DocumentRequest createRequest(HonestMarkDocument document, String signature) {
            validateDocument(document);
            return DocumentRequest.builder()
                    .productDocument(document.getProductDocument())
                    .productGroup(document.getProductGroup().toString())
                    .documentFormat(validateFormat(document.getDocumentFormat()))
                    .type(validateType(document.getType()))
                    .signature(signature)
                    .build();
        }

        private DocumentFormat validateFormat(DocumentFormat format) {
            if (format == null) throw new IllegalArgumentException("Document format is required");
            return format;
        }

        private DocumentType validateType(DocumentType type) {
            if (type == null) throw new IllegalArgumentException("Document type is required");
            return type;
        }

        private void validateDocument(HonestMarkDocument document) {
            if (document == null) throw new IllegalArgumentException("Document cannot be null");
        }
    }

    // =================== Rate Limiter (Strategy) ==========================
    @ThreadSafe
    static class TokenBucketRateLimiter implements RateLimiter {
        private final AtomicInteger tokens;
        private final int maxTokens;
        private final AtomicLong lastRefillTime;
        private final TimeUnit timeUnit;

        TokenBucketRateLimiter(TimeUnit timeUnit, int maxTokens) {
            this.timeUnit = timeUnit;
            this.maxTokens = maxTokens;
            this.tokens = new AtomicInteger(maxTokens);
            this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
        }

        @Override
        public <T> Mono<T> executeWithRateLimit(Supplier<Mono<T>> task) {
            return Mono.defer(() -> {
                refillTokensIfNeeded();
                if (tokens.decrementAndGet() < 0) {
                    return Mono.delay(computeDelay()).then(task.get());
                }
                return task.get();
            });
        }

        private void refillTokensIfNeeded() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime.get();
            if (elapsed >= timeUnit.toMillis(1)) {
                tokens.set(maxTokens);
                lastRefillTime.set(now);
            }
        }

        private Duration computeDelay() {
            long now = System.currentTimeMillis();
            long delay = timeUnit.toMillis(1) - (now - lastRefillTime.get());
            return Duration.ofMillis(Math.max(delay, 0));
        }
    }

    // =================== HTTP Adapter ==========================
    @ThreadSafe
    static class ReactiveHttpExecutor implements HttpRequestExecutor {
        private static final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
        private final HttpClient httpClient;
        private final String authToken;
        private final ObjectMapper objectMapper;

        ReactiveHttpExecutor(String authToken) {
            this.httpClient = HttpClient.newHttpClient();
            this.authToken = "Bearer " + authToken;
            this.objectMapper = new ObjectMapper();
        }

        @Override
        public Mono<DocumentResponse> execute(DocumentRequest request) {
            return Mono.fromCallable(() -> sendSyncRequest(request))
                    .map(this::parseResponse)
                    .onErrorMap(ApiException::new);
        }

        private HttpResponse<String> sendSyncRequest(DocumentRequest request) throws IOException, InterruptedException {
            HttpRequest httpRequest = buildHttpRequest(request);
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest buildHttpRequest(DocumentRequest request) throws JsonProcessingException {
            String body = objectMapper.writeValueAsString(request);
            return HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", authToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        }

        private DocumentResponse parseResponse(HttpResponse<String> response) {
            int status = response.statusCode();
            String body = response.body();
            if (status != 200) {
                throw new ApiException("Request failed with status: " + status + ", body: " + body);
            }
            try {
                DocumentResponse resp = objectMapper.readValue(body, DocumentResponse.class);
                if (resp.hasError()) {
                    throw new ApiException(resp.getErrorMessage());
                }
                return resp;
            } catch (JsonProcessingException e) {
                throw new ApiException("Failed to parse response", e);
            }
        }

        @Override
        public void close() {
            // nothing to close for now
        }
    }

    // =================== Exceptions ==========================
    static class ApiException extends RuntimeException {
        public ApiException(String message) { super(message); }
        public ApiException(String message, Throwable cause) { super(message, cause); }
        public ApiException(Throwable cause) { super(cause); }
    }
}