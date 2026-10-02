# Vue 3 + TypeScript + Vite

## Python AI 接口

开发环境的 `/chat`、`/rag` 已代理到 `http://localhost:8001`；其他业务接口仍代理到 Java 8080。

聊天 API 与 SSE 使用 `VITE_AI_API_BASE_URL`，默认留空走同源代理。部署时可以配置 Python 服务的公开地址，或由网关将 `/chat`、`/rag` 转发到 Python。`VITE_API_BASE_URL` 继续用于 Java 业务接口。

历史记录默认每页 20 条消息，支持“加载更早的消息”；会话按 `lastMessageTime` 排序。`/chat/latest` 是 RouteAgent 上下文读取接口，聊天页面不调用它。

验证：`npm run build` 检查类型并构建；`npm run test:chat` 使用 Node 的测试运行器验证 Python SSE 协议（已在 Node 24 验证）。

This template should help get you started developing with Vue 3 and TypeScript in Vite. The template uses Vue 3 `<script setup>` SFCs, check out the [script setup docs](https://v3.vuejs.org/api/sfc-script-setup.html#sfc-script-setup) to learn more.

Learn more about the recommended Project Setup and IDE Support in the [Vue Docs TypeScript Guide](https://vuejs.org/guide/typescript/overview.html#project-setup).
