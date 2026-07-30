package com.trading.algo.entity.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.trading.algo.dtos.WatchlistCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

/**
 * Converts Map<String, Set<WatchlistCategory>> to/from JSON string for JPA persistence.
 * This allows storing complex nested collections in a single database column.
 */
@Converter(autoApply = true)
@Slf4j
public class SymbolCategoriesConverter implements AttributeConverter<Map<String, Set<WatchlistCategory>>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Set<WatchlistCategory>> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (IOException e) {
            log.error("Error serializing symbolCategories to JSON", e);
            return "{}";
        }
    }

    @Override
    public Map<String, Set<WatchlistCategory>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty() || "{}".equals(dbData)) {
            return new HashMap<>();
        }
        try {
            // Construct JavaTypes for key and value
            com.fasterxml.jackson.databind.JavaType keyType =
                objectMapper.getTypeFactory().constructType(String.class);
            com.fasterxml.jackson.databind.JavaType valueType =
                objectMapper.getTypeFactory().constructCollectionType(HashSet.class, WatchlistCategory.class);

            MapType mapType = objectMapper.getTypeFactory()
                .constructMapType(HashMap.class, keyType, valueType);

            return objectMapper.readValue(dbData, mapType);
        } catch (IOException e) {
            log.error("Error deserializing symbolCategories from JSON: {}", dbData, e);
            return new HashMap<>();
        }
    }
}

