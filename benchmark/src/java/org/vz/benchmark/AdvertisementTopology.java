package org.vz.benchmark;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONObject;

import org.apache.heron.api.Config;
import org.apache.heron.api.bolt.BaseRichBolt;
import org.apache.heron.api.bolt.OutputCollector;
import org.apache.heron.api.topology.OutputFieldsDeclarer;
import org.apache.heron.api.topology.TopologyBuilder;
import org.apache.heron.api.topology.TopologyContext;
import org.apache.heron.api.tuple.Tuple;
import org.apache.heron.api.tuple.Fields;
import org.apache.heron.api.tuple.Values;
import org.apache.heron.common.basics.ByteAmount;
import org.apache.heron.spouts.kafka.DefaultKafkaConsumerFactory;
import org.apache.heron.spouts.kafka.KafkaConsumerFactory;
import org.apache.heron.spouts.kafka.KafkaSpout;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.heron.api.HeronSubmitter;
import org.apache.heron.api.exception.AlreadyAliveException;
import org.apache.heron.api.exception.InvalidTopologyException;

import org.vz.common.advertising.CampaignProcessorCommon;
import org.vz.common.advertising.RedisAdCampaignCache;

public final class AdvertisementTopology {
    private static final Logger LOG = LoggerFactory.getLogger(AdvertisementTopology.class);
    private static final String KAFKA_SPOUT_NAME = "kafka-spout";
    private static final String LOGGING_BOLT_NAME = "logging-bolt";

    private AdvertisementTopology() {
    }

    public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException {

        //START INITIALIZE KAFKA SPOUT
        Map<String, Object> kafkaConsumerConfig = new HashMap<>();
        kafkaConsumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        kafkaConsumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-kafka-spout");
        kafkaConsumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConsumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                "org.apache.kafka.common.serialization.StringDeserializer");
        LOG.info("Kafka Consumer Config: {}", kafkaConsumerConfig);

        KafkaConsumerFactory<String, String> kafkaConsumerFactory = new DefaultKafkaConsumerFactory<>(
                kafkaConsumerConfig);
        //END INITIALIZE KAFKA

        int kafkaPartitions = 1;// ((Number) commonConfig.get("kafka.partitions")).intValue();
        int cores = 4; // ((Number) commonConfig.get("process.cores")).intValue();
        int timeDivisor = 10000; // ((Number) commonConfig.get("time.divisor")).intValue();
        int parallel = Math.max(1, cores / 7);
        String redisServerHost = "localhost"; //(String) commonConfig.get("redis.host");

        TopologyBuilder topologyBuilder = new TopologyBuilder();
        topologyBuilder.setSpout(KAFKA_SPOUT_NAME,
                new KafkaSpout<>(kafkaConsumerFactory, Collections.singletonList("ad-events")), kafkaPartitions);

        topologyBuilder.setBolt("event_deserializer", new DeserializeBolt(), parallel).shuffleGrouping(KAFKA_SPOUT_NAME);
        topologyBuilder.setBolt("event_filter", new EventFilterBolt(), parallel).shuffleGrouping("event_deserializer");
        topologyBuilder.setBolt("event_projection", new EventProjectionBolt(), parallel).shuffleGrouping("event_filter");
        topologyBuilder.setBolt("redis_join", new RedisJoinBolt(redisServerHost), parallel).shuffleGrouping("event_projection");
        topologyBuilder.setBolt("campaign_processor", new CampaignProcessor(redisServerHost, timeDivisor), parallel) //
                .fieldsGrouping("redis_join", new Fields("campaign_id"));


        Config config = new Config();

        HeronSubmitter.submitTopology(args[0], config, topologyBuilder.createTopology());
    }

    public static class CampaignProcessor extends BaseRichBolt {

        private OutputCollector _collector;
        transient private CampaignProcessorCommon _campaignProcessorCommon;
        private String redisServerHost;
        private int timeDivisor;

        public CampaignProcessor(String redisServerHost, int timeDivisor) {
            this.redisServerHost = redisServerHost;
            this.timeDivisor = timeDivisor;
        }

        @Override
        public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
            _campaignProcessorCommon = new CampaignProcessorCommon(redisServerHost, Long.valueOf(timeDivisor));
            _campaignProcessorCommon.prepare();
        }

        @Override
        public void execute(Tuple tuple) {

            String campaign_id = tuple.getStringByField("campaign_id");
            String event_time = tuple.getStringByField("event_time");

            _campaignProcessorCommon.execute(campaign_id, event_time);
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
        }
    }

    public static class RedisJoinBolt extends BaseRichBolt {
        private OutputCollector _collector;
        transient RedisAdCampaignCache _redisAdCampaignCache;
        private String redisServerHost;


        public RedisJoinBolt(String redisServerHost) {
            this.redisServerHost = redisServerHost;
        }

        @Override
        public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
            _redisAdCampaignCache = new RedisAdCampaignCache(redisServerHost);
            _redisAdCampaignCache.prepare();
        }

        @Override
        public void execute(Tuple tuple) {
            String ad_id = tuple.getStringByField("ad_id");
            String campaign_id = _redisAdCampaignCache.execute(ad_id);
            if (campaign_id == null) {
                _collector.fail(tuple);
                return;
            }

            _collector.emit(tuple, new Values(campaign_id,
                    tuple.getStringByField("ad_id"),
                    tuple.getStringByField("event_time")));

            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("campaign_id", "ad_id", "event_time"));
        }
    }

    public static class EventProjectionBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map<String, Object> conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {
            _collector.emit(tuple, new Values(tuple.getStringByField("ad_id"),
                    tuple.getStringByField("event_time")));
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("ad_id", "event_time"));
        }
    }

    public static class EventFilterBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {
            if (tuple.getStringByField("event_type").equals("view")) {
                _collector.emit(tuple, tuple.getValues());
            }
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("user_id", "page_id", "ad_id", "ad_type", "event_type", "event_time", "ip_address"));
        }
    }

    public static class DeserializeBolt extends BaseRichBolt {
        OutputCollector _collector;

        @Override
        public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
            _collector = collector;
        }

        @Override
        public void execute(Tuple tuple) {
            JSONObject obj = new JSONObject(tuple.getString(1));
            _collector.emit(tuple, new Values(obj.getString("user_id"),
                    obj.getString("page_id"),
                    obj.getString("ad_id"),
                    obj.getString("ad_type"),
                    obj.getString("event_type"),
                    obj.getString("event_time"),
                    obj.getString("ip_address")));
            _collector.ack(tuple);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("user_id", "page_id", "ad_id", "ad_type", "event_type", "event_time", "ip_address"));
        }
    }

}