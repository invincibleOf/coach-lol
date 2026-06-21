package com.coachlol.liveclient;

import com.coachlol.datadragon.DataDragonService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Transforma el JSON enorme de la Live Client Data API en:
 *  - un RESUMEN compacto y legible para enviárselo a Claude (ahorra tokens), y
 *  - una FIRMA que nos dice si el estado "relevante" ha cambiado desde la última vez.
 *
 * Los items se enriquecen con su coste real vía Data Dragon: la Live API solo da
 * el nombre del item; con el itemID + Data Dragon añadimos el coste del parche actual.
 */
@Component
public class GameStateMapper {

    private final DataDragonService dataDragon;

    public GameStateMapper(DataDragonService dataDragon) {
        this.dataDragon = dataDragon;
    }

    /** Texto compacto con el estado de ambos equipos para el prompt. */
    public String summarize(JsonNode data) {
        JsonNode active = data.path("activePlayer");
        String myName = playerName(active);
        long myGold = active.path("currentGold").asLong();

        StringBuilder sb = new StringBuilder();
        sb.append("Tu oro disponible ahora mismo: ").append(myGold).append("\n");
        sb.append("Jugadores (equipo | campeón | nivel | KDA | CS | items):\n");

        for (JsonNode p : data.path("allPlayers")) {
            String name = playerName(p);
            boolean isMe = name.equalsIgnoreCase(myName);

            sb.append("- ").append(p.path("team").asText())   // ORDER o CHAOS
              .append(isMe ? " (TÚ)" : "")
              .append(" | ").append(p.path("championName").asText())
              .append(" | nivel ").append(p.path("level").asInt())
              .append(" | KDA ")
              .append(p.path("scores").path("kills").asInt()).append("/")
              .append(p.path("scores").path("deaths").asInt()).append("/")
              .append(p.path("scores").path("assists").asInt())
              .append(" | CS ").append(p.path("scores").path("creepScore").asInt())
              .append(" | items: ").append(itemList(p))
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * Firma del estado "relevante": niveles, muertes e items de cada jugador.
     * Si cambia, es que pasó algo digno de re-analizar (subida de nivel, muerte,
     * item completado).
     */
    public String signature(JsonNode data) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : data.path("allPlayers")) {
            sb.append(p.path("level").asInt()).append(':')
              .append(p.path("scores").path("deaths").asInt()).append(':')
              .append(p.path("items").size()).append('|');
        }
        return sb.toString();
    }

    private String playerName(JsonNode player) {
        // En clientes recientes el nombre viene en riotId; en otros en summonerName.
        String riotId = player.path("riotId").asText("");
        return riotId.isBlank() ? player.path("summonerName").asText("") : riotId;
    }

    private String itemList(JsonNode player) {
        JsonNode items = player.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "(ninguno)";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            int itemId = item.path("itemID").asInt(0);
            String fallbackName = item.path("displayName").asText();
            sb.append(dataDragon.describe(itemId, fallbackName)); // "Nombre (coste oro)"
        }
        return sb.toString();
    }
}
