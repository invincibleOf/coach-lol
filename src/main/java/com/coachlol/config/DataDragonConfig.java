package com.coachlol.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP para el CDN público de Data Dragon (datos estáticos del juego).
 * A diferencia de la Live Client Data API, aquí el TLS es normal (cert válido),
 * así que no necesitamos ninguna configuración insegura.
 */
@Configuration
public class DataDragonConfig {

    @Bean
    public RestClient dataDragonRestClient() {
        return RestClient.builder()
                .baseUrl("https://ddragon.leagueoflegends.com")
                .build();
    }
}
