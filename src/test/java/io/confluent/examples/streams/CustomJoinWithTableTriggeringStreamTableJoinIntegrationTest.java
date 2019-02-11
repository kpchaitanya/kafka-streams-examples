/*
 * Copyright Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.DoubleSerializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.kstream.TransformerSupplier;
import org.apache.kafka.streams.kstream.ValueTransformerWithKey;
import org.apache.kafka.streams.kstream.ValueTransformerWithKeySupplier;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.test.TestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import io.confluent.examples.streams.kafka.EmbeddedSingleNodeKafkaCluster;
import io.confluent.examples.streams.utils.InstantSerde;
import io.confluent.examples.streams.utils.KeyValueWithTimestamp;
import io.confluent.examples.streams.utils.Pair;
import io.confluent.examples.streams.utils.PairOfDoubleAndLongDeserializer;
import io.confluent.examples.streams.utils.PairSerde;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test that demonstrates one way to implement a custom join operation.
 * Here, we implement custom Transformers with the Processor API and plug them into a DSL topology.
 *
 * Note: This example works with Java 8+ only.
 *
 * Specifically, this example implements a stream-table LEFT join where both the stream side triggers a join output
 * being sent downstream (default behavior of KStreams) but also the table side (not supported yet in Kafka Streams out
 * of the box).  The example will also delay join output for a configurable amount of time when a record arrives in the
 * stream but doesn't yet have a matching record in the table.  If data happens to arrive "in time" on the table side, a
 * "full" join output will be produced.  If table data does not arrive in time, then (like the default behavior for a
 * stream-table LEFT join) a join output will be sent where the table-side data is `null`.  See the example input/output
 * further down below as illustration of the implemented behavior.
 *
 * The approach in this example shares state stores between a stream-side and a table-side transformer.  This is safe
 * because, if shared, Kafka Streams will place the transformers as well as the state stores into the same stream task,
 * in which all access is exclusive and single-threaded.  The state store on the table side is the normal store of a
 * KTable (thus avoiding data duplication due to store usage), whereas the state store on the stream side is manually
 * added and attached to the processing topology.
 *
 * An alternative, more flexible approach is outlined in the code comments below, in case you need additional control
 * over the join behavior, e.g. by including stream-side vs. table-side timestamps in the decision-making logic.
 *
 * The default stream-table join behavior of Kafka Streams (below: left join; inner join is similar) only triggers
 * join output when data arrives at the stream side.
 * See https://cwiki.apache.org/confluence/display/KAFKA/Kafka+Streams+Join+Semantics.
 *
 * Kafka Streams INNER stream-table join:
 *
 * Time | Stream              Table         | Join output
 * -----+-----------------------------------+-------------------------
 * 10   | ("alice", 999.99)                 | -
 * 20   |                     ("alice", 1L) | -
 * 30   | ("alice", 555.55)                 | ("alice", (555.55, 1L))
 *
 * Kafka Streams LEFT stream-table join:
 *
 * Time | Stream              Table         | Join output
 * -----+-----------------------------------+-------------------------
 * 10   | ("alice", 999.99)                 | ("alice", (999.99, null)
 * 20   |                     ("alice", 1L) | -
 * 30   | ("alice", 555.55)                 | ("alice", (555.55, 1L))
 *
 *
 * The code in this example changes the above behavior so that an application will wait a configurable amount of time
 * for data to arrive also at the table before it produces the join output for a given key (here: "alice").  The
 * motivation is that, in this example, we'd prefer to receive fully populated join messages rather than join messages
 * were the table-side information is missing (null).  Depending on your use case, you might prefer this changed
 * behavior, and these changed semantics, over the default join semantics of Kafka Streams.
 *
 * Time | Stream              Table         | Join output
 * -----+-----------------------------------+-------------------------
 * 10   | ("alice", 999.99)                 | -
 * 20   |                     ("alice", 1L) | ("alice", (999.99, 1L))
 * 30   | ("alice", 555.55)                 | ("alice", (555.55, 1L))
 *
 * Note how, in the example above, the custom join produced a join output of `("alice", (999.99, 1L))`, even though the
 * table-side record `("alice", 1L)` arrived AFTER the stream record `("alice", 999.99)`.
 *
 * IMPORTANT: Kafka Streams' current stream-table join semantics dictate that only a single join output will ever be
 * produced for a newly arriving stream-side record.  Table-side triggering of the join should only be used to ensure
 * (rather: to increase the chance) that, when a join output is actually produced, it contains data from both the stream
 * and the table.  However, table-side triggering should NOT be used to sent multiple join outputs for the same
 * stream-side record.
 *
 * Example of WRONG table-side join triggering:
 *
 *           Time | Stream              Table         | Join output
 *           -----+-----------------------------------+------------------------
 *           10   | ("alice", 999.99)                 |
 *           20   |                     ("alice", 1L) | ("alice", (999.99, 1L))
 *           30   |                     ("alice", 2L) | ("alice", (999.99, 2L))
 *
 * In the wrong example above, only one join output must be produced, not two.  It's up to you to decide which one,
 * however.
 *
 *
 * HOW TO ADAPT THIS EXAMPLE TO YOUR OWN USE CASES
 * ===============================================
 *
 * 1. You might want to add further logic that, for instance, changes the join behavior depending on the respective
 * timestamps of received stream records and table records.
 *
 * 2. The KTable's ValueTransformerWithKeySupplier can only react to values it <i>actually observes</i>.  By default,
 * the Kafka Streams DSL enables record caching for tables, which will cause the ValueTransformerWithKey to not observe
 * every single value that enters the table.  If your use case requires observing every single record, you must
 * configure the KTable so that its state store disables record caching: `Materialized.as(...).withCachingDisabled()`.
 *
 * 3. If you need even more control on what join output is being produced (or not being produced), or more control on
 * state management for the join in general, you may want to switch from this approach's use of a KTable (for reading
 * the table's topic) and a ValueTransformerWithKeySupplier for managing the table-side triggering of the join to a
 * KStream with a normal Transformer and a second state store.  Here, you'd read the table's topic into a KStream, and
 * then use a normal Transformer with code very similar to what's implemented in StreamTableJoinStreamWaitsForTable.
 * You must create a second state store, managed by this new table-side Transformer, to manage the table-side store
 * manually (because you use a KStream instead of a KTable for the table's data).
 */
public class CustomJoinWithTableTriggeringStreamTableJoinIntegrationTest {

  @ClassRule
  public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

  private static final String inputTopicForStream = "inputTopicForStream";
  private static final String inputTopicForTable = "inputTopicForTable";
  private static final String outputTopic = "outputTopic";
  private static final String tableStoreName = "table-store";

  @BeforeClass
  public static void startKafkaCluster() {
    CLUSTER.createTopic(inputTopicForStream);
    CLUSTER.createTopic(inputTopicForTable);
    CLUSTER.createTopic(outputTopic);
  }

  /**
   * Implements the stream-side join behavior of waiting a configurable amount of time for table-side data to arrive
   * before sending a join output for a newly received stream-side record.
   *
   * This behavior will increase the likelihood of "fully populated" join output messages, i.e. with data from both the
   * stream and the table side.  The downside is that this behavior will increase the end-to-end processing latency for
   * a stream-side record in the topology.
   */
  private static final class StreamTableJoinStreamWaitsForTable
    implements TransformerSupplier<String, Double, KeyValue<String, Pair<Double, Long>>> {

    private static final Logger LOG = LoggerFactory.getLogger(StreamTableJoinStreamWaitsForTable.class);

    private final Duration approxMaxWaitTimePerRecordForTableData;
    private final Duration frequencyToCheckForExpiredWaitTimes;
    private final String streamBufferStoreName;
    private final String tableStoreName;

    StreamTableJoinStreamWaitsForTable(final Duration maxWaitTimePerRecordForTableData,
                                       final Duration frequencyToCheckForExpiredWaitTimes,
                                       final String streamBufferStoreName,
                                       final String tableStoreName) {
      this.approxMaxWaitTimePerRecordForTableData = maxWaitTimePerRecordForTableData;
      this.frequencyToCheckForExpiredWaitTimes = frequencyToCheckForExpiredWaitTimes;
      this.streamBufferStoreName = streamBufferStoreName;
      this.tableStoreName = tableStoreName;
    }

    @Override
    public Transformer<String, Double, KeyValue<String, Pair<Double, Long>>> get() {
      return new Transformer<String, Double, KeyValue<String, Pair<Double, Long>>>() {

        private KeyValueStore<String, Pair<Double, Instant>> streamBufferStore;
        private KeyValueStore<String, Long> tableStore;
        private ProcessorContext context;

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
          streamBufferStore = (KeyValueStore<String, Pair<Double, Instant>>) context.getStateStore(streamBufferStoreName);
          tableStore = (KeyValueStore<String, Long>) context.getStateStore(tableStoreName);
          this.context = context;
          this.context.schedule(frequencyToCheckForExpiredWaitTimes, PunctuationType.STREAM_TIME, this::punctuate);
        }

        @Override
        public KeyValue<String, Pair<Double, Long>> transform(final String key, final Double value) {
          LOG.info("Received stream record ({}, {}) with timestamp {}", key, value, context.timestamp());
          sendAnyBufferedRecordForKey(key);
          return sendFullJoinRecordOrWaitForTableSide(key, value);
        }

        private void sendAnyBufferedRecordForKey(final String key) {
          LOG.info("Forwarding any buffered stream record for key {} because new stream record was received for same " +
              "key", key);
          final Pair<Double, Instant> record = streamBufferStore.get(key);
          if (record != null) {
            final Long tableValue = tableStore.get(key);
            final Pair<Double, Long> joinRecord = new Pair<>(record.x, tableValue);
            LOG.info("Force-forwarding buffered stream record ({}, {}) because new stream record received for key {}",
              key, joinRecord, key);
            context.forward(key, joinRecord);
            streamBufferStore.delete(key);
          }
        }

        private KeyValue<String, Pair<Double, Long>> sendFullJoinRecordOrWaitForTableSide(final String key, final Double value) {
          final Long tableValue = tableStore.get(key);
          if (tableValue != null) {
            // We have data for both the stream and the table, so we can send a fully populated join message downstream
            // immediately.
            final KeyValue<String, Pair<Double, Long>> joinRecord = KeyValue.pair(key, new Pair<>(value, tableValue));
            LOG.info("Table data available for key {}, sending fully populated join message {}", key, joinRecord);
            return joinRecord;
          } else {
            // Don't send a join output just yet because we're still lacking table-side information.  Instead, buffer
            // the current stream-side record, hoping that the table side will eventually see a matching record within
            // `approxMaxWaitTimePerRecordForTableData`.
            LOG.info("Table data not available for key {}, buffering stream record ({}, {}) temporarily", key, key,
              value);
            streamBufferStore.put(key, new Pair<>(value, Instant.ofEpochMilli(context.timestamp())));
            return null;
          }
        }

        private void punctuate(final long timestamp) {
          LOG.info("Punctuating @ timestamp {}", timestamp);
          sendAndPurgeAnyWaitingRecordsThatHaveExceededWaitTime();
        }

        private void sendAndPurgeAnyWaitingRecordsThatHaveExceededWaitTime() {
          try (KeyValueIterator<String, Pair<Double, Instant>> iterator = streamBufferStore.all()) {
            while (iterator.hasNext()) {
              final KeyValue<String, Pair<Double, Instant>> record = iterator.next();
              LOG.info("Checking buffered stream record ({}, {}) with timestamp {}", record.key, record.value.x,
                record.value.y.toEpochMilli());
              final Duration delta = Duration.between(record.value.y, Instant.ofEpochMilli(context.timestamp()));
              if (delta.compareTo(approxMaxWaitTimePerRecordForTableData) > 0) {
                // Final attempt to fetch table-side data; we use that data even if it is null (indicates: missing).
                final Long tableValue = tableStore.get(record.key);
                LOG.info("Wait time for stream record ({}, {}) has expired, force-forwarding now as join message " +
                  "({}, ({}, {}))", record.key, record.value.x, record.key, record.value.x, tableValue);
                context.forward(record.key, new Pair<>(record.value.x, tableValue));
                streamBufferStore.delete(record.key);
              }
            }
          }
        }

        @Override
        public void close() {
        }

      };
    }

  }

  /**
   * Implements table-side triggering of join output.
   *
   * For every <i>observed</i> record arriving at its upstream table, this transformer will check for a buffered (i.e.,
   * not yet joined) record on the stream side.  If there is a match, then the transformer will produce a fully
   * populated join output message -- which is the desired table-side triggering behavior.  If there is no match, then
   * the transformer will do nothing.
   */
  private static final class StreamTableJoinTableSideTrigger
    implements ValueTransformerWithKeySupplier<String, Long, Pair<Double, Long>> {

    private static final Logger LOG = LoggerFactory.getLogger(StreamTableJoinTableSideTrigger.class);

    private final String streamBufferStoreName;

    StreamTableJoinTableSideTrigger(final String streamBufferStoreName) {
      this.streamBufferStoreName = streamBufferStoreName;
    }

    @Override
    public ValueTransformerWithKey<String, Long, Pair<Double, Long>> get() {
      return new ValueTransformerWithKey<String, Long, Pair<Double, Long>>() {

        private KeyValueStore<String, Pair<Double, Instant>> streamBufferStore;
        private ProcessorContext context;

        @SuppressWarnings("unchecked")
        @Override
        public void init(final ProcessorContext context) {
          streamBufferStore = (KeyValueStore<String, Pair<Double, Instant>>) context.getStateStore(streamBufferStoreName);
          this.context = context;
        }

        @Override
        public Pair<Double, Long> transform(final String key, final Long value) {
          LOG.info("Received table record ({}, {}) with timestamp {}", key, value, context.timestamp());
          return possiblySendFullJoinRecord(key, value);
        }

        private Pair<Double, Long> possiblySendFullJoinRecord(final String key, final Long value) {
          if (value != null) {
            final Pair<Double, Instant> streamValue = streamBufferStore.get(key);
            if (streamValue != null) {
              // We have data from both stream and table, so we can send a fully populated join message downstream.
              LOG.info("Stream data available for key {}, sending fully populated join message ({}, ({}, {}))", key,
                key, streamValue.x, value);
              streamBufferStore.delete(key);
              return new Pair<>(streamValue.x, value);
            } else {
              LOG.info("Stream data not available for key {}, doing nothing", key);
              return null;
            }
          } else {
            LOG.info("Table value for key {} is null (tombstone), doing nothing", key);
            return null;
          }
        }

        @Override
        public void close() {
        }

      };
    }

  }

  @Test
  public void shouldTriggerStreamTableJoinFromTable() throws Exception {
    final Duration approxMaxWaitTimePerRecordForTableData = Duration.ofSeconds(5);
    final Duration frequencyToCheckForExpiredWaitTimes = Duration.ofSeconds(2);

    final List<KeyValueWithTimestamp<String, Double>> inputStreamRecords = Arrays.asList(
      new KeyValueWithTimestamp<>("alice", 999.99, 10),
      new KeyValueWithTimestamp<>("alice", 555.55, 30),
      new KeyValueWithTimestamp<>("recordUsedOnlyToTriggerAdvancementOfStreamTime", 77777.77,
        approxMaxWaitTimePerRecordForTableData.plus(Duration.ofSeconds(1)).toMillis())
    );

    final List<KeyValueWithTimestamp<String, Long>> inputTableRecords = Collections.singletonList(
      new KeyValueWithTimestamp<>("alice", 1L, 20)
    );

    final List<KeyValue<String, Pair<Double, Long>>> expectedOutputRecords = Arrays.asList(
      new KeyValue<>("alice", new Pair<>(999.99, 1L)),
      new KeyValue<>("alice", new Pair<>(555.55, 1L))
    );

    //
    // Step 1: Configure and start the processor topology.
    //
    final StreamsBuilder builder = new StreamsBuilder();

    final Properties streamsConfiguration = new Properties();
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "table-trigger-join-integration-test");
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    // Use a temporary directory for storing state, which will be automatically removed after the test.
    streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getAbsolutePath());

    // This state store is used to temporarily buffer any records arriving at the stream side of the join, so that
    // we can wait (if needed) for matching data to arrive at the table side.
    final StoreBuilder<KeyValueStore<String, Pair<Double, Instant>>> streamBufferStateStore =
      Stores
        .keyValueStoreBuilder(
          Stores.persistentKeyValueStore("stream-buffer-state-store"),
          Serdes.String(),
          new PairSerde<>(Serdes.Double(), new InstantSerde())
        )
        .withCachingEnabled();
    builder.addStateStore(streamBufferStateStore);

    // Read the input data.
    final KStream<String, Double> stream = builder.stream(inputTopicForStream, Consumed.with(Serdes.String(), Serdes.Double()));
    final KTable<String, Long> table =
      builder.table(inputTopicForTable, Consumed.with(Serdes.String(), Serdes.Long()), Materialized.as(tableStoreName));

    // Perform the custom join operation.
    final KStream<String, Pair<Double, Long>> transformedStream =
      stream.transform(
        new StreamTableJoinStreamWaitsForTable(
          approxMaxWaitTimePerRecordForTableData,
          frequencyToCheckForExpiredWaitTimes,
          streamBufferStateStore.name(),
          tableStoreName),
        streamBufferStateStore.name(),
        tableStoreName);
    final KTable<String, Pair<Double, Long>> transformedTable =
      table.transformValues(
        new StreamTableJoinTableSideTrigger(streamBufferStateStore.name()),
        streamBufferStateStore.name());
    final KStream<String, Pair<Double, Long>> joined = transformedStream.merge(transformedTable.toStream());

    // Write the join results back to Kafka.
    joined.to(outputTopic, Produced.with(Serdes.String(), new PairSerde<>(Serdes.Double(), Serdes.Long())));

    // Start the topology.
    final KafkaStreams streams = new KafkaStreams(builder.build(), streamsConfiguration);
    streams.start();

    //
    // Step 2: Produce some input data to the input topics.
    //

    // Produce input data for the stream
    final Properties producerConfigStream = new Properties();
    producerConfigStream.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    producerConfigStream.put(ProducerConfig.ACKS_CONFIG, "all");
    producerConfigStream.put(ProducerConfig.RETRIES_CONFIG, 0);
    producerConfigStream.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfigStream.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, DoubleSerializer.class);
    IntegrationTestUtils.produceKeyValuesWithTimestampsSynchronously(inputTopicForStream, inputStreamRecords,
      producerConfigStream);

    // Produce input data for the table
    final Properties producerConfigTable = new Properties();
    producerConfigTable.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    producerConfigTable.put(ProducerConfig.ACKS_CONFIG, "all");
    producerConfigTable.put(ProducerConfig.RETRIES_CONFIG, 0);
    producerConfigTable.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerConfigTable.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
    IntegrationTestUtils.produceKeyValuesWithTimestampsSynchronously(inputTopicForTable, inputTableRecords,
      producerConfigTable);

    //
    // Step 3: Verify the application's output data.
    //
    final Properties consumerConfig = new Properties();
    consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "table-trigger-join-integration-test-standard-consumer");
    consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, PairOfDoubleAndLongDeserializer.class);
    final List<KeyValue<String, Long>> actualRecords = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(
      consumerConfig,
      outputTopic,
      expectedOutputRecords.size()
    );
    streams.close();
    assertThat(actualRecords).isEqualTo(expectedOutputRecords);
  }

}