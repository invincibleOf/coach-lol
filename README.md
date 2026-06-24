# Coach LoL

Coach de **League of Legends en partida** asistido por IA. Mientras juegas, lee el
estado real de tu partida desde el cliente de LoL, se lo pasa a Claude (modelo Opus
de Anthropic) y muestra en un **overlay** consejos breves y accionables sobre qué
comprar, cómo jugar tu línea y cómo aportar al equipo — actualizados en tiempo real.

## ¿Qué hace?

Cada pocos segundos consulta la **Live Client Data API** local que el cliente de LoL
expone durante la partida (`https://127.0.0.1:2999/liveclientdata`). Con esos datos:

- Construye un resumen compacto del estado: tu campeón, tu oro, y los dos equipos
  (**aliados** y **enemigos** etiquetados explícitamente, con campeón, nivel, KDA, CS
  e items de cada jugador).
- Enriquece los items con su **coste real del parche** usando [Data Dragon](https://developer.riotgames.com/docs/lol#data-dragon).
- Pide a Claude un consejo con tres secciones fijas: **🛒 Build**, **🛣️ Línea** y
  **🤝 Equipo**.
- Empuja el consejo (con los iconos de los items recomendados) al overlay vía
  **Server-Sent Events (SSE)**.

El coach distingue tu bando, así que solo recomienda items para **contrarrestar a los
campeones del equipo enemigo**, nunca a tus aliados.

## Cómo funciona (arquitectura)

```
Cliente de LoL (Live Client Data API, :2999)
            │  poll cada 5s
            ▼
   LiveClientService ──► GameStateMapper ──► resumen + "firma" de cambios
            │                                        │
            ▼                                        ▼
     CoachScheduler ──────────────────────► CoachService ──► API de Anthropic (Claude)
            │   (solo llama a Claude si cambia algo            │
            │    relevante o pasa el intervalo forzado)        ▼
            ▼                                            consejo en markdown
     AdviceBroadcaster (SSE) ──► overlay.html (navegador / overlay en el juego)
                                  + iconos de items vía Data Dragon
```

Componentes principales (`src/main/java/com/coachlol`):

| Clase | Responsabilidad |
|-------|-----------------|
| `liveclient/LiveClientService` | Consulta la Live Client Data API local; devuelve vacío si no hay partida. |
| `liveclient/GameStateMapper` | Convierte el JSON de la partida en un resumen legible y en una "firma" que detecta cambios relevantes. |
| `coach/CoachScheduler` | Poll periódico; decide *cuándo* pedir un consejo (throttle, cambios de estado, refresco forzado). |
| `coach/CoachService` | Llama a Claude con el system prompt + catálogo de items y devuelve el consejo. |
| `datadragon/DataDragonService` | Carga el parche actual: costes de items y catálogo de items terminados. |
| `coach/AdviceBroadcaster` + `AdviceController` | Stream SSE hacia el overlay. |
| `resources/static/overlay.html` | El overlay que muestra los consejos y los iconos de items. |

### Por qué dos ritmos

El poll de datos es local y gratis, así que se hace seguido (cada 5 s). Las llamadas a
Claude cuestan, así que solo se disparan cuando la `firma` del estado cambia de forma
relevante (subes de nivel, compras, cruzas un umbral de oro, cambian items enemigos,
hay muertes) o cuando pasa el intervalo de refresco forzado. Además hay un throttle
mínimo entre llamadas. El system prompt va con *prompt caching* para abaratar el coste
durante la partida.

## Requisitos

- **Java 17** y **Maven** (o solo **Docker**, que compila por ti).
- Una **API key de Anthropic** (`ANTHROPIC_API_KEY`).
- El **cliente de League of Legends** corriendo, en una partida (la Live Client Data
  API solo existe durante la partida).

## Configuración

Copia `.env.example` a `.env` y rellena tu key:

```env
ANTHROPIC_API_KEY=sk-ant-...
# Opcional: cambiar de modelo (por defecto claude-opus-4-8)
# ANTHROPIC_MODEL=claude-opus-4-8
```

Variables de entorno disponibles (ver `src/main/resources/application.yml`):

| Variable | Por defecto | Descripción |
|----------|-------------|-------------|
| `ANTHROPIC_API_KEY` | — | Tu API key de Anthropic (obligatoria). |
| `ANTHROPIC_MODEL` | `claude-opus-4-8` | Modelo de Claude a usar. |
| `COACH_LIVE_CLIENT_URL` | `https://127.0.0.1:2999/liveclientdata` | Endpoint local del cliente de LoL. |
| `COACH_POLL_INTERVAL_MS` | `5000` | Cada cuánto se consulta el estado de la partida. |
| `COACH_MIN_SECONDS_BETWEEN_ADVICE` | `45` | Throttle mínimo entre llamadas a Claude. |
| `COACH_FORCED_ADVICE_INTERVAL_SECONDS` | `180` | Refresco forzado aunque no cambie nada relevante. |

## Ejecución

### Con Docker (recomendado)

```bash
docker compose up --build
```

> El contenedor apunta a `host.docker.internal:2999` para alcanzar el cliente de LoL
> que corre en tu máquina anfitriona (ya configurado en `docker-compose.yml`).

### Con Maven (local)

```bash
mvn spring-boot:run
```

Una vez arrancado, abre el overlay en el navegador:

```
http://localhost:8080/overlay.html
```

## Probar sin jugar

Hay un endpoint de prueba que usa una partida de ejemplo
(`src/main/resources/sample/allgamedata.json`) y recorre el mismo pipeline real
(mapper → Claude → SSE), de modo que el overlay se actualiza igual que en partida:

```bash
curl -X POST http://localhost:8080/api/advice/test
```

> ⚠️ Esto hace una llamada **real y facturable** a la API de Anthropic.

## Stack

- **Spring Boot 3.4** (web + scheduling + SSE)
- **SDK oficial de Anthropic para Java** (`anthropic-java`)
- **Data Dragon** (datos estáticos del parche de LoL)
- **Live Client Data API** de Riot (estado de la partida en curso)
