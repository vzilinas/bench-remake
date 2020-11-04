package org.vz.benchmark;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.heron.api.Config;
import org.apache.heron.api.HeronSubmitter;
import org.apache.heron.api.bolt.BaseRichBolt;
import org.apache.heron.api.bolt.OutputCollector;
import org.apache.heron.api.exception.AlreadyAliveException;
import org.apache.heron.api.exception.InvalidTopologyException;
import org.apache.heron.api.bolt.BasicOutputCollector;
import org.apache.heron.api.spout.BaseRichSpout;
import org.apache.heron.api.bolt.BaseBasicBolt;
import org.apache.heron.api.spout.SpoutOutputCollector;
import org.apache.heron.api.topology.OutputFieldsDeclarer;
import org.apache.heron.api.topology.TopologyBuilder;
import org.apache.heron.api.topology.TopologyContext;
import org.apache.heron.api.tuple.Fields;
import org.apache.heron.api.tuple.Tuple;
import org.apache.heron.api.tuple.Values;
import org.apache.heron.common.basics.ByteAmount;

public final class WordCountTopology {
    private WordCountTopology() {
    }

    // Utils class to generate random String at given length
    public static class RandomString {
        private final char[] symbols;

        private final Random random = new Random();

        private final char[] buf;

        public RandomString(int length) {
            // Construct the symbol set
            StringBuilder tmp = new StringBuilder();
            for (char ch = '0'; ch <= '9'; ++ch) {
                tmp.append(ch);
            }

            for (char ch = 'a'; ch <= 'z'; ++ch) {
                tmp.append(ch);
            }

            symbols = tmp.toString().toCharArray();
            if (length < 1) {
                throw new IllegalArgumentException("length < 1: " + length);
            }

            buf = new char[length];
        }

        public String nextString() {
            for (int idx = 0; idx < buf.length; ++idx) {
                buf[idx] = symbols[random.nextInt(symbols.length)];
            }

            return new String(buf);
        }
    }

    /**
     * A spout that emits a random sentence
     */
    private static class SentenceSpout extends BaseRichSpout {
        private static final long serialVersionUID = 2879005791639364028L;
        private SpoutOutputCollector collector;
        private Random rand = new Random(31);
        private static final int ARRAY_LENGTH = 128 * 1024;
        private static final int WORD_LENGTH = 20;
        private final String[] sentences = new String[ARRAY_LENGTH];

        @Override
        public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
            outputFieldsDeclarer.declare(new Fields("sentence"));
        }

        @Override
        @SuppressWarnings({"rawtypes"})
        public void open(Map map, TopologyContext topologyContext,
                        SpoutOutputCollector spoutOutputCollector) {

            RandomString randomString = new RandomString(WORD_LENGTH);
            for (int i = 0; i < ARRAY_LENGTH; i++) {
                sentences[i] = randomString.nextString() + ' ' + randomString.nextString() + ' ' + randomString.nextString() + ' ' + randomString.nextString() + ' ' + randomString.nextString();
            }
            collector = spoutOutputCollector;
        }

        @Override
        public void nextTuple() {
            int n = rand.nextInt(ARRAY_LENGTH);
            collector.emit(new Values(sentences[n]));
        }
    }

    public static class SplitSentence extends BaseBasicBolt {

        private static final long serialVersionUID = 2223204156371570768L;

        @Override
        public void execute(Tuple input, BasicOutputCollector collector) {
        for (String word : input.getString(0).split("\\s+")) {
            collector.emit(new Values(word));
        }
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("word"));
        }
    }

    /**
     * A bolt that counts the words that it receives
     */
    public static class ConsumerBolt extends BaseRichBolt {
        private static final long serialVersionUID = -5470591933906954522L;

        private OutputCollector collector;
        private Map<String, Integer> countMap;

        @SuppressWarnings("rawtypes")
        public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
            collector = outputCollector;
            countMap = new HashMap<String, Integer>();
        }

        @Override
        public void execute(Tuple tuple) {
            String key = tuple.getString(0);
            if (countMap.get(key) == null) {
                countMap.put(key, 1);
            } else {
                Integer val = countMap.get(key);
                countMap.put(key, ++val);
            }
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        }
    }

    /**
     * Main method
     */
    public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException {
        if (args.length < 1) {
            throw new RuntimeException("Specify topology name");
        }

        int parallelism = 1;
        if (args.length > 1) {
            parallelism = Integer.parseInt(args[1]);
        }
        TopologyBuilder builder = new TopologyBuilder();
        builder.setSpout("sentence", new SentenceSpout(), parallelism);
        builder.setBolt("split", new SplitSentence(), parallelism).shuffleGrouping("sentence");
        builder.setBolt("consumer", new ConsumerBolt(), parallelism).fieldsGrouping("split", new Fields("word"));
        Config conf = new Config();
        conf.setNumStmgrs(parallelism);

        HeronSubmitter.submitTopology(args[0], conf, builder.createTopology());
    }
}
