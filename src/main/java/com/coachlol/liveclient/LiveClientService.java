package com.coachlol.liveclient;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Consulta la Live Client Data API local del cliente de LoL.
 *
 * Esta API solo existe MIENTRAS hay una partida en curso. Si no hay partida,
 * la conexión se rechaza o devuelve 404: en ese caso devolvemos Optional.empty()
 * para que el resto del sistema simplemente no haga nada.
 */
@Service
public class LiveClientService {

    private final RestClient client;

    public LiveClientService(@Qualifier("liveClientRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * Devuelve el estado completo de la partida en curso, o vacío si no hay partida.
     */
    public Optional<JsonNode> fetchAllGameData() {
        try {
            JsonNode data = client.get()
                    .uri("/allgamedata")
                    .retrieve()
                    .body(JsonNode.class);
            return Optional.ofNullable(data);
        } catch (RestClientException ex) {
            // No hay partida activa (conexión rechazada / 404). Es un caso normal.
            return Optional.empty();
        }
    }
}
