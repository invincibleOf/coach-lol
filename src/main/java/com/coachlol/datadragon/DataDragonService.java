package com.coachlol.datadragon;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Carga y cachea los datos estáticos del parche actual desde Data Dragon:
 *  - la versión (parche) más reciente,
 *  - el catálogo de items (id -> nombre, coste, descripción, tags).
 *
 * Se usa para dos cosas:
 *  1. Enriquecer los items que YA tienen los jugadores con su coste real
 *     ({@link #describe}), para que el resumen sea preciso.
 *  2. Ofrecer a Claude un catálogo de items terminados del parche
 *     ({@link #itemCatalog}), para que recomiende builds reales y actuales.
 *
 * Si Data Dragon no está disponible al arrancar, el resto del sistema sigue
 * funcionando (sin enriquecer): degradamos con elegancia.
 */
@Service
public class DataDragonService {

    private static final String CDN_BASE = "https://ddragon.leagueoflegends.com/cdn/";

    private final RestClient client;

    private volatile String version = "desconocida";
    private final Map<Integer, ItemInfo> itemsById = new ConcurrentHashMap<>();
    private volatile String itemCatalog = "";

    public DataDragonService(@Qualifier("dataDragonRestClient") RestClient client) {
        this.client = client;
    }

    @PostConstruct
    void load() {
        try {
            refresh();
        } catch (Exception ex) {
            System.err.println("[DataDragon] No se pudo cargar; se seguirá sin enriquecer. Causa: "
                    + ex.getMessage());
        }
    }

    /** Descarga la versión más reciente y su item.json, y reconstruye la caché. */
    public void refresh() {
        String[] versions = client.get()
                .uri("/api/versions.json")
                .retrieve()
                .body(String[].class);
        if (versions == null || versions.length == 0) {
            throw new IllegalStateException("Data Dragon no devolvió versiones");
        }
        this.version = versions[0];

        JsonNode root = client.get()
                .uri("/cdn/{version}/data/es_ES/item.json", version)
                .retrieve()
                .body(JsonNode.class);

        Map<Integer, ItemInfo> parsed = new ConcurrentHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.path("data").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            int id;
            try {
                id = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException nf) {
                continue;
            }
            JsonNode n = entry.getValue();
            JsonNode into = n.path("into");
            parsed.put(id, new ItemInfo(
                    id,
                    n.path("name").asText(),
                    n.path("gold").path("total").asInt(),
                    n.path("plaintext").asText(""),
                    readTags(n),
                    n.path("gold").path("purchasable").asBoolean(false),
                    into.isArray() && !into.isEmpty(),         // tiene mejoras = no es item final
                    n.path("maps").path("11").asBoolean(false) // disponible en Grieta del Invocador
            ));
        }

        itemsById.clear();
        itemsById.putAll(parsed);
        this.itemCatalog = buildCatalog();
        System.out.println("[DataDragon] Parche " + version + " cargado: "
                + itemsById.size() + " items.");
    }

    public String getVersion() {
        return version;
    }

    /** "Doran's Ring (400 oro)" o, si no se encuentra el id, el nombre de respaldo. */
    public String describe(int itemId, String fallbackName) {
        ItemInfo info = itemsById.get(itemId);
        if (info == null) {
            return fallbackName;
        }
        return info.name() + " (" + info.goldTotal() + " oro)";
    }

    /** Catálogo compacto de items terminados (para el system prompt). */
    public String itemCatalog() {
        return itemCatalog;
    }

    /** URL de la imagen del item en el CDN de Data Dragon (parche actual). */
    public String iconUrl(int itemId) {
        return CDN_BASE + version + "/img/item/" + itemId + ".png";
    }

    /**
     * Detecta qué items del parche aparecen mencionados en el texto del consejo y
     * devuelve su nombre + URL de icono, en orden de aparición. Funciona porque el
     * catálogo y el consejo usan los mismos nombres (cargamos Data Dragon en es_ES).
     */
    public List<MentionedItem> findMentionedItems(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 1 entrada por NOMBRE: Data Dragon repite el mismo item bajo varios ids
        // (variantes por mapa, versiones de Ornn, etc.). Nos quedamos con la mejor
        // variante (preferimos la de Grieta del Invocador y comprable).
        Map<String, ItemInfo> byName = new LinkedHashMap<>();
        for (ItemInfo info : itemsById.values()) {
            if (info.name().length() >= 5 && text.contains(info.name())) {
                byName.merge(info.name(), info,
                        (a, b) -> variantScore(a) >= variantScore(b) ? a : b);
            }
        }
        Collection<ItemInfo> unique = byName.values();

        return unique.stream()
                // descarta nombres que son subcadena de otro item mencionado
                // (p. ej. "Botas" dentro de "Botas de hechicero")
                .filter(a -> unique.stream().noneMatch(b ->
                        b != a && b.name().length() > a.name().length()
                                && b.name().contains(a.name())))
                .sorted(Comparator.comparingInt(i -> text.indexOf(i.name())))
                .limit(6)
                .map(i -> new MentionedItem(i.name(), iconUrl(i.id())))
                .toList();
    }

    private static int variantScore(ItemInfo i) {
        return (i.onSummonersRift() ? 2 : 0) + (i.purchasable() ? 1 : 0);
    }

    public record MentionedItem(String name, String icon) {
    }

    private String buildCatalog() {
        return itemsById.values().stream()
                .filter(i -> i.purchasable() && i.onSummonersRift() && !i.hasInto() && i.goldTotal() >= 1100)
                .sorted(Comparator.comparingInt(ItemInfo::goldTotal))
                .map(i -> "- " + i.name() + " — " + i.goldTotal() + " oro"
                        + (i.plaintext().isBlank() ? "" : " — " + i.plaintext())
                        + (i.tags().isEmpty() ? "" : " " + i.tags()))
                .collect(Collectors.joining("\n"));
    }

    private List<String> readTags(JsonNode n) {
        List<String> tags = new ArrayList<>();
        for (JsonNode t : n.path("tags")) {
            tags.add(t.asText());
        }
        return tags;
    }

    public record ItemInfo(int id, String name, int goldTotal, String plaintext,
                           List<String> tags, boolean purchasable, boolean hasInto,
                           boolean onSummonersRift) {
    }
}
