package com.qiniu.back.domain.dailyNote.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class MonthCountVO {
    private LocalDate date;
    private int count;
}
