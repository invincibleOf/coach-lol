package com.coachlol.coach;

import com.coachlol.datadragon.DataDragonService;
import com.coachlol.datadragon.DataDragonService.MentionedItem;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mantiene las conexiones SSE abiertas con el/los overlay(s) y les empuja cada
 * consejo nuevo.
 *
 * El payload es JSON: el texto del consejo + los items mencionados (con su icono),
 * que el overlay detecta vía Data Dragon para pintar las imágenes.
 */
@Component
public class AdviceBroadcaster {

    private final DataDragonService dataDragon;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public AdviceBroadcaster(DataDragonService dataDragon) {
        this.dataDragon = dataDragon;
    }

    /** Registra un nuevo cliente (el overlay) que quiere recibir consejos. */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // sin timeout: la partida puede durar
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    /** Empuja un consejo (enriquecido con iconos) a todos los overlays conectados. */
    public void broadcast(String advice) {
        AdvicePayload payload = new AdvicePayload(advice, dataDragon.findMentionedItems(advice));
        for (SseEmitter emitter : emitters) {
            try {
                // Spring serializa el record a JSON (Jackson) al indicar APPLICATION_JSON.
                emitter.send(SseEmitter.event()
                        .name("advice")
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                emitters.remove(emitter);
            }
        }
    }

    /** Lo que recibe el overlay por SSE. */
    public record AdvicePayload(String advice, List<MentionedItem> items) {
    }
}
