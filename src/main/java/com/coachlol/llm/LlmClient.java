package com.coachlol.llm;

/**
 * Abstracción del proveedor de IA. El resto de la app (CoachService) solo conoce esta
 * interfaz: damos un prompt de sistema (instrucciones + catálogo, estable) y un mensaje
 * de usuario (estado volátil de la partida) y recibimos el texto del consejo.
 *
 * Hay una implementación por proveedor (Anthropic / OpenAI). Cuál se usa se decide por
 * la propiedad {@code coach.llm.provider}, así cambiar de proveedor es solo configuración.
 */
public interface LlmClient {

    /**
     * @param systemPrompt instrucciones fijas + catálogo de items (prefijo estable).
     * @param userMessage  estado actual de la partida + consejo previo.
     * @return el texto del consejo generado por el modelo.
     */
    String generate(String systemPrompt, String userMessage);

    /** Nombre del proveedor/modelo, solo para logging. */
    String describe();
}
