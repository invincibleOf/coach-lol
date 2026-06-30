package com.coachlol.liveclient;

import com.coachlol.datadragon.DataDragonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
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

        // Determinamos PRIMERO tu jugador dentro de allPlayers: activePlayer NO trae
        // championName ni team, solo riotId/oro. De tu entrada en allPlayers sacamos el
        // equipo (ORDER/CHAOS) y el campeón. Así etiquetamos a cada jugador como ALIADO o
        // ENEMIGO de forma explícita y el modelo no recomienda counters contra aliados.
        JsonNode me = playerByName(data, myName);
        String myTeam = me.path("team").asText();
        String myChampion = me.path("championName").asText();
        String myRole = role(me);

        StringBuilder sb = new StringBuilder();
        sb.append("Tu campeón: ").append(myChampion)
          .append(" | tu rol/línea: ").append(myRole)
          .append(" | tu equipo: ").append(myTeam).append("\n");
        sb.append("Recomienda la build PROPIA de un ").append(myChampion)
          .append(" jugando ").append(myRole)
          .append(" (p. ej. una jungla compra objeto de jungla y prioriza su patrón")
          .append(" de farmeo/ganks; no la build de otra línea).\n");
        sb.append("Tu oro disponible ahora mismo: ").append(myGold).append("\n\n");

        sb.append("TU EQUIPO (ALIADOS — NO compres items para contrarrestarlos):\n");
        appendPlayers(sb, data, myName, myTeam, true);

        sb.append("\nEQUIPO ENEMIGO (las amenazas a contrarrestar con tu build):\n");
        appendPlayers(sb, data, myName, myTeam, false);

        return sb.toString();
    }

    /** Añade las filas de los jugadores del bando pedido (aliado si {@code allies}). */
    private void appendPlayers(StringBuilder sb, JsonNode data, String myName,
                               String myTeam, boolean allies) {
        for (JsonNode p : data.path("allPlayers")) {
            String team = p.path("team").asText();
            boolean isAlly = team.equals(myTeam);
            if (isAlly != allies) {
                continue;
            }
            boolean isMe = playerName(p).equalsIgnoreCase(myName);

            sb.append("- ").append(isMe ? "TÚ → " : "")
              .append(p.path("championName").asText())
              .append(" (").append(role(p)).append(")")
              .append(" | nivel ").append(p.path("level").asInt())
              .append(" | KDA ")
              .append(p.path("scores").path("kills").asInt()).append("/")
              .append(p.path("scores").path("deaths").asInt()).append("/")
              .append(p.path("scores").path("assists").asInt())
              .append(" | CS ").append(p.path("scores").path("creepScore").asInt())
              .append(" | items: ").append(itemList(p))
              .append("\n");
        }
    }

    /**
     * Rol/línea legible del jugador a partir del campo "position" de la Live Client API
     * (TOP/JUNGLE/MIDDLE/BOTTOM/UTILITY). Puede venir vacío en algunos modos; ahí
     * devolvemos "rol desconocido" para que el modelo no asuma una línea equivocada.
     */
    private String role(JsonNode player) {
        String pos = player.path("position").asText("");
        return switch (pos) {
            case "TOP" -> "Top";
            case "JUNGLE" -> "Jungla";
            case "MIDDLE" -> "Mid";
            case "BOTTOM" -> "ADC (bot)";
            case "UTILITY" -> "Support";
            default -> "rol desconocido";
        };
    }

    /** Devuelve la entrada de allPlayers cuyo nombre coincide, o un nodo vacío si no. */
    private JsonNode playerByName(JsonNode data, String name) {
        for (JsonNode p : data.path("allPlayers")) {
            if (playerName(p).equalsIgnoreCase(name)) {
                return p;
            }
        }
        return MissingNode.getInstance();
    }

    /**
     * Firma del estado "relevante para TU decisión". Cambia (y por tanto dispara un
     * nuevo análisis) solo cuando pasa algo que afecta a tu build o a tu jugada:
     *  - tu nivel o tu nº de items (subes de nivel / compras),
     *  - tu oro por tramos de 500 (cruzas un umbral de compra),
     *  - los items del ENEMIGO (counter-build),
     *  - una muerte/asesinato en la partida (resultado de pelea).
     * Ya NO reacciona a cada micro-cambio de los 10 jugadores.
     */
    public String signature(JsonNode data) {
        JsonNode active = data.path("activePlayer");
        String myName = playerName(active);
        long goldBucket = active.path("currentGold").asLong() / 500;

        String myTeam = playerByName(data, myName).path("team").asText();

        int myLevel = 0;
        int myItems = 0;
        int enemyItems = 0;
        int totalKills = 0;
        for (JsonNode p : data.path("allPlayers")) {
            totalKills += p.path("scores").path("kills").asInt();
            if (playerName(p).equalsIgnoreCase(myName)) {
                myLevel = p.path("level").asInt();
                myItems = p.path("items").size();
            } else if (!myTeam.isEmpty() && !p.path("team").asText().equals(myTeam)) {
                enemyItems += p.path("items").size();
            }
        }

        return "me:" + myLevel + ":" + myItems + ":g" + goldBucket
                + "|enemyItems:" + enemyItems
                + "|kills:" + totalKills;
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
