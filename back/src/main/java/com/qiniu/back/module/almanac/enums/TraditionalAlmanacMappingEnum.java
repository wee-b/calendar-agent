package com.qiniu.back.module.almanac.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public enum TraditionalAlmanacMappingEnum {

    JI_SI("祭祀",
            list("复盘近期状态", "整理旧资料", "奖励自己"),
            list("复盘项目经验", "整理学习笔记", "回顾最近的 bug"),
            list("复盘工作事项", "整理会议记录", "沉淀项目经验")),
    QI_FU("祈福",
            list("写下今日目标", "给自己打气", "制定小愿望清单"),
            list("写下今日学习目标", "给调试一点耐心", "给面试复习打气"),
            list("明确今日优先级", "给自己一点正反馈", "规划关键产出")),
    QIU_SI("求嗣",
            list("孵化一个新想法", "培养一个好习惯", "开始一个小尝试"),
            list("开一个 side project", "写一个 demo", "尝试一个新技术点"),
            list("推进一个新方案", "培养新人", "沉淀一个可复用模板")),
    SHANG_CE("上册受封",
            list("申请机会", "争取资源", "提交重要材料"),
            list("投递实习", "提交竞赛材料", "申请项目名额"),
            list("申请晋升材料", "争取项目资源", "汇报阶段成果")),
    SHANG_GUAN("上官赴任",
            list("开始新任务", "承担新角色", "主动负责一件事"),
            list("接手新模块", "担任小组负责人", "开始实习任务"),
            list("接手新项目", "推进岗位职责", "承接关键任务")),
    JIE_HUN("结婚",
            list("认真沟通关系", "确定重要约定", "约重要的人见面"),
            list("找队友同步进度", "确定组队安排", "沟通合作边界"),
            list("对齐合作目标", "确认协作关系", "推进关键沟通")),
    JIA_QU("嫁娶",
            list("认真沟通关系", "确定重要约定", "约重要的人见面"),
            list("找队友同步进度", "确定组队安排", "沟通合作边界"),
            list("对齐合作目标", "确认协作关系", "推进关键沟通")),
    NA_CAI("纳采",
            list("发起邀约", "确认合作意向", "沟通初步计划"),
            list("约同学组队", "找导师沟通", "确认课程安排"),
            list("约评审沟通", "同步需求意向", "确认合作窗口")),
    WEN_MING("问名",
            list("了解对方需求", "确认基本信息", "做背景调研"),
            list("调研目标院校/岗位", "查技术文档", "确认题目要求"),
            list("做需求澄清", "查业务背景", "确认关键人信息")),
    NA_CAI_ALT("纳财",
            list("记账存钱", "检查预算", "处理账单"),
            list("规划生活费", "整理订阅服务", "检查云服务花费"),
            list("处理报销", "检查预算", "评估投入产出")),
    KAI_SHI("开市",
            list("发布作品", "开启新计划", "开始经营副业"),
            list("上线 demo", "发布博客", "开源小项目"),
            list("上线功能", "发布版本", "推进商业化动作")),
    LI_QUAN("立券",
            list("确认约定", "签下计划", "记录承诺"),
            list("确认项目分工", "写好任务清单", "提交报名表"),
            list("确认合同条款", "敲定需求范围", "输出会议纪要")),
    JIAO_YI("交易",
            list("买必要物品", "交换资源", "处理订单"),
            list("购买课程/书籍", "处理设备采购", "交换学习资料"),
            list("处理采购", "确认供应商", "对齐资源置换")),
    KAI_CANG("开仓",
            list("整理物资", "补充库存", "备好资料"),
            list("整理资料库", "补齐开发环境", "备份代码仓库"),
            list("整理项目资产", "补齐文档模板", "更新知识库")),
    CHU_XING("出行",
            list("出门办事", "短途散心", "线下见面"),
            list("去图书馆自习", "线下面试", "参加技术活动"),
            list("外出拜访", "线下会议", "客户沟通")),
    RU_XUE("入学",
            list("学习新章节", "刷题", "整理课堂笔记"),
            list("刷算法题", "看技术课", "补计算机基础"),
            list("学习业务知识", "看技术文档", "补齐能力短板")),
    XI_YI("习艺",
            list("练一项技能", "做刻意练习", "看教程实操"),
            list("练代码", "刷 LeetCode", "做项目实战"),
            list("练表达", "练方案设计", "提升工具熟练度")),
    FU_XI("赴任",
            list("开始新任务", "承担新角色", "主动负责一件事"),
            list("接手新模块", "开始实习任务", "参加项目例会"),
            list("接手新项目", "推进岗位职责", "承接关键任务")),
    DONG_TU("动土",
            list("改造房间", "重整计划", "动手解决老问题"),
            list("重构代码", "搭建项目脚手架", "调整开发环境"),
            list("重构模块", "优化流程", "启动基础建设")),
    XING_ZAO("兴造",
            list("搭建新空间", "制作作品", "改造工具流"),
            list("搭建项目", "写作品集", "做课程设计"),
            list("建设系统能力", "搭建看板", "制作业务工具")),
    XIU_ZAO("修造",
            list("修东西", "整理房间", "优化生活动线"),
            list("修 bug", "重构代码", "整理项目结构"),
            list("修线上问题", "优化流程", "整理项目文档")),
    SAO_SHE("扫舍",
            list("整理房间", "清理桌面", "删除无用文件"),
            list("清理下载目录", "整理桌面和 IDE", "清理无效依赖"),
            list("清理邮箱", "整理工位", "归档历史文件")),
    AN_CHUANG("安床",
            list("早点睡", "整理卧室", "调整作息"),
            list("早点睡别通宵", "调好作息再刷题", "休息眼睛"),
            list("保证睡眠", "恢复精力", "减少无效加班")),
    YI_XI("移徙",
            list("调整环境", "换个学习地点", "迁移资料"),
            list("迁移代码仓库", "换自习位置", "调整开发环境"),
            list("调整办公节奏", "迁移文档", "搬迁项目资料")),
    AN_ZANG("安葬",
            list("结束旧计划", "归档旧资料", "放下烂尾事项"),
            list("归档废弃项目", "关闭过期 issue", "删除无效分支"),
            list("关闭历史任务", "归档旧项目", "终止低价值事项")),
    PO_TU("破土",
            list("打破旧习惯", "开始清理积压问题", "处理难题"),
            list("拆掉旧代码", "处理历史技术债", "重开困难题"),
            list("处理历史包袱", "推进棘手问题", "拆解复杂需求")),
    QI_ZUAN("启钻",
            list("重新打开旧问题", "深挖一个卡点", "复盘被搁置的事"),
            list("重看错题", "定位深层 bug", "重新分析旧项目"),
            list("复盘旧需求", "重启搁置项目", "深挖问题根因")),
    KAI_GUANG("开光",
            list("开启新工具", "点亮新技能", "让计划正式启动"),
            list("配置新 IDE", "学习新框架", "启动 demo"),
            list("启用新系统", "试运行新流程", "上线新工具")),
    ZAI_ZHONG("栽种",
            list("培养习惯", "长期学习打卡", "开始积累"),
            list("开始长期刷题", "养成写博客习惯", "积累代码片段"),
            list("沉淀方法论", "培养团队习惯", "搭建长期机制")),
    NA_CHU("纳畜",
            list("管理宠物/物品", "补充生活物资", "照顾日常琐事"),
            list("管理设备", "补齐学习材料", "维护开发工具"),
            list("维护资产", "补齐办公用品", "照顾团队协作细节")),
    MU_YU("沐浴",
            list("洗澡放松", "运动出汗", "清爽开局"),
            list("洗个澡再学习", "清理脑子再写代码", "运动缓解久坐"),
            list("清爽开会", "散步回血", "整理状态再工作")),
    JIE_CHU("解除",
            list("取消无效安排", "卸载干扰应用", "摆脱拖延"),
            list("卸载短视频", "解除环境报错", "取消无效 TODO"),
            list("解除阻塞", "取消低价值会议", "清掉卡点")),
    HUAI_YI("坏垣",
            list("拆掉旧限制", "清理坏习惯", "处理过期规则"),
            list("删掉废代码", "拆掉错误抽象", "清理过期配置"),
            list("废除低效流程", "清理历史规则", "拆掉无效看板"));

    private final String traditionalName;
    private final Map<AudienceTypeEnum, List<String>> mappings;

    TraditionalAlmanacMappingEnum(String traditionalName,
                                  List<String> general,
                                  List<String> csStudent,
                                  List<String> workplace) {
        this.traditionalName = traditionalName;
        this.mappings = new EnumMap<>(AudienceTypeEnum.class);
        this.mappings.put(AudienceTypeEnum.GENERAL, general);
        this.mappings.put(AudienceTypeEnum.CS_STUDENT, csStudent);
        this.mappings.put(AudienceTypeEnum.WORKPLACE, workplace);
    }

    public static List<String> candidates(String traditionalName, AudienceTypeEnum audienceType) {
        for (TraditionalAlmanacMappingEnum item : values()) {
            if (item.traditionalName.equals(traditionalName)) {
                return item.mappings.getOrDefault(audienceType, item.mappings.get(AudienceTypeEnum.GENERAL));
            }
        }
        return Collections.emptyList();
    }

    public static List<String> mixedCandidates(String traditionalName) {
        for (TraditionalAlmanacMappingEnum item : values()) {
            if (item.traditionalName.equals(traditionalName)) {
                List<String> result = new java.util.ArrayList<>();
                result.addAll(item.mappings.get(AudienceTypeEnum.CS_STUDENT));
                result.addAll(item.mappings.get(AudienceTypeEnum.WORKPLACE));
                result.addAll(item.mappings.get(AudienceTypeEnum.GENERAL));
                return result;
            }
        }
        return Collections.emptyList();
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }
}
