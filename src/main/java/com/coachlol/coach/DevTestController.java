package com.coachlol.coach;

import com.coachlol.liveclient.GameStateMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Endpoint de PRUEBA para iterar el prompt y el overlay sin tener que jugar.
 *
 * Carga el allgamedata de ejemplo (src/main/resources/sample/allgamedata.json),
 * lo pasa por el MISMO pipeline real (mapper -> Claude -> SSE) y devuelve el consejo.
 * Mientras tanto, el overlay abierto en el navegador se actualizará igual que en
 * una partida real.
 *
 * Nota: esto hace una llamada REAL (y facturable) a la API de Anthropic.
 */
@RestController
@RequestMapping("/api/advice")
public class DevTestController {

    private final ObjectMapper objectMapper;
    private final GameStateMapper mapper;
    private final CoachService coachService;
    private final AdviceBroadcaster broadcaster;

    public DevTestController(ObjectMapper objectMapper,
                            GameStateMapper mapper,
                            CoachService coachService,
                            AdviceBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.coachService = coachService;
        this.broadcaster = broadcaster;
    }

    // POST /api/advice/test  -> dispara un consejo con el estado de ejemplo
    @PostMapping("/test")
    public String test() throws IOException {
        JsonNode data;
        try (InputStream in = new ClassPathResource("sample/allgamedata.json").getInputStream()) {
            data = objectMapper.readTree(in);
        }

        String summary = mapper.summarize(data);
        String advice = coachService.coach(summary);
        broadcaster.broadcast(advice); // se empuja al overlay igual que en partida real
        return advice;
    }
}
