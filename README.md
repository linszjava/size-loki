# size-loki — v-1.1

微服务日志实验：Gateway → 订单服务 → 库存服务，通过 Nacos 服务发现，使用 requestId 关联日志。

本版本由 Java 根据 HTTP 状态码生成 ERROR/INFO、outcome 和 error_type。Alloy 只采集、解析及转发 JSON，不执行状态码清洗。这是根据迁移清洗逻辑前的内容重建的基线版本。

环境：JDK 21、Maven 3.9.x、Docker Compose v2，Nacos 单独运行。

[部署与查询教程](./Loki日志采集与Docker部署指南.md)

## 版本切换

- `v-1.1`：Java 分类，Alloy 采集。
- `v-1.2`：Java 输出原始日志，Alloy 清洗。
- `main`：当前版本，与 v-1.2 对应。

```bash
git switch v-1.1
# 回到当前版本
git switch main
```

切换前先提交或保存本地改动。Git 切换不会自动重启正在运行的服务：重启 IDEA 中的 GatewayApplication，并在 loki-local 目录校验 config.alloy 后重启 Alloy。不要删除数据卷；历史日志不会随版本切换重写。
