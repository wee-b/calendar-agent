package com.qiniu.back.module.almanac.enums;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum JianXingRuleEnum {

    JIAN("建",
            Arrays.asList("开新章节", "启动小项目", "列今日待办", "冲一局排位"),
            Arrays.asList("一上来就摆烂", "半途放弃任务", "冲动消费")),
    CHU("除",
            Arrays.asList("清理桌面", "复盘错题", "删掉无效收藏", "整理下载目录"),
            Arrays.asList("继续堆积烂尾任务", "熬夜内耗", "囤无用资料")),
    MAN("满",
            Arrays.asList("批量刷题", "备份文件", "补全笔记", "整理知识库"),
            Arrays.asList("无脑剁手", "暴饮暴食", "泄露账号密码")),
    PING("平",
            Arrays.asList("稳步推进", "常规刷题", "按计划上班上课", "温和沟通"),
            Arrays.asList("赌气摆烂", "突然裸辞", "无故翘课")),
    DING("定",
            Arrays.asList("敲定计划", "预约面试", "确认日程", "锁定技术方案"),
            Arrays.asList("反复横跳", "临时鸽人", "随手改需求")),
    ZHI("执",
            Arrays.asList("硬核执行", "赶作业赶需求", "排位冲分", "健身运动"),
            Arrays.asList("摸鱼划水", "挂机摆烂", "拖延作业")),
    PO("破",
            Arrays.asList("改掉坏习惯", "卸载干扰应用", "推翻无效计划", "重构旧代码"),
            Arrays.asList("开启长期大坑", "冲动表白", "借钱放贷")),
    WEI("危",
            Arrays.asList("谨慎答题", "备份重要文件", "低调做事", "查漏补缺"),
            Arrays.asList("冒险投资", "大额转账", "与人争执")),
    CHENG("成",
            Arrays.asList("考试刷题", "面试投递", "发布作品", "排位上分"),
            Arrays.asList("临时弃考", "消极摆心态", "故意搞砸")),
    SHOU("收",
            Arrays.asList("收尾作业", "复盘本周", "归档代码", "收纳物品"),
            Arrays.asList("开新大坑", "外借贵重物品", "离家远行")),
    KAI("开",
            Arrays.asList("出门自习", "线下面试", "约人沟通", "开新游戏坑"),
            Arrays.asList("封闭自己", "拒绝沟通", "久坐不动")),
    BI("闭",
            Arrays.asList("闭门刷题", "宅家休息", "静心看书", "关闭通知免打扰"),
            Arrays.asList("长途奔波", "主动应酬", "频繁闲逛"));

    private final String name;
    private final List<String> yiPool;
    private final List<String> jiPool;

    JianXingRuleEnum(String name, List<String> yiPool, List<String> jiPool) {
        this.name = name;
        this.yiPool = yiPool;
        this.jiPool = jiPool;
    }

    public static JianXingRuleEnum getByJianXing(String jianXing) {
        for (JianXingRuleEnum e : values()) {
            if (e.name.equals(jianXing)) {
                return e;
            }
        }
        return PING;
    }

    public List<String> stableYi(int count, LocalDate date) {
        return stablePick(yiPool, count, date.toEpochDay());
    }

    public List<String> stableJi(int count, LocalDate date) {
        return stablePick(jiPool, count, date.toEpochDay() + 31);
    }

    private List<String> stablePick(List<String> source, int count, long seed) {
        List<String> copy = new ArrayList<>(source);
        Collections.shuffle(copy, new java.util.Random(seed));
        return copy.size() > count ? copy.subList(0, count) : copy;
    }
}
