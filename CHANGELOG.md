# 开发日志

## 2026-05-29

### 上午

- **项目初始化** — 搭建 Spring Boot 空项目骨架，创建前后端目录结构
- **数据库设计**
  - 完成 `yl_user` 用户表 DDL
  - 完成 `yl_todo` 待办表 DDL
  - 完成 `yl_ai_dialogue` AI 对话记录表 DDL
  - 写入初始测试数据
- **依赖管理** — 引入 MyBatis-Plus、MySQL、Druid、Knife4j、Lombok、P6Spy

### 下午

- **领域模型**
  - 创建 `domain/user` 实体 + DTO（登录/注册）+ VO（用户信息/登录响应）
  - 创建 `domain/event` 实体 + DTO（保存/查询）+ VO
  - 创建 `domain/chat` 实体 + DTO（对话请求）+ VO（对话响应）
  - 创建 `ResponseDTO` 统一响应体、`ErrorCode` 错误码枚举
- **基础组件**
  - 创建 `@NoNeedLogin`、`@CheckPhone` 注解
  - 创建 `PhoneValidator` 校验器
  - 创建 `BusinessException` 业务异常类
  - 创建 `GlobalExceptionHandler` 全局异常处理
- **认证体系** — 引入 Sa-Token，创建 `ClientTokenConfig` 客户端 Token 配置、`SaTokenUtil` Token 工具类、`LoginInterceptor` 登录拦截器、`LoginUserContext` 用户上下文、`ResponseUtil` 响应工具
- **Spring Boot 版本** — 确定使用 3.5.4，调整 Lombok 编译配置，移除 SB 3.0 不存在的 `NoResourceFoundException`

### 晚上

- **后端-用户模块**
  - 完成注册接口（手机号唯一校验 + BCrypt 密码加密 + 自增 userCode）
  - 完成登录接口（密码校验 + 状态检查 + Token 发放）
  - 完成获取用户信息接口
  - 完成修改用户信息接口
  - 完成退出登录接口
- **后端-基础设施**
  - 添加 `spring-security-crypto` 依赖用于 BCrypt 密码加密
  - 创建 `StartupListener` 启动成功后打印接口文档地址
  - 创建 `TestController` 提供测试连接、测试登录、获取测试 Token 接口
- **前端-项目搭建**
  - Vue 3 + TypeScript + Vite 8 + Vue Router 4 项目初始化
  - 登录/注册页面 UI（表单切换、样式完成）
  - 主页三栏布局：左侧待办清单 + 中间日历 + 右侧语音助手
  - NavBar 导航栏（面板折叠/展开 + 退出登录按钮）
  - 日历组件（月视图网格、月份选择器、日期点击选中、行内编辑面板）
  - ChatPanel 语音助手面板（对话气泡 + 按住说话麦克风按钮占位）
  - TodoList 待办清单面板（可折叠，当前为静态示例数据）
  - 全局复古纸张主题设计（统一的配色和边框风格）
- **文档** — 编写 README.md 和 CHANGELOG.md

---

## 待完成

- [ ] 待办模块后端 CRUD
- [ ] 前端对接后端登录/注册 API
- [ ] 前端对接后端待办 CRUD API
- [ ] Spring AI 集成 + 意图识别
- [ ] 前端浏览器麦克风录音（STT）
- [ ] 前端 TTS 朗读播放
- [ ] 前后端联调
