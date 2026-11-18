# Kafka notes

- Image `apache/kafka:3.9.0`, KRaft single node (broker and controller), no ZooKeeper.
- Listeners: `INTERNAL kafka:9092` (containers), `EXTERNAL localhost:9094` (host tools), `CONTROLLER :9093`.
- `auto.create.topics.enable=false`. The app creates `<tenantCode>.readings` with the AdminClient (3 partitions,
  replication factor 1) on tenant registration, or before the first publish.
- Internal topics (offsets, transaction state) use replication factor and min ISR 1.
- List topics: `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list`
- Read a topic: `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic tenant-alpha.readings --from-beginning`
