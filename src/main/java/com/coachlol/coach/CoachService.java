package com.coachlol.coach;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.coachlol.datadragon.DataDragonService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Núcleo de IA: recibe el resumen del estado de la partida y pide a Claude un
 * consejo breve y accionable.
 *
 * Decisiones de diseño:
 *  - Adaptive thinking: en Opus 4.6+ es lo idiomático; Claude decide cuánto razonar.
 *    El antiguo budget_tokens da error 400 en Opus 4.8.
 *  - System prompt = instrucciones FIJAS + catálogo de items del parche (estable
 *    durante todo el parche). Va con cache_control: al ser un prefijo estable byte
 *    a byte, las llamadas repetidas durante la partida reutilizan la caché y abaratan
 *    mucho el coste. El estado VOLÁTIL de la partida va en el mensaje de usuario.
 */
@Service
public class CoachService {

    private static final String SYSTEM_PROMPT = """
            Eres un coach experto de League of Legends que asiste a un jugador EN PARTIDA.
            Recibes el estado actual: items (con su coste real), nivel, KDA y CS de ambos
            equipos, y tu oro disponible. El oro del enemigo NO está disponible: estímalo
            por sus items, CS y nivel.

            El estado viene separado en dos bloques: TU EQUIPO (ALIADOS) y EQUIPO ENEMIGO.
            Tu build defensiva debe contrarrestar SOLO a campeones del EQUIPO ENEMIGO.
            NUNCA recomiendes items para contrarrestar a un aliado de tu propio equipo.
            Cuando justifiques una compra por una amenaza, esa amenaza debe ser un campeón
            del EQUIPO ENEMIGO, nombrándolo explícitamente.

            Responde SIEMPRE en español y con ESTE FORMATO EXACTO de secciones markdown,
            sin ningún texto antes ni después y sin añadir otras secciones. Cada sección
            es muy breve (1-2 frases). Usa **negrita** SOLO para el nombre del item y su
            coste. Formato:

            ### 🛒 Build
            Qué comprar ahora y contra qué amenaza enemiga concreta. Recomienda SOLO items
            del catálogo de abajo, con su coste real, teniendo en cuenta tu oro disponible.
            Puedes indicar también el siguiente item a subir.

            ### 🛣️ Línea
            Si seguir en línea, ceder o rotar, según tu ventaja o desventaja de oro y CS.

            ### 🤝 Equipo
            Una acción concreta para aportar al equipo en los próximos minutos.

            No expliques teoría general ni des clases: instrucciones directas para ESTA
            situación. Prioriza la CONSISTENCIA: si la situación no ha cambiado de forma
            relevante respecto a tu consejo anterior, mantén la misma recomendación de item.
            """;

    // La API key se lee de ANTHROPIC_API_KEY del entorno.
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    private final DataDragonService dataDragon;

    @Value("${anthropic.model}")
    private String model;

    public CoachService(DataDragonService dataDragon) {
        this.dataDragon = dataDragon;
    }

    @PostConstruct
    void logModel() {
        System.out.println("[CoachService] Modelo configurado: " + model);
    }

    /**
     * @param previousAdvice consejo anterior (puede ser null/vacío). Se le pasa al
     *                       modelo para anclar la recomendación y evitar el flip-flop.
     */
    public String coach(String gameStateSummary, String previousAdvice) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                // effort bajo: el consejo es táctico y breve; ahorra tokens de pensamiento.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(buildSystemPrompt())
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(buildUserMessage(gameStateSummary, previousAdvice))
                .build();

        Message response = client.messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())  // ContentBlock -> Optional<TextBlock>
                .map(text -> text.text())
                .collect(Collectors.joining("\n"))
                .trim();
    }

    private String buildUserMessage(String gameStateSummary, String previousAdvice) {
        StringBuilder sb = new StringBuilder("Estado actual de la partida:\n")
                .append(gameStateSummary);
        if (previousAdvice != null && !previousAdvice.isBlank()) {
            sb.append("\n\nTu consejo anterior (mantén la MISMA recomendación de item salvo")
              .append(" que la situación haya cambiado de forma relevante; no cambies por")
              .append(" variaciones menores):\n")
              .append(previousAdvice);
        }
        return sb.toString();
    }

    /**
     * Instrucciones + catálogo de items del parche actual. Se concatena igual en cada
     * llamada (mismos bytes mientras no cambie el parche), por lo que la caché funciona.
     */
    private String buildSystemPrompt() {
        String catalog = dataDragon.itemCatalog();
        if (catalog.isBlank()) {
            return SYSTEM_PROMPT; // Data Dragon no cargó: seguimos sin catálogo
        }
        return SYSTEM_PROMPT
                + "\n\nCATÁLOGO DE ITEMS TERMINADOS DEL PARCHE " + dataDragon.getVersion()
                + " (recomienda solo items de esta lista, con sus costes reales):\n"
                + catalog;
    }
}
