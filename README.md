# 三体参数实验室(Three Body Lab)

一个面向本地使用的 N 体引力模拟与参数实验平台.项目以 RK4 积分器为核心,提供实验队列、运行状态控制、轨迹持久化、实时 WebSocket 数据、可视化分析和报告导出,并保留旧版 Swing 界面的适配模块.

## 功能特性

- 基于经典万有引力模型和四阶 Runge-Kutta(RK4)方法进行 N 体数值积分.
- 提供多种初始条件预设,并支持质量、位置、速度、时间步长等参数编辑与校验.
- 支持实验排队、启动、暂停、继续、单步、重启、取消和删除.
- 通过 WebSocket 实时推送模拟状态、轨迹和指标.
- 提供三视图 Canvas 轨迹展示、指标图表和实验报告.
- 将实验清单和轨迹保存到本地,服务重启后可恢复实验状态.
- 同时提供 Vue Web 界面和旧版 Swing 适配模块.

## 项目结构

```text
simulation-core          领域模型、RK4 积分、指标和物理预设(纯 Java)
simulation-application   实验队列、状态机、采样和文件持久化
simulation-web           REST API、WebSocket、报告数据和静态资源
simulation-swing         旧 Swing 界面适配器
simulation-launcher      Spring Boot 入口与最终可执行 JAR
frontend                 Vue 3 + TypeScript + Vite 前端
contracts                OpenAPI、WebSocket Schema 和示例数据
```

模块依赖方向如下:

```text
simulation-core
       ↓
simulation-application
       ↓
simulation-web ──────┐
                     ├─→ simulation-launcher
simulation-swing ────┘
```

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本
- Node.js 20 或更高版本(构建前端和最终 JAR 时需要)
- npm 10 或更高版本

## 快速开始

在仓库根目录构建完整项目:

```powershell
mvn clean verify
```

构建完成后运行可执行 JAR:

```powershell
java -jar simulation-launcher/target/three-body-lab.jar
```

服务默认仅监听本机 `127.0.0.1:8721`,启动后访问:

```text
http://127.0.0.1:8721/
```

也可以在开发阶段直接启动 Spring Boot:

```powershell
mvn -pl simulation-launcher -am spring-boot:run
```

## 前端开发

```powershell
cd frontend
npm install
npm run dev
```

Vite 开发服务器会把 `/api` 和 `/ws` 请求代理到本地 Java 服务.生产构建:

```powershell
npm run build
```

完整前端验证(契约生成、类型检查、单元测试、生产构建和 Playwright 端到端测试):

```powershell
npm run verify
```

## 测试

运行全部 Java 测试:

```powershell
mvn test
```

运行完整构建和验证:

```powershell
mvn clean verify
```

运行前端单元测试或端到端测试:

```powershell
cd frontend
npm test
npm run test:e2e
```

## 接口概览

REST API 统一使用 `/api/v1` 前缀,主要接口包括:

- `GET /api/v1/presets`:读取物理预设.
- `POST /api/v1/configs/validate`:校验实验参数.
- `GET /api/v1/experiments`:查询实验列表.
- `POST /api/v1/experiments`:创建实验.
- `GET /api/v1/experiments/{id}`:读取实验详情.
- `POST /api/v1/experiments/{id}/actions`:执行启动、暂停、继续、单步、重启或取消操作.
- `GET /api/v1/experiments/{id}/report-data`:读取报告数据.
- `GET /api/v1/experiments/{id}/exports/config`:导出实验配置.
- `GET /api/v1/experiments/{id}/exports/trajectory`:导出轨迹数据.

实时事件通过以下 WebSocket 地址推送:

```text
ws://127.0.0.1:8721/ws/v1/experiments/{id}
```

详细契约见 [`contracts/openapi.yaml`](contracts/openapi.yaml) 和 [`contracts/ws-events.schema.json`](contracts/ws-events.schema.json).

## 本地数据

实验清单和轨迹默认保存在:

- Windows:`%LOCALAPPDATA%/ThreeBodyLab`
- 其他系统:`${user.home}/.threebody-lab`

损坏的实验清单会被隔离到数据目录下的 `.corrupted/`,避免阻止服务启动.

## 构建产物

完整打包命令:

```powershell
mvn package
```

最终可执行文件位于:

```text
simulation-launcher/target/three-body-lab.jar
```

Maven 打包 `simulation-launcher` 时会自动执行 `npm ci` 和前端生产构建,并把静态资源嵌入 JAR.

## 贡献

提交改动前请至少运行与改动范围对应的测试.涉及物理算法时,应额外确认长时间运行后坐标仍为有限值且模拟保持稳定;涉及界面或交互时,请验证实验创建、队列操作、暂停/继续、单步、报告和实时连接状态.

请勿提交 `target/`、`node_modules/`、`dist/`、IDE 配置或其他本地构建产物.
