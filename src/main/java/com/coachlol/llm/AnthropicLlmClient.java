package com.coachlol.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementación con el SDK oficial de Anthropic (Claude). Es el proveedor por defecto.
 *
 * Decisiones de diseño (heredadas de la versión anterior):
 *  - Adaptive thinking: en Opus 4.6+ es lo idiomático; Claude decide cuánto razonar.
 *  - El prompt de sistema va con cache_control: al ser un prefijo estable byte a byte,
 *    las llamadas repetidas durante la partida reutilizan la caché y abaratan el coste.
 *
 * Solo se crea como bean si coach.llm.provider = anthropic (o si no se define).
 */
@Component
@ConditionalOnProperty(name = "coach.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class AnthropicLlmClient implements LlmClient {

    // La API key se lee de ANTHROPIC_API_KEY del entorno.
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    @Value("${anthropic.model}")
    private String model;

    @Override
    public String generate(String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                // effort bajo: el consejo es táctico y breve; ahorra tokens de pensamiento.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(systemPrompt)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())  // ContentBlock -> Optional<TextBlock>
                .map(text -> text.text())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    @Override
    public String describe() {
        return "Anthropic (" + model + ")";
    }
}
