package com.morii.backend.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class DeepSeekClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    public DeepSeekClient(
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:deepseek-chat}") String model,
            @Value("${spring.ai.openai.chat.options.max-tokens:1200}") int maxTokens,
            @Value("${spring.ai.openai.chat.options.temperature:0.7}") double temperature
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (!this.apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey);
        }
        this.restClient = builder.build();
    }

    public String model() {
        return model;
    }

    @SuppressWarnings("unchecked")
    public AiResult chat(String systemPrompt, String userPrompt) {
        long started = System.nanoTime();
        log.info("DeepSeek call start: mode=normal model={} baseUrl={} userPromptChars={}", model, baseUrl, userPrompt == null ? 0 : userPrompt.length());
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        String content = "";
        if (response != null) {
            Object choicesValue = response.get("choices");
            if (choicesValue instanceof List<?> choices && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> choice) {
                Object messageValue = choice.get("message");
                if (messageValue instanceof Map<?, ?> message) {
                    Object contentValue = message.get("content");
                    content = contentValue == null ? "" : String.valueOf(contentValue);
                }
            }
        }
        log.info(
                "DeepSeek call done: mode=normal model={} latencyMs={} contentChars={} preview={}",
                model,
                latencyMs,
                content.length(),
                preview(content)
        );
        return new AiResult(content.strip(), request, response == null ? Map.of() : response, latencyMs);
    }

    public StreamResult chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        long started = System.nanoTime();
        log.info("DeepSeek call start: mode=stream model={} baseUrl={} userPromptChars={}", model, baseUrl, userPrompt == null ? 0 : userPrompt.length());
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        StringBuilder fullText = new StringBuilder();
        int[] chunkCount = {0};
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(chatCompletionsUrl()).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
            if (!apiKey.isBlank()) {
                connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(objectMapper.writeValueAsBytes(request));
            }

            int status = connection.getResponseCode();
            if (status >= 400) {
                String errorBody;
                InputStream errorStream = connection.getErrorStream();
                if (errorStream == null) {
                    errorBody = "";
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        errorBody = reader.lines().reduce("", (left, right) -> left + right);
                    }
                }
                throw new IllegalStateException("DeepSeek stream failed: HTTP " + status + " " + errorBody);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).strip();
                    if (data.isBlank()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String delta = extractDelta(data);
                    if (!delta.isEmpty()) {
                        chunkCount[0]++;
                        fullText.append(delta);
                        onDelta.accept(delta);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("DeepSeek call failed: mode=stream model={} message={}", model, e.getMessage());
            throw new IllegalStateException("DeepSeek stream request failed", e);
        }
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        log.info(
                "DeepSeek call done: mode=stream model={} latencyMs={} chunks={} contentChars={} preview={}",
                model,
                latencyMs,
                chunkCount[0],
                fullText.length(),
                preview(fullText.toString())
        );
        return new StreamResult(fullText.toString().strip(), request, latencyMs);
    }

    private String chatCompletionsUrl() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    @SuppressWarnings("unchecked")
    private String extractDelta(String json) throws JsonProcessingException {
        Map<String, Object> event = objectMapper.readValue(json, MAP_TYPE);
        Object choicesValue = event.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty() || !(choices.get(0) instanceof Map<?, ?> choice)) {
            return "";
        }
        Object deltaValue = choice.get("delta");
        if (deltaValue instanceof Map<?, ?> delta) {
            Object contentValue = delta.get("content");
            return contentValue == null ? "" : String.valueOf(contentValue);
        }
        Object messageValue = choice.get("message");
        if (messageValue instanceof Map<?, ?> message) {
            Object contentValue = message.get("content");
            return contentValue == null ? "" : String.valueOf(contentValue);
        }
        return "";
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    public record AiResult(
            String content,
            Map<String, Object> request,
            Map<String, Object> response,
            int latencyMs
    ) {
    }

    public record StreamResult(
            String content,
            Map<String, Object> request,
            int latencyMs
    ) {
    }
}
