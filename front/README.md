# Vue 3 + TypeScript + Vite

## Python AI 接口

开发环境的 `/chat`、`/rag` 已代理到 `http://localhost:8001`；其他业务接口仍代理到 Java 8080。

聊天 API 与 SSE 使用 `VITE_AI_API_BASE_URL`，默认留空走同源代理。部署时可以配置 Python 服务的公开地址，或由网关将 `/chat`、`/rag` 转发到 Python。`VITE_API_BASE_URL` 继续用于 Java 业务接口。

历史记录默认每页 20 条消息，支持“加载更早的消息”；会话按 `lastMessageTime` 排序。`/chat/latest` 是 RouteAgent 上下文读取接口，聊天页面不调用它。

规划交互从 SSE `result.planning` 获取状态，刷新、切换会话和撤回后通过 `GET /chat/state?sessionId=...` 恢复。`awaiting_answers` 展示服务端返回的 1～3 个问题、日期区间和引用文章数量，隐藏普通聊天输入框；逐项回答后合并为普通聊天消息继续规划。取消规划或草稿生成后恢复普通输入框。`draft_ready` 才开放同步与生图操作，发送及状态恢复期间禁用按钮。页面不通过回复文案判断是否已生成草稿。

前端需与支持 `planning` 字段和 `/chat/state` 的 Python 服务一起部署。状态接口沿用登录校验，只返回当前用户会话的交互摘要，不返回偏好、知识片段和日程快照。读取失败可重试，同一组问题保留未提交的表单回答，切换会话清空回答。

验证：`npm run build` 检查类型并构建；`npm run test:chat` 使用 Node 的测试运行器验证 Python SSE 协议（已在 Node 24 验证）。

This template should help get you started developing with Vue 3 and TypeScript in Vite. The template uses Vue 3 `<script setup>` SFCs, check out the [script setup docs](https://v3.vuejs.org/api/sfc-script-setup.html#sfc-script-setup) to learn more.

Learn more about the recommended Project Setup and IDE Support in the [Vue Docs TypeScript Guide](https://vuejs.org/guide/typescript/overview.html#project-setup).
