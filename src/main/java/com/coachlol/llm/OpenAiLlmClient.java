package com.coachlol.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Implementación con la API de OpenAI (chat completions), vía RestClient para no añadir
 * otro SDK. Mismo contrato que el cliente de Anthropic: recibe prompt de sistema +
 * mensaje de usuario y devuelve el texto del consejo.
 *
 * No usa "thinking" ni cache_control explícito (no existen igual en OpenAI); el caching
 * de prefijos en OpenAI es automático. Funciona con cualquier endpoint compatible con la
 * API de OpenAI (Azure OpenAI, OpenRouter, etc.) cambiando openai.base-url.
 *
 * Solo se crea como bean si coach.llm.provider = openai.
 */
@Component
@ConditionalOnProperty(name = "coach.llm.provider", havingValue = "openai")
public class OpenAiLlmClient implements LlmClient {

    private final RestClient client;
    private final String model;

    public OpenAiLlmClient(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${openai.base-url}") String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "coach.llm.provider=openai pero OPENAI_API_KEY no está definida.");
        }
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public String generate(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_completion_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));

        JsonNode response = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            return "";
        }
        return response.path("choices").path(0).path("message").path("content").asText("").trim();
    }

    @Override
    public String describe() {
        return "OpenAI (" + model + ")";
    }
}
