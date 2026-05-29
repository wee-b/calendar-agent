package com.qiniu.back.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DeletedFlagEnum {
    // 0-未删除 1-已删除

    NORMAL_STATUS(0,"未删除"),
    DELETED_STATUS(1,"已删除");

    private final Integer value;
    private final String desc;
}
