package com.coachlol.coach;

import com.coachlol.datadragon.DataDragonService;
import com.coachlol.llm.LlmClient;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * Núcleo de IA: recibe el resumen del estado de la partida y pide al proveedor de IA
 * (vía {@link LlmClient}) un consejo breve y accionable.
 *
 * Esta clase es AGNÓSTICA del proveedor: construye el prompt de sistema (instrucciones
 * FIJAS + catálogo de items del parche, estable durante todo el parche) y el mensaje de
 * usuario (estado VOLÁTIL de la partida), y delega la llamada al modelo en LlmClient.
 * Qué proveedor se usa (Anthropic / OpenAI) se decide por coach.llm.provider.
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

            CADA jugador indica su rol/línea entre paréntesis (Top, Jungla, Mid, ADC,
            Support). La build que recomiendes debe ser la PROPIA de TU campeón en TU rol:
            si juegas Jungla, recomienda objeto de jungla y el patrón de jungla; si juegas
            ADC, build de tirador; etc. NUNCA propongas la build de otra línea distinta a
            la tuya. Si tu rol aparece como "rol desconocido", deduce el rol más probable
            por tu campeón y tus items actuales.

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

    private final DataDragonService dataDragon;
    private final LlmClient llm;

    public CoachService(DataDragonService dataDragon, LlmClient llm) {
        this.dataDragon = dataDragon;
        this.llm = llm;
    }

    @PostConstruct
    void logProvider() {
        System.out.println("[CoachService] Proveedor de IA: " + llm.describe());
    }

    /**
     * @param previousAdvice consejo anterior (puede ser null/vacío). Se le pasa al
     *                       modelo para anclar la recomendación y evitar el flip-flop.
     */
    public String coach(String gameStateSummary, String previousAdvice) {
        return llm.generate(
                buildSystemPrompt(),
                buildUserMessage(gameStateSummary, previousAdvice));
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
