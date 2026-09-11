package com.qiniu.back.module.almanac.service.impl;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import com.qiniu.back.domain.almanac.vo.AlmanacDayVO;
import com.qiniu.back.module.almanac.enums.JianXingRuleEnum;
import com.qiniu.back.module.almanac.enums.TraditionalAlmanacMappingEnum;
import com.qiniu.back.module.almanac.service.AlmanacService;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AlmanacServiceImpl implements AlmanacService {

    private static final int YI_COUNT = 4;
    private static final int JI_COUNT = 3;

    private static final List<String> LUCKY_COLORS = Arrays.asList(
            "天蓝", "松绿", "米白", "浅紫", "橙黄", "银灰", "樱粉", "湖蓝", "竹青"
    );

    @Override
    public AlmanacDayVO getDayAlmanac(String date) {
        LocalDate localDate = parseDate(date);

        Solar solar = Solar.fromYmd(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
        Lunar lunar = solar.getLunar();

        String jianXing = getFirstString(lunar, "getZhiXing", "getDayTwelveStar");
        List<String> traditionalYi = getStringList(lunar, "getDayYi", "getDayYis", "getYi");
        List<String> traditionalJi = getStringList(lunar, "getDayJi", "getDayJis", "getJi");

        AlmanacDayVO vo = new AlmanacDayVO();
        vo.setSolarDate(localDate.toString());
        vo.setLunarDate(buildLunarDate(lunar));
        vo.setWeek("星期" + weekInChinese(localDate));
        vo.setJianXing(jianXing == null || jianXing.isBlank() ? "平" : jianXing);
        vo.setTraditionalYiList(traditionalYi);
        vo.setTraditionalJiList(traditionalJi);
        vo.setYiList(buildModernList(traditionalYi, localDate, true, vo.getJianXing()));
        vo.setJiList(buildModernList(traditionalJi, localDate, false, vo.getJianXing()));
        vo.setLuckyColor(pickLuckyColor(localDate));
        vo.setLuckyNum((int) Math.floorMod(localDate.toEpochDay(), 9) + 1);
        return vo;
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式错误，请使用 yyyy-MM-dd");
        }
    }

    private List<String> buildModernList(List<String> traditionalItems,
                                         LocalDate date,
                                         boolean yi,
                                         String jianXing) {
        int targetCount = yi ? YI_COUNT : JI_COUNT;
        Set<String> result = new LinkedHashSet<>();

        for (String traditional : traditionalItems) {
            List<String> candidates = TraditionalAlmanacMappingEnum.mixedCandidates(traditional);
            if (!candidates.isEmpty()) {
                result.add(stablePick(candidates, date, traditional));
            }
            if (result.size() >= targetCount) {
                break;
            }
        }

        JianXingRuleEnum rule = JianXingRuleEnum.getByJianXing(jianXing);
        List<String> fallback = yi ? rule.stableYi(targetCount, date) : rule.stableJi(targetCount, date);
        for (String item : fallback) {
            result.add(item);
            if (result.size() >= targetCount) {
                break;
            }
        }

        return new ArrayList<>(result);
    }

    private String stablePick(List<String> candidates, LocalDate date, String traditional) {
        int index = Math.floorMod(Objects.hash(date.toString(), traditional), candidates.size());
        return candidates.get(index);
    }

    private String pickLuckyColor(LocalDate date) {
        int index = Math.floorMod(Objects.hash(date.toString(), "luckyColor"), LUCKY_COLORS.size());
        return LUCKY_COLORS.get(index);
    }

    private String buildLunarDate(Lunar lunar) {
        String year = getFirstString(lunar, "getYearInGanZhi", "getYearInGanZhiExact");
        String month = getFirstString(lunar, "getMonthInChinese");
        String day = getFirstString(lunar, "getDayInChinese");
        if (year != null && month != null && day != null) {
            return year + "年" + month + "月" + day;
        }
        return lunar.toString();
    }

    private String weekInChinese(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "一";
            case TUESDAY -> "二";
            case WEDNESDAY -> "三";
            case THURSDAY -> "四";
            case FRIDAY -> "五";
            case SATURDAY -> "六";
            case SUNDAY -> "日";
        };
    }

    private String getFirstString(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(target, methodName);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private List<String> getStringList(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Object value = invokeNoArg(target, methodName);
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        result.add(String.valueOf(item));
                    }
                }
                return result;
            }
            if (value instanceof String[] array) {
                return Arrays.asList(array);
            }
            if (value instanceof String text && !text.isBlank()) {
                return Arrays.stream(text.split("[,，、\\s]+"))
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        }
        return List.of();
    }

    private Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
