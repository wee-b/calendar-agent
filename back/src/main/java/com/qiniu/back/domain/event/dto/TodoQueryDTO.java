package com.qiniu.back.domain.event.dto;

import lombok.Data;

@Data
public class TodoQueryDTO {
    private Integer year;
    private Integer month;
    private Integer status;
}
