sudo service redis-server start
sudo /usr/share/zookeeper/bin/zkServer.sh start
sudo ../kafka/kafka_2.13-2.6.0/bin/kafka-server-start.sh -daemon ../kafka/kafka_2.13-2.6.0/config/server.properties
