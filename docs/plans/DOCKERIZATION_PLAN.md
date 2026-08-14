# Three Body Lab 最小 Docker 化计划

## 状态

- 方案：已确认
- 实施：已完成
- 验证：已通过

## 已确认决策

- 使用单个 `app` 容器承载 Vue 静态资源、REST API 和 WebSocket。
- 不使用 Nginx，不拆分前后端，不改变项目目录和模块依赖。
- 宿主机仅开放 `127.0.0.1:8721:8721`。
- 使用 Docker named volume `threebody-data` 持久化实验与轨迹数据。
- 容器内部通过 `SERVER_ADDRESS=0.0.0.0` 监听，不修改现有 `application.yml`。
- 保持 REST `/api/v1` 和 WebSocket `/ws/v1` 的同源地址策略。
- 不修改业务逻辑、RK4、REST/OpenAPI、WebSocket Schema 或持久化格式。
- 本轮不处理 Vue history 深链接刷新问题。

## 实施步骤

1. 在仓库根目录增加多阶段 `Dockerfile`：Node 构建前端，Maven 打包 launcher，Java 17 JRE 以非 root 用户运行最终 JAR。
2. 增加 `docker-compose.yml`，配置本机端口映射、容器监听地址、named volume、重启策略和停止宽限期。
3. 增加 `.dockerignore`，排除依赖、构建产物、IDE 文件和本地缓存。
4. 验证 Maven 全量构建、Compose 配置、镜像构建、REST/WebSocket 连通性及容器重启后的数据恢复。
5. 审阅最终 diff 和工作区状态，记录未执行项及剩余风险。

## 验证记录

- `mvn clean verify`：通过。Java 各模块测试、前端类型检查与生产构建、最终 JAR 打包均成功。
- `docker compose config`：通过。端口仅绑定 `127.0.0.1:8721`，数据卷目标为 `/home/threebody/.threebody-lab`。
- `docker compose build`：通过。生成本地镜像 `three-body-lab:local`。
- 前端 `/` 与 REST `/api/v1/presets`：容器内运行时验证通过。
- WebSocket `/ws/v1/experiments/{id}`：返回 HTTP 101 协议升级。
- 容器用户：`uid=10001(threebody)`，非 root 运行验证通过。
- named volume：创建测试实验后重启容器，实验仍可读取，恢复验证通过。
- 隔离测试项目 `threebody-lab-test` 的容器、网络和测试卷已清理；本地镜像保留。

构建期间 npm 报告 3 个现有 high severity 依赖告警，Vite 报告一个超过 500 kB 的现有分块告警；两者均未导致构建失败，本次 Docker 化未调整依赖或前端分包策略。
