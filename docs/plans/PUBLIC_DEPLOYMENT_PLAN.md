# Three Body Lab 公网部署方案调研与实施计划

## 一、当前项目部署基础

当前代码已经具备低成本单实例上线的良好基础：

- `simulation-launcher` 生成可执行 `three-body-lab.jar`，并把 `frontend/dist` 打包到 `classpath:/static/`。
- REST 使用同源路径 `/api/v1`；WebSocket 使用 `/ws/v1/experiments/{id}`。
- 前端会根据页面协议自动选择 `ws://` 或 `wss://`，同源部署无需修改前端代码。
- 服务默认仅监听 `127.0.0.1:8721`；现有 Compose 通过 `SERVER_ADDRESS=0.0.0.0` 覆盖。
- 持久化是 `${user.home}/.threebody-lab` 下的 JSON/轨迹文件，不需要数据库。
- 已有多阶段 `Dockerfile` 和单容器 `docker-compose.yml`，并验证过数据卷恢复与 WebSocket 101 升级。
- 当前没有鉴权、限流、专用健康检查或公网 CORS 配置；文件仓库和单 worker 模型只适合单实例运行。

首发默认条件：

- 新加坡或香港节点，不涉及中国大陆 ICP 备案。
- 全站密码保护。
- 单实例、低并发，不引入数据库或用户系统。
- 推荐至少 `2 vCPU / 2 GB RAM / 40–60 GB SSD`；物理计算会持续占用 CPU，1 GB 内存只适合临时试运行。

## 二、方案对比

### 1. 单服务器直接部署

```text
浏览器
  │ HTTPS / WSS
  ▼
Nginx + Basic Auth
  ├── /              → frontend/dist
  ├── /api/v1/*      → 127.0.0.1:8721
  └── /ws/v1/*       → 127.0.0.1:8721 WebSocket Upgrade
                         │
                         ▼
                 Java Jar + 文件仓库
```

| 项目 | 方案 |
| --- | --- |
| 准备资源 | 新加坡/香港 Linux VPS、域名、固定公网 IP、SSH 密钥 |
| Docker | 不需要 |
| 进程管理 | 优先 `systemd`；不建议为单个 Java 服务额外引入 Supervisor |
| HTTPS | Nginx + Certbot/Let’s Encrypt；HTTP 强制跳转 HTTPS |
| WebSocket | Nginx 显式转发 `Upgrade`、`Connection`，提高 `proxy_read_timeout` |
| 持久化 | 创建独立系统用户，数据落在其稳定 home，例如 `/var/lib/threebody/.threebody-lab` |
| 日志 | Java stdout/stderr 进入 journald；Nginx access/error log 配合 logrotate |
| 难度 | 部署低，维护中 |
| 成本 | 低，2 GB 新加坡 VPS 通常约 US$12–24/月，另加域名和备份 |
| 首次上线 | 适合 |

优点：

- 组件最少，故障定位直接。
- 后端继续监听回环地址，8721 不暴露公网。
- 前端、REST、WebSocket 保持同源，不需要 CORS 改造。
- systemd 支持自动重启、启动顺序、资源限制与日志收集。

缺点：

- Java、Node 构建环境和 Nginx 均由服务器维护。
- 发布和回滚依赖脚本规范，否则容易出现旧静态资源与新 JAR 不一致。
- 服务器迁移、备份恢复和环境复现弱于容器方案。

预计修改：

- 不需要修改业务模块或协议。
- 新增部署用 Nginx 配置、systemd unit 和运维说明。
- 若由 Nginx 单独托管 `frontend/dist`，配置 `try_files $uri $uri/ /index.html` 支持 Vue History 路由。
- Basic Auth 文件作为服务器 secret 保存，不提交仓库。

### 2. Docker / Docker Compose 部署

基于现有结构，推荐两容器，而不是强行拆成三个容器：

```text
浏览器
  │ HTTPS / WSS
  ▼
Nginx 容器
  │ Basic Auth + TLS
  ▼
app 容器
  ├── 内嵌 Vue 静态资源
  ├── REST /api/v1
  ├── WebSocket /ws/v1
  └── /home/threebody/.threebody-lab
             │
             ▼
      宿主机持久化目录
```

| 项目 | 方案 |
| --- | --- |
| 准备资源 | Linux VPS、Docker Engine、Compose V2、域名 |
| Docker | 需要；现有 `Dockerfile` 可复用 |
| HTTPS | Nginx + Certbot；证书目录挂载为持久目录 |
| WebSocket | Nginx 转发 Upgrade 头，设置长连接超时；app 仅暴露到 Compose 内部网络 |
| 持久化 | 推荐 `/srv/threebody/data:/home/threebody/.threebody-lab`，比匿名 named volume 更便于备份 |
| 数据库 | 当前不需要；引入数据库会涉及仓库接口、轨迹归档和恢复语义的大改造 |
| 日志 | Docker json-file 限制 `max-size/max-file`；Nginx 日志挂载到宿主机 |
| 难度 | 部署中，维护低至中 |
| 成本 | 低，主要仍是 VPS 成本 |
| 首次上线 | 最推荐 |

优点：

- 现有镜像已采用非 root 用户和多阶段构建。
- 构建、发布、回滚和服务器迁移更可重复。
- Java/Node/Maven 不必安装在生产宿主机。
- 数据、证书和配置与镜像生命周期分离。

缺点：

- 需要维护镜像、Compose、证书续期和日志轮转。
- 在服务器本机构建镜像会消耗较多 CPU、内存和磁盘；后续宜改成 CI 构建并推送镜像。
- 挂载文件仓库后只能保持一个 app 副本，不能横向扩容。

不推荐的三容器变体：

```text
Nginx 网关 → frontend 静态容器
           → Java 后端容器
```

它技术上可行，但当前前端已经嵌入 JAR，拆分会增加镜像、版本协调和 SPA 配置，没有解决实际瓶颈。只有将来前端需要独立发布节奏时再采用。

预计修改文件：

- `docker-compose.yml`：加入 Nginx、内部网络、数据 bind mount、日志限制、停止宽限期。
- 新增 `deploy/nginx/threebody.conf`：TLS、Basic Auth、REST/WS 代理和安全头。
- 可新增 `.env.example` 与部署文档；真实域名、密码和证书不入库。
- `Dockerfile`、Java 模块、OpenAPI 和 WebSocket Schema 原则上无需修改。

### 3. 前后端分离部署

```text
lab.example.com
Cloudflare Pages / Vercel
          │ HTTPS
          │ REST + WSS 跨源
          ▼
api.example.com
VPS / Railway / Render
          │
          ▼
Java 单实例 + 持久卷
```

| 项目 | 方案 |
| --- | --- |
| 前端资源 | Cloudflare Pages、Vercel 或 OSS + CDN |
| 后端资源 | VPS 或支持持久卷和 WebSocket 的 PaaS |
| Docker | 前端不需要；后端可选 |
| HTTPS | 静态平台和后端分别签发证书 |
| WebSocket | `wss://api.example.com/ws/v1/...`，平台必须允许长连接 |
| 持久化 | 后端 VPS 目录或 PaaS persistent volume |
| 难度 | 中至高 |
| 维护难度 | 中 |
| 成本 | 前端通常免费，后端成本不变或略高 |
| 首次上线 | 不推荐 |

当前项目需要修改：

- 构建时设置现有的：
  - `VITE_API_BASE_URL=https://api.example.com/api/v1`
  - `VITE_WS_BASE_URL=wss://api.example.com/ws/v1`
- `simulation-web` 增加严格的 REST CORS 白名单。
- `WebSocketConfig` 对 `https://lab.example.com` 设置精确允许 Origin，禁止使用通配符。
- 增加 CORS/Origin 回归测试；不修改 OpenAPI 或 WS 消息结构。
- 全站保护需要同时覆盖两个子域。推荐 Cloudflare Access；分别配置 Basic Auth 会产生两套认证状态。

优点：

- 前端静态资源由 CDN 分发，发布和回滚独立。
- Cloudflare Pages 对 Vue SPA 有原生 fallback，静态请求和带宽成本低。
- 前端平台自动处理域名和证书。

缺点：

- 当前低流量场景中 CDN 收益有限。
- 引入 CORS、WebSocket Origin、双域证书和两套发布流程。
- 全站密码保护更复杂。
- OSS/CDN 往往还要单独处理 History 路由、缓存刷新和 MIME 类型。

平台选择：

- Cloudflare Pages：更适合当前纯 Vue 静态前端，免费方案包含大量静态请求、自动 SSL 和 SPA 支持。
- Vercel：同样可行，Hobby 可用于个人项目；但对当前 Vue 静态站没有明显优势。
- 不建议把现有 Java 后端迁入 Vercel Functions。即使 Vercel 已支持 WebSocket，函数实例、最大执行时长和外部状态要求与当前单 worker、内存状态、文件仓库不匹配。

### 4. 云容器 / PaaS

推荐候选为 Railway 或 Render：

```text
平台边缘 TLS
  │ HTTPS / WSS
  ▼
单个 Java/Docker Service
  │
  ▼
Persistent Volume
```

| 项目 | Railway | Render |
| --- | --- | --- |
| 部署方式 | 直接使用现有 Dockerfile | Docker Web Service |
| HTTPS/域名 | 自动 SSL、平台域名和自定义域名 | 自动 TLS 和自定义域名 |
| WebSocket | 通过平台公网网络转发 | 官方支持，无固定连接时长，但部署/维护会断线 |
| 持久化 | Volume 挂载到 `/home/threebody/.threebody-lab` | Paid Persistent Disk |
| 日志 | 平台集中日志 | 平台集中日志 |
| 单实例限制 | 文件卷模式下保持 1 replica | 挂载磁盘后只能单实例，且失去零停机发布 |
| 难度 | 部署低、维护低 | 部署低、维护低 |
| 成本 | 中：Hobby US$5 起并按 CPU、RAM、卷和流量计费 | 中：付费计算实例 + 持久磁盘 |
| 首次上线 | 可选，但成本效益不如 VPS | 可选，但持久盘限制明显 |

优点：

- 自动处理 TLS、域名、日志、重启和发布。
- 无需维护 Linux、Nginx 和证书续期。
- 适合后续接入 CI/CD。

缺点：

- Java 进程和持续物理计算的内存、CPU 计费可能高于固定 VPS。
- 免费/休眠实例不适合稳定 WebSocket 和持续实验。
- 卷绑定单实例，仍不能横向扩容。
- 部署替换会中断 WebSocket；虽然前端已有重连与 REST 重同步，但仍需验证。
- 平台区域未必对中国大陆访问最优。

不适合的 PaaS 类型：

- Cloud Run、普通 Serverless Functions、无持久卷容器平台。
- 原因是当前应用有长连接、进程内实验状态、顺序 worker 和本地轨迹文件，不适合短生命周期或自动多副本实例。

## 三、推荐实施路径

### 涉及模块与预计文件

首发采用“新加坡 VPS + Docker Compose + Nginx + 单 app 容器”：

- 部署层：`docker-compose.yml`、`deploy/nginx/threebody.conf`、部署文档。
- 构建层：复用现有根目录 `Dockerfile` 和 `simulation-launcher/pom.xml`。
- 前端：保持同源默认值，不修改 `apiClient.ts` 或 `experimentSocket.ts`。
- 后端：保持 `simulation-web`、`simulation-application` 和契约不变。
- 持久化：继续使用 `FileExperimentRepository`，宿主机挂载稳定数据目录。
- 不新增数据库、不拆前端容器、不增加公共 API。

### 数据流变化

```text
当前：
浏览器 → 127.0.0.1:8721 → Spring Boot → 本机用户目录

上线后：
浏览器
  → DNS
  → HTTPS/WSS 443
  → Nginx TLS + Basic Auth
  → Compose 内部 app:8721
  → REST / WebSocket / 内嵌 Vue
  → /home/threebody/.threebody-lab
  → /srv/threebody/data
```

业务数据、SI 单位、REST 契约、WS Schema、序列号和重连协议均不变化。

### 可独立验证的实施步骤

1. **建立主机基线**
   - 准备 2 vCPU / 2 GB 新加坡 VPS、普通非 root 管理用户、SSH key。
   - 防火墙仅开放 SSH、80、443；8721 不对公网开放。
   - 验证系统时区、磁盘空间和 NTP。

2. **整理生产 Compose**
   - app 只加入内部网络，不再映射公网端口。
   - 加入 Nginx 服务、重启策略、30 秒停止宽限期和日志上限。
   - 将数据改为明确的宿主机 bind mount。
   - 用 `docker compose config` 独立验证。

3. **配置反向代理**
   - `/`、`/api/v1`、`/ws/v1` 全部代理到 app，继续由 JAR 提供前端。
   - 配置 WebSocket Upgrade、真实客户端 IP、合理的读写超时和上传限制。
   - 启用 Basic Auth，全站统一保护。
   - 先用 HTTP/IP 验证，再接入域名。

4. **接入域名与 HTTPS**
   - DNS A/AAAA 指向 VPS。
   - Certbot 签发证书，配置 HTTP→HTTPS。
   - 验证证书自动续期和续期后的 Nginx reload。

5. **建立日志与备份**
   - Docker 日志设置大小和保留文件数。
   - Nginx 日志使用 logrotate。
   - 每日备份 `/srv/threebody/data`；重要备份在应用优雅停止或暂停实验后执行。
   - 至少完成一次恢复到临时目录并启动验证。

6. **后续演进**
   - 发布频率增加后，使用 CI 构建镜像并推送到镜像仓库，生产机只拉取。
   - 运维负担超过 VPS 成本优势时，迁移到 Railway/Render 单实例持久卷。
   - 只有出现多用户或多实例需求时，才评估数据库、对象存储和分布式事件通道。

## 四、测试与验收

- 构建：`mvn clean verify`、`docker compose build`。
- 静态页面：HTTPS 首页和 Vue `/reports/{id}` 直接刷新均正常。
- REST：认证前返回 401；认证后 `/api/v1/presets`、创建和读取实验正常。
- WebSocket：通过 Nginx 获得 101，HTTPS 页面使用 `wss://`，持续连接超过代理默认超时仍正常。
- 重连：重启 app 后前端自动重连，并通过 REST 全量同步和 `sequence` 去重恢复。
- 持久化：创建实验后重启容器，实验、报告和轨迹仍可读取。
- 停机：运行中实验收到优雅停机，归档 writer 在宽限期内刷新。
- 安全：公网无法直接访问 8721；弱密码、默认密码和认证文件不进入 Git。
- 日志：Java、Nginx access/error 均可查询，日志达到上限后正确轮转。
- 备份：从备份恢复后能够启动并读取已有实验。
- 资源：用一个高步数实验观察 CPU、内存、磁盘增长；确认 2 GB 主机没有 OOM。
- 不执行独立 lint；项目当前没有 lint 命令。

## 五、主要风险

- 文件仓库的锁只在单 JVM 内有效，禁止多个 app 副本共享同一卷。
- 全站 Basic Auth 适合个人演示，不等于多用户权限系统；密码泄露后访客可以删除实验和消耗算力。
- 物理模拟是 CPU 密集型，低价共享 CPU 实例可能出现明显抖动。
- 轨迹文件会持续增长，需要磁盘阈值、保留周期和备份策略。
- WebSocket 在部署、代理重载或平台维护时会断开，必须验收现有重连和重同步逻辑。
- 运行中直接复制数据目录可能得到应用层不一致备份，应使用优雅停止、暂停实验或块存储快照。
- 当前没有专用健康端点；首发可用只读 `/api/v1/presets` 检查，不为部署单独扩展公共契约。
- Vue 使用 History 路由；如果以后由 Nginx/Pages 单独托管静态文件，必须配置 SPA fallback。
- 香港节点通常比新加坡贵；当前优先新加坡，只有实测国内网络质量不足时再迁移香港。

## 六、明确排序

1. **最简单：单服务器 + JAR + systemd + Caddy/Nginx**
   - 组件最少。若允许补充方案，Caddy 可自动管理 HTTPS 并原生代理 WebSocket，比 Nginx + Certbot 更省维护。
   - 适合最快完成受密码保护的个人演示。

2. **最推荐：单服务器 + 现有 Dockerfile + Compose + Nginx**
   - 与仓库现状最契合，既不拆架构，又具备可重复构建、回滚、迁移和数据卷管理能力。
   - 应作为第一次正式上线方案。

3. **最适合长期低运维：Railway/Render 单服务 + 持久卷**
   - 平台接管证书、域名、日志和进程维护。
   - 成本高于 VPS，且仍只能单实例；当节省的运维时间高于额外费用时再迁移。

4. **前后端分离**
   - 可行，但当前没有性能或发布节奏需求支撑额外的 CORS、Origin、认证和双域复杂度。
   - 不作为首发选择。

## 七、参考资料

- [Nginx WebSocket 代理](https://nginx.org/en/docs/http/websocket.html)
- [Cloudflare Pages SPA](https://developers.cloudflare.com/pages/configuration/serving-pages/)
- [Cloudflare Pages 域名](https://developers.cloudflare.com/pages/configuration/custom-domains/)
- [Railway 定价](https://docs.railway.com/pricing/plans)
- [Railway Volume](https://docs.railway.com/volumes)
- [Render WebSocket](https://render.com/docs/websocket)
- [Render Persistent Disk](https://render.com/docs/disks)
- [AWS Lightsail 定价](https://aws.amazon.com/lightsail/pricing/)
- [Caddy 自动 HTTPS](https://caddyserver.com/docs/automatic-https)
