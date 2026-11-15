# RocketMQ 本地环境（P3，5.3.0）

## 启动

```bash
docker compose -f docker/rocketmq/docker-compose.yml up -d
```

启动两个容器：

| 容器 | 端口 | 作用 |
|---|---|---|
| `flashbuy-rmqnamesrv` | 9876 | 注册中心，保存 Broker 路由 |
| `flashbuy-rmqbroker` | 10909 / 10911 | Broker，负责消息存储与投递；10909 是 HA 端口 |

## 验证

```bash
docker ps --filter name=flashbuy-rmq
docker exec flashbuy-rmqbroker sh mqadmin clusterList -n namesrv:9876
```

正常会看到 `DefaultCluster broker-a 127.0.0.1:10911 V5_3_0`（5.x 的输出还带 `#Timer(Progress)` 列——时间轮状态）。

## 说明

- `brokerIP1=127.0.0.1`：让宿主机上的 Spring Boot 能通过 `127.0.0.1:10911` 连接 Broker；
- `autoCreateTopicEnable=true`：demo 环境允许生产者自动建 Topic；
- 镜像使用 `docker.m.daocloud.io/apache/rocketmq:5.3.0`，因为当前网络访问 Docker Hub 的 TLS 会被阻断（5.x 升级过程见《闪购改造记录》第 41 章）；
- `messageDelayLevel` 定制保留了 15m 档（第 14 级），供订单 15 分钟关单使用；5.x 服务端同时内置时间轮（`clusterList` 的 Timer 列），任意精度定时消息是后续演进项；
- 客户端为 rocketmq-client 5.3.0（rocketmq-spring-boot-starter 2.3.1 捆绑），与服务端 5.3.0 同代；
- 容器内的 store 数据默认不挂载到宿主机，`down -v` 会清空消息；生产应挂载 store 目录；
- 现有 TDengine 等容器不受影响，本项目只新增 `flashbuy-rmq*` 容器。

## 停止

```bash
docker compose -f docker/rocketmq/docker-compose.yml down
```
