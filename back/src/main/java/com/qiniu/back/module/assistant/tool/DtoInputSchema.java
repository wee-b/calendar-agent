package com.qiniu.back.module.assistant.tool;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据 DTO 的字段类型、Swagger 注解和校验注解生成 MCP inputSchema。
 * DTO 是字段说明与必填规则的唯一来源，避免工具定义与接口 DTO 漂移。
 */
final class DtoInputSchema {

    private DtoInputSchema() {
    }

    static Map<String, Object> empty() {
        return Map.of("type", "object", "properties", Map.of());
    }

    static Map<String, Object> from(Class<?> dtoType) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Field field : fieldsOf(dtoType)) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Map<String, Object> property = propertySchema(
                    field.getGenericType(), field.getAnnotation(Schema.class));
            applyValidationConstraints(property, field);
            String propertyName = propertyName(field);
            properties.put(propertyName, property);
            if (isRequired(field)) {
                required.add(propertyName);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static String propertyName(Field field) {
        JsonAlias alias = field.getAnnotation(JsonAlias.class);
        return alias != null && alias.value().length > 0 ? alias.value()[0] : field.getName();
    }

    static Map<String, Object> select(Class<?> dtoType, String... fieldNames) {
        Map<String, Object> source = from(dtoType);
        Set<String> selectedNames = Set.of(fieldNames);

        @SuppressWarnings("unchecked")
        Map<String, Object> sourceProperties = (Map<String, Object>) source.get("properties");
        Map<String, Object> selectedProperties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : sourceProperties.entrySet()) {
            if (selectedNames.contains(property.getKey())) {
                selectedProperties.put(property.getKey(), property.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        List<String> sourceRequired = (List<String>) source.getOrDefault("required", List.of());
        List<String> selectedRequired = sourceRequired.stream().filter(selectedNames::contains).toList();

        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("type", "object");
        selected.put("properties", selectedProperties);
        if (!selectedRequired.isEmpty()) {
            selected.put("required", selectedRequired);
        }
        return selected;
    }

    @SafeVarargs
    static Map<String, Object> merge(Map<String, Object>... schemas) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Map<String, Object> schema : schemas) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schemaProperties = (Map<String, Object>) schema.get("properties");
            properties.putAll(schemaProperties);

            @SuppressWarnings("unchecked")
            List<String> schemaRequired = (List<String>) schema.getOrDefault("required", List.of());
            for (String name : schemaRequired) {
                if (!required.contains(name)) {
                    required.add(name);
                }
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("type", "object");
        merged.put("properties", properties);
        if (!required.isEmpty()) {
            merged.put("required", required);
        }
        return merged;
    }

    private static List<Field> fieldsOf(Class<?> dtoType) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = dtoType; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }

        List<Field> fields = new ArrayList<>();
        for (Class<?> type : hierarchy) {
            fields.addAll(List.of(type.getDeclaredFields()));
        }
        return fields;
    }

    private static void applyValidationConstraints(Map<String, Object> property, Field field) {
        if (field.isAnnotationPresent(NotBlank.class)) {
            property.put("minLength", 1);
        }

        Size size = field.getAnnotation(Size.class);
        if (size != null) {
            boolean array = "array".equals(property.get("type"));
            property.put(array ? "minItems" : "minLength", size.min());
            if (size.max() != Integer.MAX_VALUE) {
                property.put(array ? "maxItems" : "maxLength", size.max());
            }
        }

        Min min = field.getAnnotation(Min.class);
        if (min != null) {
            property.put("minimum", min.value());
        }
        Max max = field.getAnnotation(Max.class);
        if (max != null) {
            property.put("maximum", max.value());
        }
        Pattern pattern = field.getAnnotation(Pattern.class);
        if (pattern != null) {
            property.put("pattern", pattern.regexp());
        }
    }

    private static boolean isRequired(Field field) {
        Schema schema = field.getAnnotation(Schema.class);
        return field.isAnnotationPresent(NotNull.class)
                || field.isAnnotationPresent(NotBlank.class)
                || (schema != null && schema.requiredMode() == Schema.RequiredMode.REQUIRED);
    }

    private static Map<String, Object> propertySchema(Type type, Schema annotation) {
        Map<String, Object> property = new LinkedHashMap<>();
        Class<?> rawType = rawType(type);

        if (Collection.class.isAssignableFrom(rawType)) {
            property.put("type", "array");
            property.put("items", propertySchema(collectionItemType(type), null));
        } else if (rawType == String.class || rawType == LocalDate.class || rawType == LocalDateTime.class
                || rawType.isEnum()) {
            property.put("type", "string");
            if (rawType == LocalDate.class) {
                property.put("format", "date");
            } else if (rawType == LocalDateTime.class) {
                property.put("format", "date-time");
            } else if (rawType.isEnum()) {
                property.put("enum", List.of(rawType.getEnumConstants()));
            }
        } else if (rawType == int.class || rawType == Integer.class
                || rawType == long.class || rawType == Long.class) {
            property.put("type", "integer");
        } else if (rawType == float.class || rawType == Float.class
                || rawType == double.class || rawType == Double.class) {
            property.put("type", "number");
        } else if (rawType == boolean.class || rawType == Boolean.class) {
            property.put("type", "boolean");
        } else {
            property.putAll(from(rawType));
        }

        if (annotation != null && !annotation.description().isBlank()) {
            property.put("description", annotation.description());
        }
        return property;
    }

    private static Class<?> rawType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }

    private static Type collectionItemType(Type type) {
        if (type instanceof ParameterizedType parameterizedType
                && parameterizedType.getActualTypeArguments().length == 1) {
            return parameterizedType.getActualTypeArguments()[0];
        }
        return String.class;
    }
}
