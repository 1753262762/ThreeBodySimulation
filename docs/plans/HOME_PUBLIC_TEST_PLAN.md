# Windows 个人电脑临时公网测试实施计划

## 方案摘要

采用“Docker Compose + Cloudflare Quick Tunnel”：

```text
公网浏览器 HTTPS / WSS
        ↓
随机 trycloudflare.com 地址
        ↓ 出站隧道，无需开放路由器端口
cloudflared 容器
        ↓ Docker 内部网络
Three Body Lab 容器 :8721
        ↓
独立临时 Docker 数据卷
```

该方案适用于短时间演示和验收，不作为长期正式部署。Quick Tunnel 地址每次启动都会变化，不提供稳定域名或可用性保证，并存在 200 个并发请求限制。参见 [Cloudflare Quick Tunnel 文档](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/)。

## 涉及模块与文件

预计新增或修改：

- `docker-compose.public-test.yml`
  - 独立定义应用、Tunnel、内部网络和临时数据卷。
- `scripts/public-test.ps1`
  - 提供 `Start`、`Status`、`Logs`、`Stop` 操作。
- `README.md`
  - 增加临时公网测试入口、风险警告和关闭方法。

复用模块：

- `simulation-launcher`：现有 Spring Boot 可执行 JAR。
- `simulation-web`：继续同时承载前端静态文件、REST API 和 WebSocket。
- `simulation-application`：继续使用本地 JSON/JSONL 持久化。
- `frontend`：继续使用同源相对地址访问 REST 和 WebSocket。

不修改 Java API、OpenAPI、WebSocket Schema、前端请求代码和现有正式数据格式。

## 实现设计

### 1. 公网测试专用 Compose

新增独立 Compose 文件，固定项目名为 `threebody-public-test`，避免影响现有 `docker-compose.yml`。

`app` 服务：

- 复用当前 `Dockerfile` 和 `three-body-lab:local` 镜像。
- 容器内设置 `SERVER_ADDRESS=0.0.0.0`。
- 将容器 `8721` 仅映射到宿主机 `127.0.0.1:18721`，用于本机健康检查。
- 使用专用卷 `public-test-data` 挂载到 `/home/threebody/.threebody-lab`。
- 不读取现有 `threebody-data`，不暴露用户已有实验数据。

`tunnel` 服务：

- 使用 `cloudflare/cloudflared:latest`。
- 通过 Docker 内部网络访问 `http://app:8721`。
- 不映射任何宿主机端口。
- 创建无需账号和 Token 的 Quick Tunnel。
- 从日志中取得随机 `https://*.trycloudflare.com` 地址。

无需配置路由器端口转发、UPnP、Windows 入站防火墙规则或公网 IP。

### 2. 手动控制脚本

`scripts/public-test.ps1` 提供以下操作。

#### `Start`

- 检查 Docker CLI 和 Docker Engine。
- 若 Docker Desktop 未运行，只提示用户启动，不自动修改系统配置。
- 构建并启动专用 Compose 项目。
- 轮询 `http://127.0.0.1:18721/api/v1/presets`，确认应用就绪。
- 最多等待 60 秒，从 Tunnel 日志提取公网地址。
- 输出公网地址及“持有地址者拥有完整实验创建、运行和删除权限”的醒目警告。
- 启动失败时打印相关日志，并清理本次创建的临时项目和数据卷。

#### `Status`

- 显示两个容器的运行状态。
- 检查本地 REST 健康状态。
- 从日志重新输出当前公网地址。

#### `Logs`

- 跟踪应用和 Tunnel 日志。

#### `Stop`

- 明确显示将删除的 Compose 项目和临时卷。
- 默认要求确认；`-Force` 可跳过确认。
- 执行针对固定项目名的 `down -v --remove-orphans`。
- 不触碰现有 `threebody-data` 卷。

脚本结束后容器继续运行，直到用户执行 `Stop`、关闭 Docker Desktop、电脑休眠或关机。

## 数据流变化

- 浏览器从随机 HTTPS 地址加载 Spring Boot 内置的 Vue 静态资源。
- REST 请求仍访问同源 `/api/v1`，不产生 CORS 改造。
- WebSocket 通过同一域名升级为 WSS，再由 Tunnel 转发到 `/ws/v1/experiments/{id}`。
- Cloudflare 支持代理 WebSocket；连接被网络或代理关闭时，沿用现有前端重连和 REST 状态同步能力。参见 [Cloudflare WebSocket 文档](https://developers.cloudflare.com/network/websockets/)。
- 实验配置、归档和报告只写入 `public-test-data`，停止服务后随卷一起删除。

## 分步实施与验证

### 1. 新增公网测试 Compose 文件

- 运行 `docker compose -p threebody-public-test -f docker-compose.public-test.yml config`。
- 确认只绑定 `127.0.0.1:18721`，Tunnel 无宿主机端口。

### 2. 实现脚本的 `Start` 和就绪检测

- 验证 Docker 未运行、镜像构建失败、应用启动超时和 Tunnel 启动失败的错误提示。
- 确认失败不会留下仍可访问的孤立 Tunnel。

### 3. 实现 `Status`、`Logs` 和安全停止

- 确认所有操作都使用固定 Compose 项目名。
- 验证 `Stop` 只删除临时项目及其专用卷。

### 4. 进行本机验证

- 访问首页和 `/api/v1/presets`。
- 创建、启动、暂停和删除一个测试实验。
- 确认 WebSocket 实时事件正常到达。

### 5. 进行真实公网验证

- 使用关闭 Wi-Fi 的手机蜂窝网络访问随机地址。
- 验证前端加载、REST 操作、实时轨迹、报告页面和 WebSocket 重连。
- 连续运行实验至少 15 分钟，并进行一次网络切换或短暂断网恢复测试。

### 6. 验证数据隔离与关闭行为

- 确认公网测试中看不到原有实验。
- 停止后确认公网地址失效、临时卷已删除。
- 再次启动应获得新地址和空白实验数据。
- 确认原有 `threebody-data` 及本地数据未改变。

### 7. 文档和差异检查

- 补充启动、查看状态、日志、关闭以及安全警告。
- 执行 PowerShell 语法检查、`git diff --check` 和 `git status --short`。
- 不运行与本次部署脚本无关的 Java/前端全量测试；若 Docker 镜像重新构建，则以镜像构建和运行时冒烟测试作为集成验证。

## 风险与默认约束

- 没有域名，因此不能使用 Cloudflare Access；公网地址完全没有身份认证。
- 获得地址的人可以调用全部 REST 和 WebSocket 能力，包括创建、运行和删除实验。
- 地址只应私下分享，并在验收结束后立即执行 `Stop`。
- Quick Tunnel 适合临时测试，不适合长期服务、固定书签或对外正式发布。
- Docker Desktop、电脑供电和网络必须保持正常；测试期间应避免休眠。
- 网络切换可能中断 WebSocket；验收标准是现有客户端能够自动恢复，而不是连接永不中断。
- 本方案不开放家庭路由器端口，降低直接暴露 Windows 主机的风险。
- 如果后续需要固定域名、登录保护和长期在线，应升级为命名 Cloudflare Tunnel + Cloudflare Access，或迁移至云服务器；不继续扩展 Quick Tunnel。
