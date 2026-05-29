package com.qiniu.back.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum GenderEnum {

    UNKNOWN(0,"保密"),
    MAN(1,"男"),
    WOMAN(2,"女");

    private final Integer value;
    private final String desc;
}
