package com.trading.algo.delta.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class DeltaAppConfig {

    @Value("${delta.symbols}")
    private String symbolsCsv;

    @Value("${delta.api.base-url}")
    private String baseUrl;

    @Value("${delta.api.testnet:false}")
    private boolean testnet;

    @Value("${delta.api.testnet-url}")
    private String testnetUrl;

    @Value("${alert.cooldown.minutes:15}")
    private int cooldownMinutes;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Bean
    public List<String> monitoredSymbols() {
        return Arrays.stream(symbolsCsv.split(","))
                .map(String::trim)
                .toList();
    }

    public String getEffectiveBaseUrl() {
        return testnet ? testnetUrl : baseUrl;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }
}
