package com.coachlol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.cert.X509Certificate;

/**
 * Configura el cliente HTTP que habla con la Live Client Data API local.
 *
 * El cliente de LoL sirve esa API con un certificado autofirmado de Riot en
 * 127.0.0.1:2999. Por eso aquí construimos un RestClient que confía en ese
 * certificado. Este SSLContext es deliberadamente permisivo y SOLO debe usarse
 * contra el host local; nunca lo apuntes a un servidor real.
 */
@Configuration
public class RiotClientConfig {

    @Bean
    public RestClient liveClientRestClient(
            @Value("${coach.live-client-url}") String baseUrl) throws Exception {

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustAllManager()}, null);

        HttpClient httpClient = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    private static X509TrustManager trustAllManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // confiamos en todo (solo localhost)
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // confiamos en todo (solo localhost)
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
