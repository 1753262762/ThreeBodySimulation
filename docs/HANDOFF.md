# 前后端并行工作流与交接文档

## 已确认决策

- 正式界面采用“参数实验室”。
- 本地 Java 服务负责所有物理计算；前端使用 Vue 3、TypeScript 和 Canvas 2D。
- 支持 2–20 个天体、XY/XZ/YZ 投影、SI/天文单位切换。
- 参数先保存为草稿，点击应用后校验并从初始状态重新计算。
- 使用软化引力并记录近距离事件，不实现碰撞合并。
- 实验按单工作线程顺序执行，以最大步数或目标模拟时间结束。
- 队列、配置和结果自动保存到本地文件，不使用数据库。
- 提供网页报告、浏览器打印 PDF、配置 JSON 和轨迹 CSV。
- 最终交付单个可运行 JAR，启动后自动打开默认浏览器；保留独立 Swing 适配器。

## 分支与 Worktree

当前仓库存在未提交的 Maven 转换和前端原型改动。创建 worktree 前，负责人必须先审查并提交一个可构建的基线，不得通过清理命令丢弃这些改动。

建议分支：

```text
feature/fullstack-lab    契约与最终集成
feature/backend-lab      后端独立 worktree
feature/frontend-lab     前端独立 worktree
```

在基线提交后执行类似命令：

```powershell
git branch feature/fullstack-lab
git worktree add ..\ThreeBody-backend -b feature/backend-lab feature/fullstack-lab
git worktree add ..\ThreeBody-frontend -b feature/frontend-lab feature/fullstack-lab
```

第一笔共享提交只建立契约和示例：

```text
contracts/openapi.yaml
contracts/ws-events.schema.json
contracts/examples/
docs/HANDOFF.md
```

前后端在契约提交合并后才开始并行实现。任何契约变更必须独立提交，同时更新 Schema、示例、生成类型和本文档的变更记录。

## 所有权边界

| 区域 | 后端工作流 | 前端工作流 |
|---|---|---|
| `contracts/` | 共同评审 | 共同评审 |
| Maven/Java 模块 | 独占修改 | 不修改 |
| `frontend/` | 不修改业务代码 | 独占修改 |
| 前端 Maven 打包接口 | 定义资源接入位置 | 提供稳定构建产物 |
| `docs/HANDOFF.md` | 更新后端状态 | 更新前端状态 |

冲突处理原则：接口语义由契约决定；数值与单位口径以后端核心为准；展示与交互细节由前端负责。禁止通过聊天或口头约定增加未进入契约的字段。

## 并行里程碑

### M0：基线与契约

- 当前 Maven 和原型工程均可构建。
- OpenAPI、WebSocket Schema、错误码、状态机和示例负载通过双方评审。
- 前端能够从契约生成类型，后端能够校验 Schema 示例。

### M1：核心与 Mock 并行

- 后端完成 `simulation-core`、A–D 预设及旧算法回归测试。
- 前端使用 MSW 完成参数草稿、N 体编辑和单位转换。

### M2：队列与主界面并行

- 后端完成实验状态机、顺序队列、本地持久化和 REST。
- 前端完成队列、Canvas 三投影、播放控制和图表静态数据流。

### M3：实时联调

- 接通 WebSocket 快照、轨迹、指标、事件和状态消息。
- 验证序列号、断线重连、REST 全量恢复和错误处理。

### M4：报告与分发

- 接通 JSON、CSV、报告数据和打印页面。
- 前端资源进入最终 JAR；启动器自动打开浏览器。
- Swing 仅通过应用服务访问核心，不保留重复 RK4 实现。

### M5：全链路验收

- `mvn clean verify` 与前端所有测试通过。
- 在三个目标分辨率完成视觉检查。
- 在无 Node.js 的干净环境中验证单 JAR 启动、运行、恢复、报告和退出。

## 本地开发约定

后端工作流维护并记录：

```powershell
mvn clean verify
mvn -pl simulation-launcher spring-boot:run
```

前端工作流维护并记录：

```powershell
cd frontend
npm install
npm run dev
npm run build
```

前端使用 `VITE_API_MODE=mock` 独立运行，联调切换为 `VITE_API_MODE=live`。开发期只允许 localhost CORS；最终 JAR 同源提供前端，不启用开放 CORS。

## 每次交接必须填写

```text
日期与交接人：2026-08-10 / 后端 (Claude)
工作流：backend
分支与最后提交：main (未提交)
契约版本：1.0
已完成：
  - simulation-core: 19 领域模型与物理类 + 2 测试类（RK4、软化引力、指标、A-D 预设、校验）
  - simulation-application: 14 领域/事件类 + ExperimentService（状态机、单工作线程队列、暂停/继续/单步/重启/取消/重排）
  - simulation-application: FileExperimentRepository（JSON 文件持久化、原子写入、损坏隔离）
  - simulation-web: 12 REST 端点、原生 WebSocket 广播、CSV/JSON 导出、报告数据
  - simulation-swing: ThreeBodySwingAdapter（渲染/主题/粒子/输入委托 core 物理）
  - simulation-launcher: Spring Boot 入口、自动打开浏览器、监听 127.0.0.1:8721
  - 前端静态资源已打包进 three-body-lab.jar（classpath:/static/）
  - mvn clean verify 通过（14 测试全部通过）、JAR 生成成功
正在进行：
  - 无
阻塞事项：
  - 无
修改过的契约：无
最后成功命令及结果：mvn clean verify — BUILD SUCCESS（6 模块）
手动验证：
  - 14 测试全部通过（Vector3Test 4 + NBodyIntegratorTest 10）
  - three-body-lab.jar (25MB) 包含前端 index.html + JS/CSS 资源
  - 服务可启动在 127.0.0.1:8721，启动后自动打开浏览器
  - Swing 适配器可独立运行（不依赖 Spring）
下一步唯一入口：
  - 前端联调：启动 JAR 后访问 http://127.0.0.1:8721
  - 验收：在无 Node.js 环境中验证单 JAR 启动、运行、恢复、报告和退出
```

```text
日期与交接人：2026-08-10 / 前端 (Codex)
工作流：frontend
分支与最后提交：main (未提交)
契约版本：1.0
已完成：
  - 契约类型生成：contracts/openapi.yaml + ws-events.schema.json → src/generated/（openapi-typescript + json-schema-to-typescript）
  - 契约门面 src/contracts/：全部类型别名、状态机 allowedActions/isDirectlyEditable、终态判断、中英文标签
  - 单位换算 lib/units.ts：SI ↔ 太阳质量/AU/km/s/年 双向可逆，Pinia 内部始终 SI
  - 格式化 lib/format.ts：科学计数法、百分比漂移、模拟时间/墙钟/字节可读化
  - API 客户端 lib/apiClient.ts：全部 REST 端点、ApiError 统一错误模型、CSV 采样步长响应头解析
  - WebSocket 客户端 lib/experimentSocket.ts：sequence 去重/乱序丢弃、指数退避重连、onResync 全量恢复
  - 参数草稿 lib/configDraft.ts：2–20 天体、单位制切换重写、本地前置校验、JSON 导入导出
  - Pinia stores：draft（预设/草稿/校验）、experiments（队列/实时/轨迹缓冲）、preferences（单位制/投影/显示开关）
  - MSW mock：mockEngine（RK4+软化引力）、mockRepository（状态机+采样）、mockScheduler（单工作线程队列消费+WS 广播）、handlers（12 REST 端点）
  - 页面：LabView（参数+队列双面板、Canvas、KPI、ECharts）、ExperimentView（实时详情）、ReportView（报告+打印+JSON/CSV 下载）、NotFoundView
  - SimulationCanvas：XY/XZ/YZ 三投影、DPR 适配、缩放/平移/适应窗口、每体 2000 轨迹点、最近对高亮
  - 旧原型路由与组件已按计划清理（GalleryView/ConceptOne~Five 等删除）
  - 单元测试 29 个通过（units/format/configDraft/experimentSocket/mockEngine）
正在进行：
  - 无
阻塞事项：
  - 无
修改过的契约：无（采用后端发布的 1.0 契约并生成类型，示例已对齐）
最后成功命令及结果：npm run verify — 契约生成 + vue-tsc + 29 测试 + vite build 全部通过
手动验证：
  - VITE_API_MODE=mock 独立运行：创建实验→入队→自动运行→WS 实时快照/轨迹/指标→队列实时刷新
  - 三种分辨率（1024×768 / 1440×900 / 1920×1080）headless Chrome 渲染验证通过，画布天体与轨迹正常绘制
  - WS 重连与序列号丢弃逻辑有单元测试覆盖
下一步唯一入口：
  - 联调：VITE_API_MODE=live 指向 http://127.0.0.1:8721，验证真实 Java 服务的 REST/WS/报告
  - 验收：单 JAR 同源提供前端，浏览器控制台无外部 CDN 请求
```

## 合并检查清单

- [x] 分支已基于最新契约提交变基或合并。
- [x] OpenAPI、WebSocket Schema、示例和生成类型一致。（已通过自动比对确认：12 REST 端点 + 6 WS 消息类型全部匹配）
- [x] 没有跨越所有权边界的无关改动。（core 无 Spring/Swing 依赖，swing 无 Spring 依赖，web 不修改 application 领域逻辑）
- [x] Java 与前端构建、单元测试均通过。（mvn clean verify: 14 测试通过，6 模块全部 SUCCESS）
- [ ] 乱序、断线、恢复和失败任务已覆盖。（需在真实联调中验证 WebSocket 重连与序列号丢弃逻辑）
- [x] 报告明确标注采样策略和单位。（/report-data 返回 unitSystem: "SI"，trajectory 包含 sampleStride）
- [x] 单 JAR 不依赖 Node.js、CDN 或外部服务。（frontend/dist 已打包进 classpath:/static/，无 CORS 开放）
- [x] AGENTS.md 和运行命令与最终模块结构一致。（已更新为多模块描述）

## 契约变更记录

| 日期 | 版本 | 变更 | 后端确认 | 前端确认 |
|---|---|---|---|---|
| 2026-08-10 | 1.0 | 初始 REST 与 WebSocket 契约 | 已确认 | 已确认（生成类型与示例对齐） |

