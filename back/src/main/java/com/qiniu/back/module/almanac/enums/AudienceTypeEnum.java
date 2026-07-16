package com.qiniu.back.module.almanac.enums;

import java.util.Locale;

public enum AudienceTypeEnum {
    GENERAL,
    CS_STUDENT,
    WORKPLACE;

    public static AudienceTypeEnum from(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (AudienceTypeEnum type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return GENERAL;
    }
}
