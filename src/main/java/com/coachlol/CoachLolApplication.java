package com.coachlol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // habilita el @Scheduled del poller (nuestro "cron")
public class CoachLolApplication {

    public static void main(String[] args) {
        // La Live Client Data API usa un certificado autofirmado en 127.0.0.1:2999
        // cuyo CN no coincide con "127.0.0.1". El JDK HttpClient verifica el hostname
        // por defecto; lo desactivamos SOLO para poder hablar con ese servidor local.
        // (No afecta a llamadas a servidores reales como la API de Anthropic, que usan
        //  certificados válidos.)
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

        SpringApplication.run(CoachLolApplication.class, args);
    }
}
