# Three Body Lab Coding Agent Instructions

## Project Overview

Three Body Lab 是一个本地 N 体模拟参数实验室。后端负责物理计算、实验编排、持久化以及 REST/WebSocket 接口；前端负责参数编辑、实时可视化、报告与离线 mock 模式；Swing 模块仅保留旧界面适配能力。

本文件用于约束在本仓库中工作的 Coding Agent。它不是项目使用手册。所有修改应以当前任务为边界，以实际代码、测试和配置为事实来源；文档与代码冲突时先核实代码，再修正文档或实现。

## Tech Stack

- Java 17、Maven 多模块、JUnit 5。
- Spring Boot 4，REST 与原生 WebSocket 位于 `simulation-web`。
- Vue 3、TypeScript、Vite、Pinia、Vue Router、ECharts。
- Vitest 用于前端单元测试，Playwright 用于端到端测试，MSW 支持 mock 模式。
- OpenAPI 与 JSON Schema 是前后端共享契约；本项目不使用数据库。

不要根据版本记忆推断 API。涉及框架行为、插件配置或依赖能力时，先查看对应 `pom.xml`、`package.json` 或现有实现。

## Repository Structure

```text
contracts/                  OpenAPI 与 WebSocket 事件契约，前后端共享事实来源
frontend/                   Vue 3 + TypeScript 前端、mock 运行时、Vitest 与 Playwright 测试
simulation-core/            领域模型、软化引力、RK4 积分、指标与物理预设
simulation-application/     实验状态机、顺序队列、事件分发、采样与文件持久化
simulation-web/             REST、WebSocket、DTO、报告与静态资源承载
simulation-swing/           旧 Swing UI 适配器，物理计算委托给 core
simulation-launcher/        Spring Boot 入口与最终可执行 JAR 打包
```

各 Java 模块的生产代码和测试分别位于 `src/main/java/` 与 `src/test/java/`。旧单体文件 `src/main/java/com/threebody/ThreeBodySimulation.java` 已被模块化实现取代，不要引用、修改或恢复它。

## Development Commands

除特别说明外，从仓库根目录运行命令。

```powershell
# 全量 Java 构建、测试和打包；launcher 构建阶段会执行 npm ci 与前端生产构建
mvn clean verify

# 按模块运行相关测试；-am 同时构建上游依赖
mvn -pl simulation-core -am test
mvn -pl simulation-application -am test
mvn -pl simulation-web -am test

# 启动本地后端和打包最终 JAR
mvn -pl simulation-launcher -am spring-boot:run
mvn package
java -jar simulation-launcher/target/three-body-lab.jar
```

前端命令在 `frontend/` 中运行：

```powershell
npm ci
npm run dev
npm test
npm run build
npm run test:e2e
npm run generate:contracts
npm run verify
```

`npm run build` 包含 TypeScript 类型检查和 Vite 构建。`npm run verify` 依次生成契约类型、执行类型检查、单元测试、生产构建和 E2E。当前没有独立 lint 脚本，不要声称已运行 lint，也不要虚构命令。

## Architecture Guidelines

- 允许的模块依赖链是 `launcher -> web -> application -> core` 与 `launcher -> swing -> core`。下层模块不得反向依赖上层，也不得为了方便跨层调用。
- `simulation-core` 必须保持纯 Java：不得引入 Spring、Swing、AWT 或文件系统访问。所有后端物理量使用 SI 单位（kg、m、m/s、s、J）。
- `simulation-application` 只承担用例编排、状态、事件和持久化，不依赖 Spring、Swing 或 AWT。HTTP/WebSocket 适配属于 `simulation-web`。
- `simulation-swing` 只负责旧界面的输入和渲染；Swing UI 操作必须在 Event Dispatch Thread 上执行。
- `contracts/openapi.yaml` 与 `contracts/ws-events.schema.json` 是共享协议的事实来源。契约变化后在 `frontend/` 中运行 `npm run generate:contracts`；业务代码只从 `frontend/src/contracts/index.ts` 门面导入类型，不直接导入或手工编辑 `frontend/src/generated/`。
- API、WebSocket 与状态中的数值保持 SI 单位；显示单位转换集中在前端单位工具中，不要把显示单位泄漏到协议或后端领域模型。
- 实验队列按单 worker 顺序执行。修改 `ExperimentService`、`Experiment`、事件分发、WebSocket mailbox、文件仓库或归档写入时，必须先理解其线程所有权、锁、顺序性、背压与关闭语义，不要用无同步的共享可变状态破坏现有并发模型。
- 实时快照类事件采用“最新值优先”，状态、近距离事件和错误采用可靠有序传递；不要在未理解丢弃策略和序列号重连协议前统一队列实现。
- 持久化是本地 JSON/JSONL 文件而非数据库。保留原子写入、损坏文件隔离和启动恢复语义；不要在物理步进热路径中增加同步磁盘 I/O。

## Coding Guidelines

- 修改前先阅读目标代码、直接调用者、直接被调用者和相关测试，理解相关调用链后再动手；不要无差别扫描仓库。
- 优先复用邻近代码的抽象、命名、错误处理、测试组织和并发模式。除非任务要求，不引入新的架构层、工具或依赖。
- 采用能完整解决问题的最小修改。不要顺手重构、重命名、格式化或清理任务无关代码。
- Java 使用 4 空格缩进；类、枚举和 record 使用 `PascalCase`，方法和字段使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。清理本次修改产生的未使用 import。
- 物理量在字段/变量名或相邻注释中标明单位，例如 `massKg`、`timeStepSeconds`。物理计算优先使用职责单一、可测试的辅助方法。
- TypeScript/Vue 遵循当前文件风格和已有 store、composable、组件及测试模式；不要引入第二套状态管理、请求或渲染方案。
- 保持 UTF-8，不破坏现有中文注释。注释解释约束或原因，不复述代码。
- 修复缺陷时优先添加能复现问题的回归测试；新增行为应覆盖正常路径以及与风险相称的边界/失败路径。

## Change Rules

- 只修改完成当前任务所必需的文件。尊重工作区已有改动，不覆盖、不回退、不混入用户的无关修改。
- 未经任务明确要求，不修改公共 API、OpenAPI/WS Schema、持久化格式、默认配置、端口、依赖版本或构建插件。确需修改时，先说明兼容性和迁移影响，并同步所有生产者、消费者、生成代码和测试。
- 不随意添加依赖。先检查 JDK、现有依赖和项目工具能否满足需求；新增依赖必须有明确必要性，并更新正确的模块配置和锁文件。
- 不手工编辑生成产物，也不提交 `target/`、`dist/`、`node_modules/`、`frontend/.visual/`、`frontend/test-results/`、`*.class`、`*.jar`、IDE 配置或临时文件。
- 修改模拟常量、积分算法、采样频率、队列容量、轨迹上限或单位转换时，必须说明原因与预期影响，并验证数值稳定性、内存/吞吐或协议兼容性中的相关部分。
- 不删除或弱化现有测试来让构建通过。若测试与需求冲突，先确认需求和实际契约，再更新实现与测试并解释行为变化。

## Verification

验证必须与改动范围匹配，并优先从最相关、最快的检查开始：

- Java 单模块修改：运行对应模块测试，通常使用 `mvn -pl <module> -am test`。
- 跨模块、依赖、打包或静态资源修改：运行 `mvn clean verify`。
- 前端逻辑或组件修改：运行相关 Vitest；至少执行 `npm test` 和 `npm run build`。
- 用户流程、路由、WebSocket/mock 行为修改：在相关单元测试和构建之外运行 `npm run test:e2e`；需要完整前端检查时运行 `npm run verify`。
- 契约修改：重新生成类型，检查生成 diff，并验证后端测试、前端类型检查/测试以及所有契约消费者。
- 物理算法修改：除自动化测试外，确认长时间或边界输入下坐标、速度、能量等关键值保持有限，并检查预设行为没有意外退化。
- Swing 交互修改：进行针对性的手动验证，并覆盖受影响的 EDT、暂停/继续、主题、轨迹、缩放/平移或全屏行为。
- 纯文档修改通常不需要运行代码测试，但必须检查内容与当前配置/代码一致，并检查最终 diff。

完成前必须运行 `git diff --check`，审阅 `git diff -- AGENTS.md` 或本次任务涉及的全部文件，并用 `git status --short` 确认没有意外产物或越界修改。若某项验证因环境、时间或缺少依赖无法执行，明确列出“未运行”的命令和原因；不要猜测、暗示或宣称通过。

## Git Guidelines

- 除非用户明确要求，不创建分支、不提交、不推送，也不改写历史。
- 每个提交只包含一个聚焦改动；提交前再次确认 diff 和验证结果。
- 提交信息沿用仓库风格：简短、祈使式中文说明，例如 `修复实验状态同步`。
- PR 说明应包含行为变化、验证步骤和相关 Issue。UI/交互修改附截图或短视频；模拟常量或算法修改写明依据和预期影响。

## Agent Workflow

1. 确认任务边界、允许修改的文件以及明确的非目标；查看 `git status --short`，识别已有改动。
2. 只定位与任务相关的入口、调用链、配置和测试。先理解现有实现模式与模块边界，不扫描生成目录或依赖目录。
3. 制定最小改动方案；若方案需要改变公共契约、配置、依赖或持久化格式，先停下来说明影响并取得明确授权。
4. 实施聚焦修改，同时补充或更新直接相关测试；不处理顺手发现但与任务无关的问题，可在结果中单独指出。
5. 按风险从局部到整体执行测试、类型检查和构建。失败时区分本次修改、既有问题与环境限制，不以猜测掩盖失败。
6. 审阅最终 diff，确认没有调试代码、无关格式化、生成产物、敏感信息或越界文件。
7. 汇报修改内容、关键设计决定、实际运行的验证及结果，并明确任何未验证项和剩余风险。
