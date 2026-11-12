# size-loki — v-1.1

微服务日志实验：Gateway → 订单服务 → 库存服务，通过 Nacos 服务发现，使用 requestId 关联日志。

本版本的 Java 网关请求完成日志统一使用 INFO，只记录 status 等原始字段，不添加 outcome 或 error_type。Alloy 只采集、解析及转发 JSON，不执行状态码清洗。因此商品不存在或未知网址返回 404 时，完成日志仍为 INFO。其他业务代码主动记录的 ERROR 不受影响。

环境：JDK 21、Maven 3.9.x、Docker Compose v2，Nacos 单独运行。

[部署与查询教程](./Loki日志采集与Docker部署指南.md)

## 版本切换

- `v-1.1`：Java 原始 INFO 日志，Alloy 仅采集，不分类。
- `v-1.2`：Java 输出原始日志，Alloy 清洗。
- `main`：当前版本，与 v-1.2 对应。

```bash
git switch v-1.1
# 回到当前版本
git switch main
```

切换前先提交或保存本地改动。Git 切换不会自动重启正在运行的服务：重启 IDEA 中的 GatewayApplication，并在 loki-local 目录校验 config.alloy 后重启 Alloy。不要删除数据卷；历史日志不会随版本切换重写。
