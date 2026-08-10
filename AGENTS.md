# 仓库指南

## 项目结构与模块组织

本仓库是一个使用 Maven 构建的 Java 项目,包含五个子模块:

```text
simulation-core          领域模型、RK4 积分、指标和物理预设(纯 Java,无 Spring/Swing)
simulation-application   实验队列、状态机、采样和文件持久化(依赖 core)
simulation-web           REST、WebSocket、报告数据和静态资源(依赖 application + Spring Boot)
simulation-swing         旧 Swing 界面适配器(依赖 core)
simulation-launcher      程序入口及最终可执行 JAR(依赖 web + swing)
```

旧单体文件 `src/main/java/com/threebody/ThreeBodySimulation.java` 已被模块化重构取代.测试放在各模块的 `src/test/java/` 下,所有视觉元素均由代码生成.不要提交 `target/`、`.class`、`.jar` 等构建产物.

## 构建、测试与开发命令

请在仓库根目录运行以下命令,并确保已安装 JDK 17 或更高版本和 Maven:

```powershell
mvn clean verify
mvn -pl simulation-launcher spring-boot:run
mvn package
```

`mvn clean verify` 清理、编译并运行全量测试,`mvn -pl simulation-launcher spring-boot:run` 启动 Spring Boot 服务(含 Web 界面与 REST API),`mvn package` 在 `simulation-launcher/target/` 生成 `three-body-lab.jar`.单模块开发可使用 `-pl` 指定模块名.

前端视觉原型位于 `frontend/`,使用 Vue 3、TypeScript 和 Vite.进入该目录后运行:

```powershell
npm install
npm run dev
npm run build
```

`npm run dev` 启动本地预览,`npm run build` 执行类型检查并生成 `frontend/dist/`.不要提交 `node_modules/` 或 `dist/`.

## 编码风格与命名规范

使用四个空格缩进,并遵循 Java 常用的大括号格式.类和枚举使用 `PascalCase`,方法和字段使用 `camelCase`,常量使用 `UPPER_SNAKE_CASE`.质量、距离、速度和时间步长等物理量应在名称或相邻注释中注明单位.保留 UTF-8 编码,避免破坏现有中文注释.修改物理计算、渲染或控制逻辑时,优先拆分为职责单一的辅助方法;Swing 界面操作应在事件分派线程中执行.本项目暂未配置格式化或静态检查工具,请保持与现有代码风格一致,并清理未使用的导入.

## 测试指南

项目已配置 JUnit 5,但目前没有自动化测试或覆盖率要求.每次修改都必须通过 `mvn test`,并进行针对性的手动验证.根据改动范围检查:程序启动、暂停与继续、A-D 初始方案、1-4 主题切换、轨迹控制、缩放和平移,以及 F11/ESC 全屏操作.修改物理算法时,应确认长时间运行后坐标仍为有限值,且运动保持稳定.测试放在 `src/test/java/com/threebody` 下,并采用 `ThreeBodySimulationTest` 等命名.

## 提交与拉取请求规范

近期提交采用简短、聚焦单一改动的中文说明,例如 `优化拖拽代码逻辑`.请延续这种简洁的祈使式风格,避免在一次提交中混入无关改动.拉取请求应说明行为变化、列出验证步骤并关联相关 Issue.涉及界面、主题或交互的修改应附截图或短视频;调整模拟常量或算法时,应明确说明原因及预期影响.
