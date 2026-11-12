# size-loki 日志采集与 Docker 部署指南

## 1. 本次搭建什么

基于项目已有业务代码，运行以下链路：

```text
请求 → Gateway（8088）→ 订单服务（18081）→ 库存服务（18082）
                        Nacos 服务发现
三个服务 → 项目 logs/*.json → Alloy → Loki ← Grafana
```

| 组件 | 本实验中的职责 |
|---|---|
| Spring Boot 微服务 | 输出 ECS JSON 日志，并在业务日志中记录 requestId |
| Alloy | 读取文件、解析服务名和级别、添加标签、使用原始日志时间、发送日志 |
| Loki | 接收日志，保存正文和标签索引，按 LogQL 查询和统计 |
| Grafana | 提供日志查询界面和图表 |

不需要 Elasticsearch、Logstash、Kibana 或 Prometheus。Prometheus 可以在后续 JVM、CPU、接口指标监控实验中引入。

本教程面向本机学习：单实例、本地文件存储、端口仅绑定回环地址。Loki 没有启用租户隔离或外部认证，不能直接照搬为公网部署。

## 2. 配置文件与准备条件

所有配套文件已写入项目，不必从文档手动复制：

```text
size-loki/
├── pom.xml
├── edge/gateway-service/
├── services/order-service/
├── services/inventory-service/
├── logs/                              # 运行时生成，不提交
└── loki-local/
    ├── compose.yml
    ├── loki-config.yml
    ├── config.alloy
    └── grafana/provisioning/datasources/loki.yml
```

准备 JDK 21、Maven 3.9.x、已启动的 Docker Desktop（或 Docker Engine + Compose v2）和独立 Nacos。Nacos 不包含在本项目 Compose 中。

在项目根目录执行：

```bash
java -version
mvn -version
docker version
docker compose version
mkdir -p logs
```

当前镜像固定为 Loki `3.7.0`、Alloy `v1.19.2`、Grafana `12.4.0`，用于固定教程配置，不代表始终为最新版本。升级前查阅发布说明并重新运行配置校验。

Mac 上若 Docker 报挂载目录无权限，检查 Docker Desktop 的文件共享设置是否允许访问项目所在目录；本项目位于外置卷时也要检查该卷的访问权限。

## 3. 微服务需要怎么改

已有 JSON 日志配置可以继续使用，无需增加 Loki SDK，也不需要把日志直接推送到 Loki。网关在 Java 中以 INFO 输出最终 HTTP 状态等原始字段，不做结果分类，Alloy 仅采集解析，见第 8.1 节。修改后的 Java 代码需要重启网关才能生效。

三个服务的 `application.yml` 已配置日志文件。例如订单服务：

```yaml
logging:
  file:
    name: ${LOG_FILE:./logs/order-service.json}
  structured:
    format:
      file: ecs
    ecs:
      service:
        name: ${spring.application.name}
        version: 1.0.0-SNAPSHOT
        environment: local
```

ECS 在这里是 JSON 字段格式，不意味着依赖 Elasticsearch。服务输出 `service.name`、`log.level`、`@timestamp`、`message`，业务代码另加 `requestId`、`status`、`duration_ms` 等字段；不是每一条日志都有所有业务字段。

在 IDEA 中，把三个应用的 **Working directory 都设为当前 size-loki 根目录**，否则相对路径 `./logs` 会落到不同模块目录，Alloy 无法从统一目录读到日志。

现有滚动配置每个文件最大 10 MB、保留历史 7 天、总大小上限 100 MB。这只控制微服务本地文件，与 Loki 的保留策略相互独立。

## 4. Loki 配置怎么修改

配置入口：[loki-local/loki-config.yml](./loki-local/loki-config.yml)。

| 配置 | 本实验取值 | 含义 |
|---|---|---|
| `auth_enabled` | `false` | 单租户本地实验；此开关不是登录认证系统 |
| `server.http_listen_port` | `3100` | 容器内 HTTP API 端口 |
| `common.path_prefix` | `/loki` | 数据工作目录，挂载命名卷持久化 |
| `common.replication_factor` | `1` | 单实例，不使用多副本 |
| `common.ring.kvstore.store` | `inmemory` | 单进程使用内存 ring |
| `schema_config.configs[].store` | `tsdb` | 使用 TSDB 索引 |
| `schema_config.configs[].schema` | `v13` | 索引 schema 版本 |
| `schema_config.configs[].object_store` | `filesystem` | 本地文件存储，适合本实验 |
| `schema_config.configs[].index.period` | `24h` | 每日索引周期，配合保留策略 |
| `limits_config.retention_period` | `168h` | 日志保留 7 天 |
| `compactor.retention_enabled` | `true` | 开启 Compactor 保留清理 |
| `compactor.delete_request_store` | `filesystem` | 使用本地删除请求存储 |

例如改为保留 14 天：

```yaml
limits_config:
  retention_period: 336h
```

保留时间不是到点立即删文件，Compactor 会异步处理。缩短保留时间会导致符合条件的旧日志被清理，修改前确认需要保留的数据。

`schema_config` 中的 `from: 2024-01-01` 表示 schema 生效日期，覆盖本实验的新日志。已有数据后不要随意重写历史 schema；升级应按官方说明追加未来生效的配置段。

改配置后，在 `loki-local` 目录执行：

```bash
docker compose run --rm --no-deps loki \
  -config.file=/etc/loki/config.yml -verify-config=true
docker compose restart loki
curl -fsS http://localhost:3100/ready
```

若只想把宿主机端口换成 13100，修改 Compose 的 `127.0.0.1:3100:3100` 为 `127.0.0.1:13100:3100`。容器之间仍使用 `http://loki:3100`，宿主机的检查地址则改为 `http://localhost:13100/ready`。Compose 端口修改后用 `docker compose up -d` 重建配置，不能只 restart。

## 5. Alloy 怎么读取本项目日志

配置入口：[loki-local/config.alloy](./loki-local/config.alloy)。

处理过程：

```text
local.file_match → loki.source.file → loki.process → loki.write
发现日志文件        持续读取          解析与加标签     发到 Loki
```

Compose 的目录映射是：

```yaml
- ../logs:/var/log/size-loki:ro
```

左侧相对于 `loki-local/compose.yml`，就是项目根目录的 `logs`；右侧是 Alloy 容器中的路径。因此 Alloy 的 `__path__` 写 `/var/log/size-loki/*-service.json`，不能写宿主机绝对路径。

Alloy 使用 JMESPath 提取 JSON 字段。本项目实际输出嵌套对象，例如 `"service":{"name":"gateway-service"}` 和 `"log":{"level":"INFO"}`，因此使用 `service.name`、`log.level` 访问嵌套字段。下面同时兼容带点号的扁平字段名：

```alloy
stage.json {
  expressions = {
    service_name = "service.name || \"service.name\"",
    level        = "log.level || \"log.level\"",
    timestamp    = "\"@timestamp\"",
  }
}
```

当前标签包含 `job="size-loki"`、`env="local"`、`service_name` 和 `level`，文件采集器也会附带 `filename`。正文保持原样，级别来自 Java；网关完成日志不包含请求分类字段。时间取自日志的 `@timestamp`，解析失败时保留采集器原来的时间。

**不要把 requestId、订单号、用户 ID 设为索引标签。** 这些值数量会持续增长，产生大量日志流；保留在正文中，在查询时用 `| json` 提取即可。

本实验只匹配当前活动文件，不批量导入 `.json.日期.序号` 历史轮转文件。持续运行时采集器可以跟随文件轮转，但如果采集器长时间停止、日志已滚动或删除，恢复后可能缺失该期间日志。持久化的 `alloy-data` 保存读取位置，不应随意删除，也不意味着具备端到端零丢失保证。

修改 Alloy 后先校验再重启：

```bash
docker compose run --rm --no-deps alloy validate /etc/alloy/config.alloy
docker compose restart alloy
```

如果新增服务仍写入 `logs/xxx-service.json`，且使用同样 JSON 格式，会被现有规则发现。若更改日志目录，应同步修改 Compose 挂载；若更改文件名，应同步修改 Alloy 通配符。

## 6. 用 Docker 安装并启动三个组件

从项目根目录执行：

```bash
mkdir -p logs
cd loki-local
docker compose config -q
docker compose pull
```

下载完成后，使用对应版本的程序校验配置：

```bash
docker compose run --rm --no-deps loki \
  -config.file=/etc/loki/config.yml -verify-config=true
docker compose run --rm --no-deps alloy validate /etc/alloy/config.alloy
```

两条命令应退出码为 0，再启动：

```bash
docker compose up -d
docker compose ps
docker compose logs --tail=100 loki alloy grafana
```

| 服务 | 本机地址 | 用途 |
|---|---|---|
| Grafana | http://localhost:3000 | 查看日志 |
| Loki | http://localhost:3100/ready | 就绪检查 |
| Alloy | http://localhost:12345 | 查看采集组件状态 |

`depends_on` 只控制启动顺序，不保证 Loki 已就绪；刚启动时 Alloy 暂时连接失败可能是正常现象，需要确认随后恢复。

```bash
curl -fsS http://localhost:3100/ready
curl -fsS http://localhost:12345/-/ready
curl -fsS http://localhost:3000/api/health
```

启动初期 `/ready` 可能暂时返回非 200，稍后重试并检查日志。就绪检查成功不代表业务日志已采集，后面还需验证 requestId 查询。

Grafana 使用官方镜像初始账号 `admin`、密码 `admin`，首次登录修改密码。如果已有 `grafana-data` 卷，账号密码沿用已有数据。

[数据源配置](./loki-local/grafana/provisioning/datasources/loki.yml) 会自动创建默认的 **Loki** 数据源。URL 是 `http://loki:3100`，不是 `http://localhost:3100`：Grafana 在容器内，localhost 指向它自己。

## 7. 启动微服务并产生测试日志

在 IDEA 导入根 `pom.xml`，选择 JDK 21。三个应用均配置自己的环境变量：

| 变量 | 内容 |
|---|---|
| `NACOS_ADDR` | 本机 Nacos 可使用 `localhost:8848` |
| `NACOS_USERNAME` | 实际用户名 |
| `NACOS_PASSWORD` | 实际密码 |

按顺序启动 `InventoryApplication`、`OrderApplication`、`GatewayApplication`，确认在 Nacos 中注册成功。若原 size-elk 的三个 Java 服务还在运行，先停止它们，避免端口冲突和同名服务混入发现列表。

在终端执行：

```bash
curl -i -H 'X-Request-ID: loki-demo-ok-001' \
  http://localhost:8088/api/orders/1001
curl -i -H 'X-Request-ID: loki-demo-empty-001' \
  http://localhost:8088/api/orders/1002
curl -i -H 'X-Request-ID: loki-demo-404-001' \
  http://localhost:8088/api/orders/1003
```

预期：1001 有库存、可下单；1002 库存为零、不可下单但 HTTP 仍成功；1003 商品不存在、HTTP 404。业务只查询，不创建订单或扣减库存。

每次新实验换一个唯一 requestId，或省略请求头让网关生成，并从响应 `X-Request-ID` 中读取。重复使用同一个值会把多次调用混到一起。

从项目根目录检查文件：

```bash
ls -lh logs/
tail -n 3 logs/gateway-service.json
```

应能看到三个服务的 JSON 文件及新写入的业务日志。等待数秒供文件发现和批量发送。

## 8. 在 Grafana 查询跨服务日志

打开 Grafana 的 Explore，选择 **Loki** 数据源、切换到代码查询模式，将时间范围设为最近 15 分钟。

全部项目日志：

```logql
{job="size-loki", env="local"}
```

单个服务：

```logql
{job="size-loki", service_name="order-service"}
```

精确查一次请求的三个服务日志：

```logql
{job="size-loki"} |= "loki-demo-ok-001" | json | requestId="loki-demo-ok-001"
```

先按标签和时间定位，再做正文过滤及 JSON 字段匹配。按时间从旧到新排列，观察网关、订单和库存的日志；这属于日志关联，不是分布式追踪瀑布图。

错误级别日志：

```logql
{job="size-loki", level="ERROR"}
```

网关 HTTP 失败请求：

```logql
{job="size-loki", service_name="gateway-service"}
  |= "网关请求完成" | json | status >= 400 | __error__=""
```

本版本网关完成日志统一为 INFO，即使 status=404/500 也不会自动改成 ERROR。请按 status 查询失败请求，不能仅用 level=ERROR。Alloy 不负责分类，历史日志不会自动修改。

过去 5 分钟网关失败请求数：

```logql
sum(count_over_time(
  {job="size-loki", service_name="gateway-service"}
  |= "网关请求完成" | json | status >= 400 | __error__="" [5m]
))
```

该统计无需 Prometheus。没有匹配日志时可能显示 No data，而不是数字 0。

## 8.1. v-1.1：原始日志，不做状态码清洗

Java 网关的开始和完成日志都使用 `log.atInfo()`，只记录 requestId、method、path、status、duration_ms 和 event_action。Java 和 Alloy 都不根据状态码转换级别，不添加 outcome、error_type、original_level 或 processing_pipeline。

保留 WebFilter 和响应提交前读取状态的机制，保证未知网址及异常处理后的最终 404/500 也有记录；这只是记录原始状态，不是分类清洗。其他业务代码主动输出的 ERROR 日志仍保留。

切换到本版本后，必须在 IDEA 重启 GatewayApplication，并在 loki-local 目录执行：

```bash
docker compose exec -T alloy alloy validate /etc/alloy/config.alloy
docker compose restart alloy
```

发送新请求（每次测试更换 requestId）：

```bash
curl -i -H 'X-Request-ID: raw-product-404-001' http://localhost:8088/api/orders/1004
curl -i -H 'X-Request-ID: raw-url-404-001' http://localhost:8088/no-such-url
```

预期本地文件与 Grafana 的网关完成日志均为 INFO、status=404，没有清洗分类字段。HTTP 响应仍为 404，日志级别不影响响应码。

在 Grafana 选覆盖新请求的时间范围，查询失败完成事件：

```logql
{job="size-loki", service_name="gateway-service"}
  | json | event_action="gateway_request_completed" | status >= 400 | __error__=""
```

历史日志不会回写：过去的 ERROR、outcome 等字段仍会出现在旧记录中。新规则只影响重新启动后产生并采集的新日志。

duration_ms 为收到请求至提交响应头的耗时，提交前取消不会产生完成事件。

运行网关测试：

```bash
mvn -o -pl edge/gateway-service -am test
```

8 个测试覆盖原始 INFO 输出、无分类字段、最终状态、未知网址、500、默认 200 和 requestId 透传。`v-1.2` 则在相同原始日志基础上由 Alloy 执行清洗。

## 9. 日常维护与排查

以下命令在 `loki-local` 目录执行：

```bash
docker compose logs --tail=100 alloy loki
docker compose restart alloy
docker compose restart loki
docker compose down
docker compose up -d
```

普通 `down` 保留命名卷。不要加 `-v`，否则会删除 Loki 日志、Grafana 设置和 Alloy 读取位置。

| 现象 | 检查方向 |
|---|---|
| Docker 无法连接 | Docker Desktop 是否启动，当前用户是否有 socket 访问权限 |
| 日志目录为空 | Java 服务是否启动；IDEA 工作目录是否为 size-loki 根目录 |
| Alloy 找不到文件 | 挂载目录、文件名通配符、文件读取权限、Docker 文件共享权限 |
| Loki 拒收日志 | 查看 Alloy/Loki 日志，注意时间戳过旧、乱序或速率限制 |
| Grafana 无数据 | 数据源 URL、时间范围、job 标签、Alloy 状态；先使用最简单的全部日志查询 |
| requestId 查不到 | 检查正文实际字段和值，使用新请求验证；启动日志通常没有 requestId |
| level/service_name 缺失 | 检查 JSON 是嵌套对象还是带点号的扁平字段；当前表达式兼容两种形式 |
| 改 Compose 不生效 | 执行 `docker compose up -d`，restart 不会更新挂载和端口配置 |
| 重启后历史日志不出现 | Alloy 读取位置会避免重读；先生成新请求，不要直接删除卷 |

验收顺序：三个组件就绪 → 三个 JSON 文件产生新内容 → Grafana 能看到 `job="size-loki"` → 一个 requestId 能关联三个服务 → 404 查询能看到网关失败记录。

## 10. 验证范围与参考资料

本版本为加入 Alloy 清洗前的实现重建，包含 8 个网关测试。切换后应重启网关和 Alloy，再按第 8.1 节联调。

官方参考（配置编写时核对）：

- [Loki Docker 安装](https://grafana.com/docs/loki/latest/setup/install/docker/)
- [Loki 3.7.0 本地配置](https://github.com/grafana/loki/blob/v3.7.0/cmd/loki/loki-local-config.yaml)
- [Loki 日志保留策略](https://grafana.com/docs/loki/latest/operations/storage/retention/)
- [Alloy 日志处理组件](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/)
- [Alloy 文件采集组件](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
- [Grafana Docker 安装](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)
- [LogQL 日志查询](https://grafana.com/docs/loki/latest/query/log_queries/)
