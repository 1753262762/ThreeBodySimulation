# Simulation Health 项目进度

更新时间:2026-08-13

## 当前状态

Simulation Health(数值健康诊断)主体功能已实现,已覆盖 Core、Application、REST、WebSocket、前端 UI、离线 Mock 和相关测试.

当前实现将 `Experiment.id` 作为 Run ID.Health 由后端统一判定,前端只负责展示,不再维护独立的数值健康阈值.

## 已完成

- 新增 Run 级 `SimulationHealthReport` 及相关状态、阈值、原因、失败信息、趋势、近遇和建议类型.
- 新增权威 `SimulationHealthAnalyzer`:
  - 计算 Energy Health Drift 和 Angular Momentum Drift.
  - 保存 current、sampled peak、峰值步数和模拟时间.
  - 支持 `GOOD`、`WARNING`、`POOR`、`FAILED` 四种状态.
  - 状态按 sampled peak 判定,回落后不会自动降级.
  - 保留最近 8 个有限样本并生成趋势结果.
  - 关联漂移越级附近的近遇事件.
  - 生成去重的 `REDUCE_TIME_STEP` / `CLONE_AND_RETRY` 建议.
- 扩展积分器数值失败信息,包含 `bodyId`、`field`、`step`、`simulationTimeSeconds` 和 `value`.
- 派生 Metrics 出现非有限值时立即终止 Run,并生成结构化 FAILED Health.
- Health 已接入 Experiment 生命周期和本地 JSON 持久化.
- 新增 latest-wins WebSocket `HEALTH` 消息,不修改现有 SNAPSHOT 协议.
- REST 实验详情和报告返回完整 Health;实验列表返回可空 `healthStatus`.
- 更新 OpenAPI、WebSocket Schema 和示例,并重新生成前端契约类型.
- Pinia 支持 REST 全量 Health 覆盖和 WebSocket 实时增量更新.
- 新增可展开的 Simulation Health 卡,展示:
  - 状态、current/peak drift 和趋势.
  - 最近距离、近遇次数及采样范围.
  - 实际阈值、原因、失败详情和建议.
  - 回放模式下的 Run 级诊断范围提示.
- 队列增加 Health badge,报告页复用 Health 卡.
- 实现 Clone & Retry:复制源 Run 配置、应用建议 dt、打开参数编辑器,源 Run 保持不变.
- 移除 Lab 和 Queue 中的 RESTART 操作入口;兼容 REST API 暂时保留.
- 删除前端旧 `conservation.ts` 数值判级,消除前端、后端和 mock 的多套阈值.
- 离线 mock 增加隔离的 Health test double,并补齐 Metrics WebSocket 的 step/time 字段.

## 验证结果

### 已通过

- `mvn -pl simulation-web -am test`
  - Core:28 项通过.
  - Application:77 项通过.
  - Web:15 项通过.
  - 合计:120 项通过,0 项失败.
- Health 前端定向测试:22/22 通过.
- Vue TypeScript 类型检查通过.
- `npm run build` 通过.
- `git diff --check` 通过.
- 契约类型生成完成.

### 已知限制

- 全量前端单测为 151/153:两个既有 Canvas/WebGL 测试受当前 Node/jsdom 环境影响.
- E2E 为 11/12:剩余 tooltip 用例假设固定画布中心必定命中天体.
- `mvn verify` 已完成 Core、Application、Web 和 Swing,Launcher 执行 `npm ci` 时受 Windows `spawn EPERM` 中止;随后已恢复依赖并单独完成前端生产构建.
- 项目没有 lint 脚本,因此 lint 未运行.
- 前端生产构建存在既有的大 chunk 警告,不影响构建成功.

## 后续建议

1. 更新三个 E2E 流程,使创建实验时正确处理 Warning 确认弹窗.
2. 在支持当前依赖版本的 Node 环境中重新运行完整前端单测.
3. 停止占用 launcher JAR 的本地 Java 进程后重新执行 `mvn clean verify`.
4. 增加专门的 Health/Clone & Retry E2E,覆盖新 Run ID、源 Run 保留和建议 dt 预填.
5. 后续如引入 Compare 页面,可直接复用列表中的 `healthStatus` 和完整 Health 报告.

## 工作区说明

本轮没有覆盖或回退工作区中原有的 README、docs、配置文件及 `frontend/public/mockServiceWorker.js` 改动;只创建 WIP 提交,不创建分支或推送.
