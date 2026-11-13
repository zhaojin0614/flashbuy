# RocketMQ 本地环境（P3）

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
docker exec flashbuy-rmqbroker sh -c \
  'cd /home/rocketmq/rocketmq-4.9.7/bin && sh mqadmin clusterList -n namesrv:9876'
```

正常会看到 `DefaultCluster broker-a 127.0.0.1:10911 V4_9_7`。

## 说明

- `brokerIP1=127.0.0.1`：让宿主机上的 Spring Boot 能通过 `127.0.0.1:10911` 连接 Broker；
- `autoCreateTopicEnable=true`：demo 环境允许生产者自动建 Topic；
- 镜像使用 `docker.m.daocloud.io/apache/rocketmq:4.9.7`，因为当前网络访问 Docker Hub 的 TLS 会被阻断；
- 容器内的 store 数据默认不挂载到宿主机，`down -v` 会清空消息；生产应挂载 store 目录；
- 现有 TDengine 等容器不受影响，本项目只新增 `flashbuy-rmq*` 容器。

## 停止

```bash
docker compose -f docker/rocketmq/docker-compose.yml down
```
