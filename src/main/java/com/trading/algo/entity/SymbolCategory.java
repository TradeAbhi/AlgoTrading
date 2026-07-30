package com.trading.algo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.algo.dtos.WatchlistCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class SymbolCategory {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "categories_json", columnDefinition = "TEXT")
    @JsonIgnore
    @Builder.Default
    private String categoriesJson = "[]";

    /**
     * Gets categories deserialized from JSON
     */
    @JsonProperty("categories")
    public Set<WatchlistCategory> getCategories() {
        try {
            if (categoriesJson == null || categoriesJson.trim().isEmpty()) {
                return new HashSet<>();
            }
            return objectMapper.readValue(categoriesJson, new TypeReference<>() {});
        } catch (IOException e) {
            log.error("Error deserializing categories from JSON: {}", categoriesJson, e);
            return new HashSet<>();
        }
    }

    /**
     * Sets categories by serializing to JSON
     */
    @JsonProperty("categories")
    public void setCategories(Set<WatchlistCategory> categories) {
        try {
            if (categories == null || categories.isEmpty()) {
                this.categoriesJson = "[]";
            } else {
                this.categoriesJson = objectMapper.writeValueAsString(categories);
            }
        } catch (IOException e) {
            log.error("Error serializing categories to JSON", e);
            this.categoriesJson = "[]";
        }
    }
}
