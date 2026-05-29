# 语音日历工具 (Voice Calendar)

基于语音交互的智能日历管理工具，支持通过语音添加/删除/查看待办事件。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3 + TypeScript |
| 后端 | Spring Boot 3.5 + Sa-Token + MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 连接池 | Druid |
| 缓存 | Redis |
| AI | Spring AI（待集成） |
| 密码加密 | BCrypt |
| 文档 | Knife4j (OpenAPI 3) |

---

## 后端

### 项目结构

```
back/src/main/java/com/qiniu/back/
├── annotation/              # 自定义注解
│   ├── NoNeedLogin.java     #   标记接口无需登录
│   └── CheckPhone.java      #   手机号格式校验
├── config/                  # 配置
│   ├── ClientTokenConfig.java  # Sa-Token 客户端 Token 配置
│   ├── MvcConfigure.java       # 拦截器注册
│   └── StartupListener.java    # 启动成功打印文档地址
├── domain/                  # 领域模型
│   ├── ErrorCode.java       #   统一错误码枚举
│   ├── ResponseDTO.java     #   统一响应体
│   ├── chat/                #   AI 对话
│   │   ├── AiDialogue.java          # 实体
│   │   ├── dto/ChatRequestDTO.java  # 请求 DTO
│   │   └── vo/ChatResponseVO.java   # 响应 VO
│   ├── event/               #   待办
│   │   ├── Todo.java                # 实体
│   │   ├── dto/TodoSaveDTO.java     # 保存 DTO
│   │   ├── dto/TodoQueryDTO.java    # 查询 DTO
│   │   └── vo/TodoVO.java           # 响应 VO
│   └── user/                #   用户
│       ├── User.java                # 实体
│       ├── dto/UserLoginDTO.java    # 登录 DTO
│       ├── dto/UserRegisterDTO.java # 注册 DTO
│       ├── vo/LoginVO.java          # 登录响应 VO
│       ├── vo/UserInfoVO.java       # 用户信息 VO
│       └── vo/UserVO.java           # 用户列表 VO
├── enumeration/             # 枚举
│   ├── DeletedFlagEnum.java #   删除状态
│   └── GenderEnum.java      #   性别
├── exception/
│   └── BusinessException.java  # 业务异常
├── handler/
│   ├── LoginInterceptor.java      # 登录拦截器（Token 校验 + 权限）
│   └── GlobalExceptionHandler.java  # 全局异常处理
├── module/
│   ├── TestController.java        # 测试接口
│   └── user/
│       ├── controller/UserController.java  # 用户接口
│       ├── mapper/UserMapper.java          # 用户 Mapper
│       └── service/
│           ├── UserService.java            # 用户服务接口
│           └── impl/UserServiceImpl.java   # 用户服务实现
├── util/
│   ├── SaTokenUtil.java       # Token 工具（生成/校验/解析/注销）
│   ├── LoginUserContext.java  # 当前用户上下文（ThreadLocal）
│   └── ResponseUtil.java      # HttpServletResponse 写 JSON
└── validator/
    └── PhoneValidator.java    # 手机号校验器
```

### 数据库表

| 表名 | 说明 |
|------|------|
| `yl_user` | 用户表 |
| `yl_todo` | 待办表 |
| `yl_ai_dialogue` | AI 对话记录表 |

### API 接口

#### 用户模块 `/user`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/user/register` | 注册 | 否 |
| POST | `/user/login` | 登录，返回 token | 否 |
| GET | `/user/info` | 获取当前用户信息 | 是 |
| PUT | `/user/info` | 修改用户信息 | 是 |
| POST | `/user/logout` | 退出登录 | 是 |

#### 测试模块 `/test`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/test/testConnection` | 测试连接 | 否 |
| GET | `/test/testLogin` | 测试登录状态 | 是 |
| GET | `/test/getToken?userId=1` | 获取测试 Token | 否 |

### 快速启动

```bash
# 1. 创建数据库并导入表结构和测试数据
mysql -u root -p < sql/tables.sql
mysql -u root -p < sql/insert_data.sql

# 2. 修改数据库/Redis 连接信息
#    编辑 back/src/main/resources/application-dev.yml

# 3. 启动后端
cd back
mvn spring-boot:run

# 4. 获取测试 Token
curl http://localhost:8080/test/getToken?userId=1
```

### 接口文档

启动后访问 **http://localhost:8080/doc.html** 查看 Knife4j 接口文档。

### 认证流程

1. 调用 `/user/login` 或 `/test/getToken` 获取 token
2. 后续请求 Header 携带 `yvli-token: <token值>`
3. `LoginInterceptor` 自动解析 token → 查询用户 → 存入 `LoginUserContext`
4. 业务代码通过 `LoginUserContext.getUser()` 获取当前用户
5. 登出时调用 `/user/logout`，销毁 token

### 开发进度

- [x] 项目骨架搭建 + 依赖管理
- [x] 数据库表设计（用户表 / 待办表 / 对话表）
- [x] 实体类 + DTO + VO
- [x] Sa-Token 认证 + 登录拦截器
- [x] 用户模块（注册 / 登录 / 信息 / 修改 / 登出）
- [ ] 待办模块 CRUD
- [ ] AI 对话 + Spring AI 集成
- [ ] 前后端联调
- [ ] 语音转文字（浏览器 STT）
- [ ] TTS 朗读播放

---

## 前端

### 项目结构

```
front/src/
├── App.vue                      # 根组件（全局样式重置）
├── main.ts                      # 入口（Vue + Router 挂载）
├── style.css                    # 全局样式
├── router/index.ts              # 路由（Login / Home）+ 路由守卫
├── components/
│   └── NavBar.vue               # 顶部导航栏（面板切换 + 退出）
└── views/
    ├── LoginView.vue            # 登录/注册页面
    └── HomeView/
        ├── HomeView.vue         # 主页布局（三栏 + 日历）
        └── components/
            ├── TodoList.vue     # 左侧待办清单面板
            └── ChatPanel.vue    # 右侧语音助手面板
```

### 技术栈

| 技术 | 版本 |
|------|------|
| Vue | 3.5 |
| TypeScript | 6.0 |
| Vite | 8.0 |
| Vue Router | 4.6 |

### 页面说明

- **登录/注册页** — 表单切换，UI 已完成，待对接 `/user/login` 和 `/user/register` 接口
- **主页** — 三栏布局：左待办 + 中日历 + 右语音助手，均可折叠/展开
- **日历** — 月视图网格、月份选择器、日期点击选中、行内编辑面板（保存/取消）
- **待办清单** — 左侧可折叠面板，当前为静态列表，待对接后端待办接口
- **语音助手** — 右侧可折叠面板，模拟对话气泡，麦克风按钮预留了 `startRecord/stopRecord` 方法

### 快速启动

```bash
cd front
npm install
npm run dev
```

### 开发进度

- [x] Vue 3 项目搭建 + 路由
- [x] 登录/注册页面 UI
- [x] 主页三栏布局 + 日历组件
- [x] 语音助手面板 UI
- [x] 待办清单面板 UI
- [ ] 对接后端登录/注册 API
- [ ] 对接后端待办 CRUD API
- [ ] 浏览器麦克风录音（STT）
- [ ] AI 对话对接 + TTS 播放
