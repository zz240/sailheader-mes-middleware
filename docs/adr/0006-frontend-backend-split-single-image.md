# ADR-0006: 前后端分离（代码层面）与单镜像交付

- 状态：已接受
- 日期：2026-08-18

## 背景

UI 长期是单文件 `spike/src/main/resources/static/index.html`（原生 JS，已数百行），后续要新增死信查看/重投、模板管理、站点与 API Key 等页面。单文件原生 JS 不利于维护，脱离主流技术栈导致招人、上手与迭代都变慢。

## 决策

1. **代码分离**：新建独立前端工程 `frontend/`，使用 Vue 3 + TypeScript + Vite + Element Plus，组件化开发；后端 REST API 不变，前端只消费现有接口。
2. **开发期**：Vite dev server 代理 `/api` 到后端 8080，热更新，后端无需重启。
3. **部署期**：Docker **多阶段构建**——阶段 1 用 node 构建前端产物（dist），阶段 2 把 dist 拷贝进 Spring Boot 镜像；单镜像、同源、无 CORS，docker-compose 增加 app 服务（MySQL/RabbitMQ 不变）。
4. **迁移完成即移除旧 UI**：现有 index.html 全部功能（数据源、任务/动作、字段映射编辑器、执行结果、结构探测）迁移到新前端后删除旧文件，不留两套 UI。

## 结果

获得主流技术栈的组件化、类型安全与快速迭代；交付模型保持"单实例、同源、无 CORS"，边缘部署复杂度不变。一次性成本约 3~5 个工作日，且当前（spike 阶段、API 已稳定）是拆分成本最低的时机。
