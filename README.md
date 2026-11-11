# size-loki

基于 Spring Cloud Gateway、订单服务、库存服务的微服务日志实验。三个服务通过 Nacos 发现，使用同一个 `requestId` 关联请求日志。

```text
业务：Gateway → order-service → inventory-service
日志：JSON 文件 → Grafana Alloy → Loki → Grafana
```

[Docker 安装、配置修改和日志查询教程](./Loki日志采集与Docker部署指南.md)

配套配置位于 `loki-local/`。Java 服务仍在本机运行，Compose 只部署日志组件；Nacos 需要单独运行。本阶段不需要 Prometheus。

环境：JDK 21、Maven 3.9.x、Docker Compose v2。打开根 `pom.xml` 导入项目。

Java 输出原始 INFO 请求完成日志，Alloy 按 HTTP 状态将 4xx/5xx 清洗为 ERROR，并补充 outcome、error_type 和 original_level。正文与 Loki 标签同步更新，保留原始本地日志。

已通过 8 个 Java 测试、Alloy 配置校验和 18 个隔离清洗样例。更新 Java 后需重启网关，再按教程第 8.1 节验证实际业务请求。

## 版本分支

| 分支 | 日志分类位置 | 用途 |
|---|---|---|
| [v-1.1](https://github.com/linszjava/size-loki/tree/v-1.1) | Java 根据状态码设置 ERROR 和结果字段，Alloy 只采集解析 | 加入 Alloy 清洗前的重建基线 |
| [v-1.2](https://github.com/linszjava/size-loki/tree/v-1.2) | Java 输出原始 INFO，Alloy 根据状态码清洗 | 当前清洗实验 |
| [main](https://github.com/linszjava/size-loki/tree/main) | 与 v-1.2 相同 | 当前主分支 |

```bash
git switch v-1.1
# 查看清洗版本
git switch v-1.2
# 回到主分支
git switch main
```

切换前先提交或保存本地修改。切换 Git 分支不会自动切换正在运行的服务：在 IDEA 重启 GatewayApplication；在 loki-local 目录校验 config.alloy 后执行 `docker compose restart alloy`。不要删除数据卷，已入库日志不会随代码版本切换而重写。

对比两个版本：

```bash
git diff v-1.1..v-1.2 -- loki-local/config.alloy
git diff v-1.1..v-1.2 -- edge/gateway-service
```
