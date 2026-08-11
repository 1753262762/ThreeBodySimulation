# 三体参数实验室（Three Body Lab）

本地 N 体引力模拟与参数实验平台。以四阶 Runge-Kutta（RK4）积分器为核心，提供实验队列、运行状态控制、轨迹持久化、实时 WebSocket 数据推送、可视化分析和报告导出功能。同时提供 Vue 3 Web 界面和旧版 Swing 桌面界面。

## 功能特性

- **N 体物理模拟**：基于经典万有引力模型和软化引力（Plummer 软化），使用 RK4 方法进行数值积分，支持 2–20 个天体。
- **内置预设**：提供 A–G 七组初始条件预设（A–D 为经典三体/多体场景），可直接加载并修改。
- **参数编辑与校验**：支持质量、位置、速度、时间步长、引力常数、软化长度等参数的编辑，前端和后端双重校验。
- **实验队列管理**：支持实验创建、排队、启动、暂停、继续、单步执行、重启、取消和删除，可拖拽重排队列顺序。
- **实验状态机**：QUEUED → RUNNING → PAUSED（可恢复/单步）→ COMPLETED / CANCELLED / FAILED，状态转换由后端严格控制。
- **实时数据推送**：通过 WebSocket 实时推送模拟快照（60 Hz）、轨迹增量（60 Hz）和物理指标（2 Hz），支持序列号去重和断线重连。
- **三视图轨迹展示**：基于 Canvas 2D 的 XY / XZ / YZ 三投影视图，支持缩放、平移和自适应窗口，每体保留 8000 个实时轨迹点。
- **物理指标监控**：实时计算动能、势能、总能量、能量漂移、角动量、线动量、最近两体距离等指标，通过 ECharts 图表展示。
- **实验报告**：提供报告页面，包含轨道图、指标趋势图、事件时间线，支持浏览器打印 PDF。
- **数据导出**：支持导出实验配置（JSON）和分层采样轨迹数据（CSV），CSV 包含 step / 时间 / 天体 ID / 名称 / 位置 / 速度。
- **本地持久化**：实验清单和轨迹数据自动保存到本地文件系统，服务重启后可恢复，损坏文件自动隔离。
- **Swing 桌面界面**：保留旧版 Swing 适配器，可独立运行，物理计算委托给 simulation-core。

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **后端框架** | Spring Boot（WebMVC + WebSocket） | 4.0.7 |
| **编程语言** | Java | 17 |
| **构建工具** | Maven | 3.9+ |
| **物理引擎** | 纯 Java 实现（RK4 + 软化引力） | — |
| **序列化** | Jackson（JSON） | 由 Spring Boot 管理 |
| **前端框架** | Vue 3 + TypeScript | 3.5.13 / ~5.7.2 |
| **构建工具** | Vite | 6.0 |
| **状态管理** | Pinia | 4.0 |
| **路由** | Vue Router | 4.5 |
| **图表** | ECharts | 6.1 |
| **Canvas 渲染** | 原生 Canvas 2D API | — |
| **单元测试（后端）** | JUnit 5 | 5.11.4 |
| **单元测试（前端）** | Vitest + jsdom | 4.1 / 29.1 |
| **端到端测试** | Playwright | 1.62 |
| **Mock（前端）** | MSW | 2.15 |
| **契约类型生成** | openapi-typescript + json-schema-to-typescript | 7.5 / 15.0 |

项目不依赖外部数据库、缓存或消息队列。数据通过 Jackson 序列化为 JSON 文件存储在本地文件系统。

## 项目结构

```
ThreeBody/
├── simulation-core/            领域模型、RK4 积分、物理指标和预设（纯 Java，无框架依赖）
│   └── src/main/java/com/threebody/core/
│       ├── Vector3.java                三维向量
│       ├── NBodyIntegrator.java        RK4 积分器
│       ├── MetricsCalculator.java      物理指标计算
│       ├── ConfigValidator.java        配置校验
│       ├── Presets.java                A–G 预设定义
│       ├── SimulationConfig.java       模拟配置
│       └── BodySpec / BodyState / …    领域模型
│
├── simulation-application/     实验状态机、队列调度、采样策略和文件持久化
│   └── src/main/java/com/threebody/app/
│       ├── domain/                      实验、进度、轨迹、事件领域对象
│       ├── service/                     ExperimentService（核心业务）、ExperimentRepository 接口
│       ├── service/persistence/         FileExperimentRepository（JSON 文件持久化）
│       └── event/                       异步事件分发（供 WebSocket 广播）
│
├── simulation-web/             REST API、WebSocket 推送、导出端点和静态资源承载
│   └── src/main/java/com/threebody/web/
│       ├── controller/                  ExperimentController（12 个 REST 端点）
│       ├── websocket/                   ExperimentWebSocketHandler（原生 WebSocket）
│       ├── config/                      AppConfig、WebSocketConfig
│       └── dto/                         ApiError
│
├── simulation-swing/           旧版 Swing 桌面界面适配器（依赖 core，不依赖 Spring）
│   └── src/main/java/com/threebody/swing/
│       └── ThreeBodySwingAdapter.java
│
├── simulation-launcher/        Spring Boot 入口，打包最终可执行 JAR
│   └── src/main/java/com/threebody/launcher/
│       └── ThreeBodyLabApplication.java   主类，启动后自动打开浏览器
│
├── frontend/                   Vue 3 + TypeScript + Vite 前端
│   ├── src/
│   │   ├── views/              页面：LabView（主实验室）、ExperimentView（实验详情）、ReportView（报告）、NotFoundView
│   │   ├── components/         组件：SimulationCanvas、ParameterEditor、QueuePanel、KpiCards、MetricChart
│   │   ├── stores/             Pinia 状态：draft（草稿/校验）、experiments（队列/实时数据）、preferences（单位制/投影）
│   │   ├── lib/                工具：apiClient、experimentSocket、configDraft、units、format、snapshotBuffer 等
│   │   ├── mocks/              MSW Mock：mockEngine（RK4 物理模拟）、mockRepository（状态机）、mockScheduler（队列+WS）
│   │   ├── contracts/          契约门面（类型别名、状态机辅助、中文标签）
│   │   └── generated/          由契约自动生成的 TypeScript 类型（禁止手动修改）
│   ├── e2e/                    Playwright 端到端测试
│   └── scripts/                契约类型生成脚本
│
├── contracts/                  前后端共享契约
│   ├── openapi.yaml            REST API 契约（OpenAPI 3.0.3）
│   ├── ws-events.schema.json   WebSocket 消息 Schema（JSON Schema draft-07）
│   └── examples/               示例数据（预设、实验、校验结果、WS 事件、报告）
│
├── docs/
│   ├── HANDOFF.md              前后端交接文档与并行工作流记录
│   └── plans/                  开发计划文档
│
├── pom.xml                     父 POM（Maven 多模块）
└── AGENTS.md                   仓库开发指南（AI 辅助开发用）
```

**模块依赖方向：**

```
simulation-core
       ↓
simulation-application
       ↓
simulation-web ──────┐
                     ├─→ simulation-launcher（最终 JAR）
simulation-swing ────┘
```

## 环境要求

| 软件 | 最低版本 | 说明 |
|------|----------|------|
| JDK | 17 | 项目 `maven.compiler.release` 设置为 17 |
| Maven | 3.9+ | 多模块构建 |
| Node.js | 20+ | 前端构建和完整打包时需要 |
| npm | 10+ | 随 Node.js 20 提供 |

> **注意**：如果只运行已构建好的 JAR 文件（`three-body-lab.jar`），仅需 JDK 17，不需要 Node.js 和 Maven。

## 快速开始

### 1. 克隆项目

```bash
git clone <仓库地址>
cd ThreeBody
```

### 2. 完整构建

在仓库根目录执行：

```bash
mvn clean verify
```

此命令会依次：编译全部 Java 模块 → 运行后端单元测试 → 安装前端依赖（`npm ci`）→ 构建前端 → 将前端产物打包进 JAR。

### 3. 启动服务

```bash
java -jar simulation-launcher/target/three-body-lab.jar
```

服务启动后会自动打开默认浏览器访问 `http://127.0.0.1:8721`。

也可以使用 Maven 直接启动（开发模式）：

```bash
mvn -pl simulation-launcher -am spring-boot:run
```

### 4. 前端独立开发

如果需要前后端分离开发：

```bash
cd frontend
npm install
npm run dev          # 启动 Vite 开发服务器（端口 5173）
```

开发服务器会将 `/api` 和 `/ws` 请求代理到本地 Java 后端（`127.0.0.1:8721`）。

如果后端尚未就绪，可使用 Mock 模式独立运行前端：

```bash
# Windows PowerShell
$env:VITE_API_MODE="mock"
npm run dev

# Linux / macOS / Git Bash
VITE_API_MODE=mock npm run dev
```

Mock 模式在浏览器内运行完整的 RK4 物理引擎模拟、实验状态机和 WebSocket 广播，无需后端即可体验完整功能。

## 配置说明

### 后端配置

`simulation-launcher/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: three-body-lab

server:
  port: 8721          # 服务端口
  address: 127.0.0.1  # 仅监听本机，不对外暴露
```

服务仅绑定 `127.0.0.1`，不提供外部访问。如需修改端口或监听地址，编辑此文件后重新构建。

### 前端配置

`frontend/vite.config.ts` 中的关键配置：

- **开发服务器端口**：`5173`
- **API 代理**：`/api` → `http://127.0.0.1:8721`
- **WebSocket 代理**：`/ws` → `ws://127.0.0.1:8721`
- **Mock 模式**：通过环境变量 `VITE_API_MODE=mock` 启用，不依赖后端

### 本地数据目录

实验清单和轨迹数据默认保存在：

| 平台 | 路径 |
|------|------|
| Windows | `%LOCALAPPDATA%\ThreeBodyLab` |
| Linux / macOS | `~/.threebody-lab` |

数据目录结构：

```
ThreeBodyLab/
├── experiments.json       # 实验清单
├── <experiment-id>.json   # 各实验的完整数据（状态、事件、轨迹元信息）
├── <experiment-id>.csv    # 分层采样轨迹（长表格式）
└── .corrupted/            # 损坏文件的隔离目录
```

损坏的实验清单会被自动移动到 `.corrupted/` 目录，避免阻止服务启动。

## API 概览

REST API 统一使用 `/api/v1` 前缀，全部端点见 [`contracts/openapi.yaml`](contracts/openapi.yaml)。

### 主要模块

| 模块 | 端点 | 说明 |
|------|------|------|
| **预设** | `GET /api/v1/presets` | 获取 A–G 内置预设 |
| **校验** | `POST /api/v1/configs/validate` | 校验模拟参数（始终返回 200，通过 `valid` 字段判断） |
| **实验 CRUD** | `GET/POST /api/v1/experiments` | 列出 / 创建实验 |
| | `GET/PUT/DELETE /api/v1/experiments/{id}` | 查看 / 编辑 / 删除实验 |
| **实验控制** | `POST /api/v1/experiments/{id}/actions` | 执行 PAUSE / RESUME / STEP / RESTART / CANCEL |
| **队列** | `PATCH /api/v1/queue` | 重排实验队列 |
| **导出** | `GET /api/v1/experiments/{id}/exports/config` | 导出配置 JSON |
| | `GET /api/v1/experiments/{id}/exports/trajectory` | 导出轨迹 CSV（含 X-Sample-Stride 响应头） |
| **报告** | `GET /api/v1/experiments/{id}/report-data` | 获取报告聚合数据 |

### WebSocket

```
ws://127.0.0.1:8721/ws/v1/experiments/{id}
```

推送消息类型：

| 类型 | 频率 | 说明 |
|------|------|------|
| `SNAPSHOT` | 最高 60 Hz | 当前步天体位置和速度 |
| `TRAJECTORY` | 最高 60 Hz | 新增轨迹采样点（增量） |
| `METRICS` | 2 Hz | 物理守恒量指标 |
| `STATUS` | 按事件 | 实验状态变更 |
| `NEAR_ENCOUNTER` | 按事件 | 两体近距离事件（< 5 倍软化长度） |
| `ERROR` | 按事件 | 数值异常或内部错误 |

每条消息包含单调递增的 `sequence` 字段，客户端据此丢弃重复和乱序消息。重连后应先调用 `GET /api/v1/experiments/{id}` 获取全量状态，再接收 `sequence` 更大的增量消息。

### 数值单位

接口和 WebSocket 中的数值统一使用 **SI 单位**：

| 物理量 | 单位 |
|--------|------|
| 质量 | kg |
| 长度 / 位置 | m |
| 速度 | m/s |
| 时间 | s |
| 能量 | J |

前端提供 SI ↔ 太阳质量 / AU / km/s / 年 的双向单位转换，但接口层始终使用 SI。

## 开发说明

### 后端开发

对单个模块运行测试：

```bash
mvn -pl simulation-core test
mvn -pl simulation-application test
```

运行全部测试：

```bash
mvn test
```

### 前端开发

```bash
cd frontend

# 类型检查
npx vue-tsc -b

# 单元测试
npm test

# 单元测试（监听模式）
npm run test:watch

# E2E 测试（需要先启动 dev server）
npm run test:e2e

# 完整验证（契约生成 + 类型检查 + 单元测试 + 构建 + E2E）
npm run verify
```

> **E2E 测试说明**：Playwright 配置在 `playwright.config.ts` 中，使用独立端口 4173，测试期间强制 Mock 模式（`VITE_API_MODE=mock`），不需要后端服务。

### 契约变更

修改 `contracts/` 下的文件后，重新生成前端类型：

```bash
cd frontend
npm run generate:contracts
```

此命令读取 `contracts/openapi.yaml` 和 `contracts/ws-events.schema.json`，生成 `frontend/src/generated/openapi.ts` 和 `frontend/src/generated/ws-events.ts`。

### 前后端联调

1. 启动后端：`mvn -pl simulation-launcher -am spring-boot:run`
2. 启动前端（Live 模式）：`cd frontend && npm run dev`
3. 访问 `http://localhost:5173`，API 请求自动代理到后端

### 跨域

开发期 Vite 通过代理避免跨域。生产环境前端静态资源打包在 JAR 内，与后端同源，不存在跨域问题。后端不启用开放 CORS。

### 数据持久化

- 使用 Jackson JSON 序列化到本地文件，不依赖外部数据库。
- 写入采用原子操作（先写临时文件再重命名）。
- 实验状态变更、事件追加、轨迹采样均实时写入。
- 损坏的文件被隔离到 `.corrupted/` 目录，不影响其他实验。

## 测试

| 层级 | 工具 | 位置 | 数量 |
|------|------|------|------|
| Java 单元测试 | JUnit 5 | 各模块 `src/test/java/` | 12 个测试文件 |
| 前端单元测试 | Vitest | `frontend/src/**/__tests__/` | 13 个测试文件 |
| 端到端测试 | Playwright | `frontend/e2e/` | 1 个测试文件 |

运行全部测试：

```bash
# 后端
mvn test

# 前端
cd frontend && npm test && npm run test:e2e
```

## 构建与部署

### 构建可执行 JAR

```bash
mvn clean package
```

产物路径：`simulation-launcher/target/three-body-lab.jar`

Maven 打包 `simulation-launcher` 时会自动执行：
1. `npm ci` — 安装前端依赖
2. `npm run build` — 构建前端（类型检查 + Vite 生产构建）
3. 复制 `frontend/dist/` 到 `classpath:/static/`
4. Spring Boot repackage — 生成包含所有依赖和前端资源的 fat JAR

### 运行

```bash
java -jar simulation-launcher/target/three-body-lab.jar
```

服务启动后自动打开浏览器访问 `http://127.0.0.1:8721`。

### 运行环境

- **开发 / 构建**：需要 JDK 17 + Maven 3.9+ + Node.js 20+
- **仅运行 JAR**：只需 JDK 17
- **不需要**：数据库、Redis、Nginx、Docker 或任何外部服务

本项目当前未配置 Docker 部署方案。

## 常见问题

### 1. 启动 JAR 后浏览器没有自动打开

服务仅监听 `127.0.0.1:8721`，手动访问 `http://127.0.0.1:8721` 即可。如果在无桌面环境的服务器上运行，`java.awt.Desktop` 不可用，自动打开浏览器功能会静默失败。

### 2. 前端 `npm run dev` 后 API 请求报 404

确认后端服务已在 `127.0.0.1:8721` 启动。或者使用 Mock 模式：`VITE_API_MODE=mock npm run dev`。

### 3. Maven 构建失败，提示找不到 Node.js 或 npm

`simulation-launcher` 模块构建时会调用 `npm ci` 和 `npm run build`，需要 Node.js 20+。如果只想构建 Java 模块（不打包前端），可以跳过 launcher：

```bash
mvn -pl simulation-core,simulation-application,simulation-web,simulation-swing clean verify
```

### 4. 实验数据丢失

检查本地数据目录（Windows：`%LOCALAPPDATA%\ThreeBodyLab`，其他系统：`~/.threebody-lab`）。如果 `experiments.json` 损坏，它会被移到 `.corrupted/` 子目录，可以从该目录尝试手动恢复。

### 5. WebSocket 连接频繁断开又重连

WebSocket 客户端实现了指数退避重连策略。如果持续断开，检查是否有防火墙或代理拦截 WebSocket 流量。开发模式下确认 Vite 代理配置中 `/ws` 的 target 为 `ws://127.0.0.1:8721`。

## License

暂未指定 License。
