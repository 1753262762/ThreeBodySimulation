# 后端工程交付计划

## 目标与技术基线

将现有 Swing 单体中的领域模型、RK4 计算、运行状态、轨迹和界面彻底分离，建设可供 Web 与 Swing 共同调用的本地 N 体模拟服务。采用 Java 17、Spring Boot 4.0.6、Springdoc OpenAPI 3.x 和 JUnit 5。最终由启动模块生成单个 `three-body-lab.jar`，仅监听 `127.0.0.1`，启动成功后自动打开默认浏览器。

## Maven 模块

```text
simulation-core          领域模型、RK4、指标和物理事件
simulation-application   实验队列、状态机、采样和文件持久化
simulation-web           REST、WebSocket、报告数据和静态资源
simulation-swing         旧 Swing 界面适配器
simulation-launcher      程序入口及最终可执行 JAR
```

依赖只能由外向内：`launcher/web/swing -> application -> core`。`simulation-core` 禁止依赖 Spring、Swing、AWT 和文件系统。

## 领域模型与数值规则

- `Vector3`：不可变三维向量。
- `BodySpec`：UUID、名称、颜色、质量、初始位置和速度。
- `SimulationConfig`：2–100 个天体、时间步长、引力常数、软化长度和结束条件。
- `SimulationState`：步数、模拟时间、天体当前状态和基准指标。
- `Experiment`：配置、队列状态、快照、指标、事件及轨迹摘要。
- 后端接口全部使用 SI：kg、m、m/s、s；禁止在核心层使用 AU 或太阳质量。
- 使用软化引力 `a ∝ r / (r² + ε²)^(3/2)`；距离不超过 `5ε` 时记录 `NEAR_ENCOUNTER`，但不中止或合并天体。
- 指标包括总能量、相对能量漂移、总角动量、最近天体距离和计算速率。

实验状态固定为 `QUEUED`、`RUNNING`、`PAUSED`、`COMPLETED`、`CANCELLED`、`FAILED`。非法状态转换返回 HTTP 409。

## 队列、采样与持久化

- 使用单工作线程顺序消费队列；同一时刻最多一个 `RUNNING` 实验。
- 创建实验时必须设置 `maxSteps` 或 `targetSimulationTimeSeconds`，也允许手动取消。
- 支持暂停、继续、单步、使用新配置重启、取消和队列重排。
- 应用重启后，原 `RUNNING` 实验恢复为 `PAUSED`，由用户确认后继续。
- Windows 数据目录为 `%LOCALAPPDATA%/ThreeBodyLab`，其他平台回退到 `${user.home}/.threebody-lab`。
- 使用临时文件加原子替换写入 JSON 清单；不引入数据库。
- 实时画布保留每个天体最近 2,000 点。归档最多保留 50,000 个采样点；达到上限后保留首尾及事件点，并将普通点采样步长翻倍。
- 已生成报告和结果文件不自动删除；提供显式删除接口并返回占用空间。

## REST 与 WebSocket 契约

OpenAPI 文件固定为 `contracts/openapi.yaml`，REST 基础路径为 `/api/v1`：

```text
GET  /presets
POST /configs/validate
GET  /experiments
POST /experiments
GET  /experiments/{id}
PUT  /experiments/{id}              # 仅 QUEUED 可直接编辑
DELETE /experiments/{id}
POST /experiments/{id}/actions
PATCH /queue                         # 提交完整有序实验 ID 列表
GET  /experiments/{id}/exports/config
GET  /experiments/{id}/exports/trajectory
GET  /experiments/{id}/report-data
```

动作请求为：

```json
{
  "action": "PAUSE|RESUME|STEP|RESTART|CANCEL",
  "config": null
}
```

`RESTART` 可携带新配置，清空旧状态并重新入队；其他动作的 `config` 必须为 `null`。

WebSocket 地址为 `/ws/v1/experiments/{id}`，消息由 `contracts/ws-events.schema.json` 约束：

```json
{
  "schemaVersion": "1.0",
  "type": "SNAPSHOT|METRICS|STATUS|NEAR_ENCOUNTER|ERROR",
  "experimentId": "uuid",
  "sequence": 1,
  "timestamp": "ISO-8601",
  "payload": {}
}
```

快照最高 30 Hz、轨迹增量 10 Hz、指标 2 Hz。客户端重连后先通过 REST 获取完整状态，再接收序列号更大的增量消息。

## 实施顺序

1. 冻结 OpenAPI、WebSocket Schema、错误码和示例负载。
2. 提取不可变领域模型、RK4 和 A–D 预设，建立旧实现回归基线。
3. 实现指标、软化引力、近距离事件和分层采样。
4. 实现实验状态机、顺序队列与本地文件仓库。
5. 实现 REST、原生 WebSocket、CSV/JSON 和报告数据接口。
6. 实现静态前端资源打包、浏览器自动打开和服务生命周期管理。
7. 将旧 Swing 改为 `simulation-core` 的独立适配器，删除其中重复物理逻辑。

## 测试与验收

- 单元测试：向量运算、RK4 确定性、对称配置、软化引力、能量与角动量。
- 状态测试：队列顺序、暂停恢复、单步、重启、取消及非法转换。
- 持久化测试：原子写入、损坏文件隔离、异常退出恢复和跨版本字段兼容。
- 边界测试：2/100 个天体、非正质量、NaN/Infinity、极端时间步长和近距离事件。
- 集成测试：OpenAPI 请求、WebSocket Schema、序列号、重连、JSON/CSV 和报告数据。
- 验收命令为 `mvn clean verify`；最终 JAR 必须能在无 Node.js 环境下启动并访问参数实验室。

