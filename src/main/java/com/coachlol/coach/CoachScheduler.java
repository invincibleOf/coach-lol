package com.coachlol.coach;

import com.coachlol.liveclient.GameStateMapper;
import com.coachlol.liveclient.LiveClientService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * El "cron" del proyecto. Dos ritmos distintos (ver decisión de diseño):
 *  - Poll de datos: cada poll-interval-ms (rápido, local, gratis).
 *  - Análisis con IA: solo cuando cambia algo relevante o pasa el intervalo forzado,
 *    y siempre respetando un throttle mínimo entre llamadas a Claude.
 */
@Component
public class CoachScheduler {

    private final LiveClientService liveClient;
    private final GameStateMapper mapper;
    private final CoachService coachService;
    private final AdviceBroadcaster broadcaster;

    private final long minBetweenAdviceMs;
    private final long forcedAdviceIntervalMs;

    private String lastSignature = "";
    private Instant lastAdviceAt = Instant.EPOCH;
    private String lastAdvice = "";

    public CoachScheduler(LiveClientService liveClient,
                          GameStateMapper mapper,
                          CoachService coachService,
                          AdviceBroadcaster broadcaster,
                          @Value("${coach.min-seconds-between-advice}") long minSecondsBetweenAdvice,
                          @Value("${coach.forced-advice-interval-seconds}") long forcedAdviceIntervalSeconds) {
        this.liveClient = liveClient;
        this.mapper = mapper;
        this.coachService = coachService;
        this.broadcaster = broadcaster;
        this.minBetweenAdviceMs = minSecondsBetweenAdvice * 1000;
        this.forcedAdviceIntervalMs = forcedAdviceIntervalSeconds * 1000;
    }

    @Scheduled(fixedDelayString = "${coach.poll-interval-ms}")
    public void tick() {
        Optional<JsonNode> dataOpt = liveClient.fetchAllGameData();
        if (dataOpt.isEmpty()) {
            // Sin partida: limpiamos el estado para que la próxima empiece sin anclar
            // un consejo viejo y dé su primer consejo cuanto antes.
            resetState();
            return;
        }
        JsonNode data = dataOpt.get();

        Instant now = Instant.now();
        long sinceLastMs = Duration.between(lastAdviceAt, now).toMillis();

        // Throttle duro: nunca llamamos a Claude demasiado seguido.
        if (sinceLastMs < minBetweenAdviceMs) {
            return;
        }

        String signature = mapper.signature(data);
        boolean stateChanged = !signature.equals(lastSignature);
        boolean forcedRefresh = sinceLastMs >= forcedAdviceIntervalMs;

        if (!stateChanged && !forcedRefresh) {
            return; // nada relevante cambió y aún no toca refresco forzado
        }

        try {
            String summary = mapper.summarize(data);
            String advice = coachService.coach(summary, lastAdvice); // anclamos al consejo previo
            broadcaster.broadcast(advice);

            lastSignature = signature;
            lastAdviceAt = now;
            lastAdvice = advice;
        } catch (Exception ex) {
            // Una llamada fallida (rate limit, red) no debe tumbar el poller.
            System.err.println("[CoachScheduler] Error generando consejo: " + ex.getMessage());
        }
    }

    private void resetState() {
        lastSignature = "";
        lastAdviceAt = Instant.EPOCH;
        lastAdvice = "";
    }
}
