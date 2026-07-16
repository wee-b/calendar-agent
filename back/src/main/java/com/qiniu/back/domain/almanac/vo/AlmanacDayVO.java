package com.qiniu.back.domain.almanac.vo;

import lombok.Data;

import java.util.List;

@Data
public class AlmanacDayVO {

    private String solarDate;

    private String lunarDate;

    private String week;

    private String jianXing;

    private List<String> traditionalYiList;

    private List<String> traditionalJiList;

    private List<String> yiList;

    private List<String> jiList;

    private String luckyColor;

    private Integer luckyNum;
}
