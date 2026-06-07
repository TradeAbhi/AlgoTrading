package com.trading.algo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "orb")
public class OrbConfig {

    private String instrumentsCsvPath = "classpath:ind_nifty200list.csv";
    private Map<String, String> keyToSymbolMap = new LinkedHashMap<>();

    public String getInstrumentsCsvPath() {
        return instrumentsCsvPath;
    }

    public void setInstrumentsCsvPath(String instrumentsCsvPath) {
        this.instrumentsCsvPath = instrumentsCsvPath;
    }

    public Map<String, String> getKeyToSymbolMap() {
        return keyToSymbolMap;
    }

    public void setKeyToSymbolMap(Map<String, String> keyToSymbolMap) {
        this.keyToSymbolMap = keyToSymbolMap;
    }

    public List<String> getNifty500InstrumentKeys() {
        return new ArrayList<>(keyToSymbolMap.keySet());
    }
}