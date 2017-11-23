/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.openmessaging.benchmark;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.RateLimiter;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.benchmark.driver.BenchmarkConsumer;
import io.openmessaging.benchmark.driver.BenchmarkDriver;
import io.openmessaging.benchmark.driver.BenchmarkProducer;
import io.openmessaging.benchmark.driver.ConsumerCallback;
import io.openmessaging.benchmark.utils.PaddingDecimalFormat;
import io.openmessaging.benchmark.utils.Timer;

public class WorkloadGenerator implements ConsumerCallback, AutoCloseable {

    private final String driverName;
    private final BenchmarkDriver benchmarkDriver;
    private final Workload workload;

    private static final ExecutorService executor = Executors
            .newCachedThreadPool(new DefaultThreadFactory("messaging-benchmark"));

    private final LongAdder messagesSent = new LongAdder();
    private final LongAdder bytesSent = new LongAdder();

    private final LongAdder messagesReceived = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();

    private final LongAdder totalMessagesSent = new LongAdder();
    private final LongAdder totalMessagesReceived = new LongAdder();

    private boolean testCompleted = false;
    private volatile boolean needToWaitForBacklogDraining = false;

    public WorkloadGenerator(String driverName, BenchmarkDriver benchmarkDriver, Workload workload) {
        this.driverName = driverName;
        this.benchmarkDriver = benchmarkDriver;
        this.workload = workload;

        if (workload.consumerBacklogSizeGB > 0 && workload.producerRate == 0) {
            throw new IllegalArgumentException("Cannot probe producer sustainable rate when building backlog");
        }
    }

    public TestResult run() throws Exception {
        List<String> topics = createTopics();
        List<BenchmarkConsumer> consumers = createConsumers(topics);
        List<BenchmarkProducer> producers = createProducers(topics);

        ensureTopicsAreReady(producers, consumers);

        RateLimiter rateLimiter;
        AtomicBoolean runCompleted = new AtomicBoolean();
        if (workload.producerRate > 0) {
            rateLimiter = RateLimiter.create(workload.producerRate);
        } else {
            // Producer rate is 0 and we need to discover the sustainable rate
            rateLimiter = RateLimiter.create(10000);

            executor.execute(() -> {
                // Run background controller to adjust rate
                findMaximumSustainableRate(rateLimiter, runCompleted);
            });
        }

        log.info("----- Starting warm-up traffic ------");
        generateLoad(producers, TimeUnit.MINUTES.toSeconds(1), rateLimiter);
        runCompleted.set(true);

        if (workload.consumerBacklogSizeGB > 0) {
            log.info("Stopping all consumers to build backlog");
            for (BenchmarkConsumer consumer : consumers) {
                consumer.close();
            }

            executor.execute(() -> {
                buildAndDrainBacklog(topics);
            });
        }

        log.info("----- Starting benchmark traffic ------");
        runCompleted.set(false);

        if (workload.producerRate == 0) {
            // Continue with the feedback system to adjust the publish rate
            executor.execute(() -> {
                // Run background controller to adjust rate
                findMaximumSustainableRate(rateLimiter, runCompleted);
            });
        }

        TestResult result = generateLoad(producers, TimeUnit.MINUTES.toSeconds(workload.testDurationMinutes),
                rateLimiter);
        runCompleted.set(true);

        return result;
    }

    private void ensureTopicsAreReady(List<BenchmarkProducer> producers, List<BenchmarkConsumer> consumers) {
        log.info("Waiting for consumers to be ready");
        // This is work around the fact that there's no way to have a consumer ready in Kafka without first publishing
        // some message on the topic, which will then trigger the partitions assignement to the consumers

        // In this case we just publish 1 message and then wait for consumers to receive the data
        producers.forEach(
                producer -> producer.sendAsync("key", new byte[10]).thenRun(() -> totalMessagesSent.increment()));

        long expectedMessages = workload.subscriptionsPerTopic * producers.size();

        while (totalMessagesReceived.sum() < expectedMessages) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.info("All consumers are ready");
    }

    /**
     * Adjust the publish rate to a level that is sustainable, meaning that we can consume all the messages that are
     * being produced
     */
    private void findMaximumSustainableRate(RateLimiter rateLimiter, AtomicBoolean testCompleted) {
        double maxRate = Double.MAX_VALUE; // Discovered max sustainable rate
        double currentRate = rateLimiter.getRate(); // Start with a reasonable number
        double minRate = 0.1;

        long localTotalMessagesSentCounter = totalMessagesSent.sum();
        long localTotalMessagesReceivedCounter = totalMessagesReceived.sum();

        int controlPeriodMillis = 3000;

        int successfulPeriods = 0;

        while (!testCompleted.get()) {
            // Check every few seconds and adjust the rate
            try {
                Thread.sleep(controlPeriodMillis);
            } catch (InterruptedException e) {
                return;
            }

            // Consider multiple copies when using mutlple subscriptions
            long totalMessagesSent = this.totalMessagesSent.sum();
            long totalMessagesReceived = this.totalMessagesReceived.sum();
            long messagesPublishedInPeriod = totalMessagesSent - localTotalMessagesSentCounter;
            long messagesReceivedInPeriod = totalMessagesReceived - localTotalMessagesReceivedCounter;
            double publishRateInLastPeriod = messagesPublishedInPeriod / (controlPeriodMillis / 1000.0);
            double receiveRateInLastPeriod = messagesReceivedInPeriod / (controlPeriodMillis / 1000.0);

            localTotalMessagesSentCounter = totalMessagesSent;
            localTotalMessagesReceivedCounter = totalMessagesReceived;

            log.debug("Current rate:  {} -- Publish rate {} -- Consume Rate: {} -- min-rate: {} -- max-rate: {}",
                    dec.format(currentRate), dec.format(publishRateInLastPeriod), dec.format(receiveRateInLastPeriod),
                    dec.format(minRate), dec.format(maxRate));

            if (publishRateInLastPeriod < currentRate * 0.95) {
                // Producer is not able to publish as fast as requested
                maxRate = currentRate * 1.1;
                currentRate = minRate + (currentRate - minRate) / 2;

                log.debug("Publishers are not meeting requested rate. reducing to {}", currentRate);
            } else if (receiveRateInLastPeriod < publishRateInLastPeriod * 0.98) {
                // If the consumers are building backlog, we should slow down publish rate
                maxRate = currentRate;
                currentRate = minRate + (currentRate - minRate) / 2;
                log.debug("Consumers are not meeting requested rate. reducing to {}", currentRate);

                // Slows the publishes to let the consumer time to absorb the backlog
                rateLimiter.setRate(minRate / 10);
                while (true) {
                    long backlog = workload.subscriptionsPerTopic * this.totalMessagesSent.sum()
                            - this.totalMessagesReceived.sum();
                    if (backlog < 1000) {
                        break;
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                log.debug("Resuming load at reduced rate");
                rateLimiter.setRate(currentRate);

                try {
                    // Wait some more time for the publish rate to catch up
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }

                localTotalMessagesSentCounter = this.totalMessagesSent.sum();
                localTotalMessagesReceivedCounter = this.totalMessagesReceived.sum();

            } else if (currentRate < maxRate) {
                minRate = currentRate;
                currentRate = Math.min(currentRate * 2, maxRate);
                log.debug("No bottleneck found, increasing the rate to {}", currentRate);
            } else if (++successfulPeriods > 3) {
                minRate = currentRate * 0.95;
                maxRate = currentRate * 1.05;
                successfulPeriods = 0;
            }

            rateLimiter.setRate(currentRate);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdownNow();
    }

    private List<String> createTopics() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        Timer timer = new Timer();

        String topicPrefix = benchmarkDriver.getTopicNamePrefix();

        List<String> topics = new ArrayList<>();
        for (int i = 0; i < workload.topics; i++) {
            String topic = String.format("%s-%s-%04d", topicPrefix, getRandomString(), i);
            topics.add(topic);
            futures.add(benchmarkDriver.createTopic(topic, workload.partitionsPerTopic));
        }

        futures.forEach(CompletableFuture::join);

        log.info("Created {} topics in {} ms", topics.size(), timer.elapsedMillis());
        return topics;
    }

    private List<BenchmarkConsumer> createConsumers(List<String> topics) {
        List<CompletableFuture<BenchmarkConsumer>> futures = new ArrayList<>();
        Timer timer = new Timer();

        for (int i = 0; i < workload.subscriptionsPerTopic; i++) {
            String subscriptionName = String.format("sub-%03d", i);

            for (String topic : topics) {
                futures.add(benchmarkDriver.createConsumer(topic, subscriptionName, this));
            }
        }

        List<BenchmarkConsumer> consumers = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());

        log.info("Created {} consumers in {} ms", consumers.size(), timer.elapsedMillis());
        return consumers;
    }

    private List<BenchmarkProducer> createProducers(List<String> topics) {
        List<CompletableFuture<BenchmarkProducer>> futures = new ArrayList<>();
        Timer timer = new Timer();

        for (int i = 0; i < workload.producersPerTopic; i++) {
            for (String topic : topics) {
                futures.add(benchmarkDriver.createProducer(topic));
            }
        }

        List<BenchmarkProducer> producers = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        log.info("Created {} producers in {} ms", producers.size(), timer.elapsedMillis());
        return producers;
    }

    private void buildAndDrainBacklog(List<String> topics) {
        this.needToWaitForBacklogDraining = true;

        long requestedBacklogSize = workload.consumerBacklogSizeGB * 1024 * 1024 * 1024;

        while (true) {
            long currentBacklogSize = (workload.subscriptionsPerTopic * totalMessagesSent.sum()
                    - totalMessagesReceived.sum()) * workload.messageSize;

            if (currentBacklogSize >= requestedBacklogSize) {
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.info("--- Start draining backlog ---");

        createConsumers(topics);

        final long minBacklog = 1000;

        while (true) {
            long currentBacklog = workload.subscriptionsPerTopic * totalMessagesSent.sum()
                    - totalMessagesReceived.sum();
            if (currentBacklog <= minBacklog) {
                log.info("--- Completed backlog draining ---");
                needToWaitForBacklogDraining = false;
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private TestResult generateLoad(List<BenchmarkProducer> producers, long testDurationsSeconds,
            RateLimiter rateLimiter) {
        Recorder recorder = new Recorder(TimeUnit.SECONDS.toMicros(30), 5);
        Recorder cumulativeRecorder = new Recorder(TimeUnit.SECONDS.toMicros(30), 5);

        long startTime = System.nanoTime();
        this.testCompleted = false;

        int processors = Runtime.getRuntime().availableProcessors();
        Collections.shuffle(producers);

        final byte[] payloadData = new byte[workload.messageSize];

        // Divide the producers across multiple different threads
        for (List<BenchmarkProducer> producersPerThread : Lists.partition(producers, processors)) {
            executor.submit(() -> {
                try {

                    // Send messages on all topics/producers assigned to this thread
                    while (!testCompleted) {
                        for (int i = 0; i < producersPerThread.size(); i++) {
                            BenchmarkProducer producer = producersPerThread.get(i);
                            rateLimiter.acquire();

                            final long sendTime = System.nanoTime();
                            String key = randomKeys[(int) sendTime % randomKeys.length];

                            producer.sendAsync(key, payloadData).thenRun(() -> {
                                messagesSent.increment();
                                totalMessagesSent.increment();
                                bytesSent.add(payloadData.length);

                                long latencyMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - sendTime);
                                recorder.recordValue(latencyMicros);
                                cumulativeRecorder.recordValue(latencyMicros);

                            }).exceptionally(ex -> {
                                log.warn("Write error on message", ex);
                                System.exit(-1);
                                return null;
                            });
                        }
                    }
                } catch (Throwable t) {
                    log.error("Got error", t);
                }
            });
        }

        // Print report stats
        long oldTime = System.nanoTime();

        long testEndTime = startTime + TimeUnit.SECONDS.toNanos(testDurationsSeconds);

        TestResult result = new TestResult();
        result.workload = workload.name;
        result.driver = driverName;

        Histogram reportHistogram = null;

        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }

            long now = System.nanoTime();
            double elapsed = (now - oldTime) / 1e9;

            double publishRate = messagesSent.sumThenReset() / elapsed;
            double publishThroughput = bytesSent.sumThenReset() / elapsed / 1024 / 1024;

            double consumeRate = messagesReceived.sumThenReset() / elapsed;
            double consumeThroughput = bytesReceived.sumThenReset() / elapsed / 1024 / 1024;

            reportHistogram = recorder.getIntervalHistogram(reportHistogram);

            long currentBacklog = workload.subscriptionsPerTopic * totalMessagesSent.sum()
                    - totalMessagesReceived.sum();

            log.info(
                    "Pub rate {} msg/s / {} Mb/s | Cons rate {} msg/s / {} Mb/s | Backlog: {} K | Pub Latency (ms) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {}",
                    rateFormat.format(publishRate), throughputFormat.format(publishThroughput),
                    rateFormat.format(consumeRate), throughputFormat.format(consumeThroughput),
                    dec.format(currentBacklog / 1000.0), //
                    dec.format(reportHistogram.getMean() / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(50) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(99) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(99.9) / 1000.0),
                    throughputFormat.format(reportHistogram.getMaxValue() / 1000.0));

            result.publishRate.add(publishRate);
            result.consumeRate.add(consumeRate);
            result.backlog.add(currentBacklog);
            result.publishLatencyAvg.add(reportHistogram.getMean() / 1000.0);
            result.publishLatency50pct.add(reportHistogram.getValueAtPercentile(50) / 1000.0);
            result.publishLatency75pct.add(reportHistogram.getValueAtPercentile(75) / 1000.0);
            result.publishLatency95pct.add(reportHistogram.getValueAtPercentile(95) / 1000.0);
            result.publishLatency99pct.add(reportHistogram.getValueAtPercentile(99) / 1000.0);
            result.publishLatency999pct.add(reportHistogram.getValueAtPercentile(99.9) / 1000.0);
            result.publishLatency9999pct.add(reportHistogram.getValueAtPercentile(99.99) / 1000.0);
            result.publishLatencyMax.add(reportHistogram.getMaxValue() / 1000.0);

            reportHistogram.reset();

            if (now >= testEndTime && !needToWaitForBacklogDraining) {
                testCompleted = true;
                reportHistogram = cumulativeRecorder.getIntervalHistogram();
                log.info(
                        "----- Aggregated Pub Latency (ms) avg: {} - 50%: {} - 95%: {} - 99%: {} - 99.9%: {} - 99.99%: {} - Max: {}",
                        dec.format(reportHistogram.getMean() / 1000.0),
                        dec.format(reportHistogram.getValueAtPercentile(50) / 1000.0),
                        dec.format(reportHistogram.getValueAtPercentile(95) / 1000.0),
                        dec.format(reportHistogram.getValueAtPercentile(99) / 1000.0),
                        dec.format(reportHistogram.getValueAtPercentile(99.9) / 1000.0),
                        dec.format(reportHistogram.getValueAtPercentile(99.99) / 1000.0),
                        throughputFormat.format(reportHistogram.getMaxValue() / 1000.0));

                result.aggregatedPublishLatencyAvg = reportHistogram.getMean() / 1000.0;
                result.aggregatedPublishLatency50pct = reportHistogram.getValueAtPercentile(50) / 1000.0;
                result.aggregatedPublishLatency75pct = reportHistogram.getValueAtPercentile(75) / 1000.0;
                result.aggregatedPublishLatency95pct = reportHistogram.getValueAtPercentile(95) / 1000.0;
                result.aggregatedPublishLatency99pct = reportHistogram.getValueAtPercentile(99) / 1000.0;
                result.aggregatedPublishLatency999pct = reportHistogram.getValueAtPercentile(99.9) / 1000.0;
                result.aggregatedPublishLatency9999pct = reportHistogram.getValueAtPercentile(99.99) / 1000.0;
                result.aggregatedPublishLatencyMax = reportHistogram.getMaxValue() / 1000.0;

                reportHistogram.percentiles(100).forEach(value -> {
                    result.aggregatedPublishLatencyQuantiles.put(value.getPercentile(),
                            value.getValueIteratedTo() / 1000.0);
                });

                break;
            }

            oldTime = now;
        }

        return result;
    }

    @Override
    public void messageReceived(byte[] data) {
        messagesReceived.increment();
        totalMessagesReceived.increment();
        bytesReceived.add(data.length);
    }

    private static final DecimalFormat rateFormat = new PaddingDecimalFormat("0.0", 7);
    private static final DecimalFormat throughputFormat = new PaddingDecimalFormat("0.0", 4);
    private static final DecimalFormat dec = new PaddingDecimalFormat("0.0", 4);

    private static final Random random = new Random();

    private static final String getRandomString() {
        byte[] buffer = new byte[5];
        random.nextBytes(buffer);
        return BaseEncoding.base64Url().omitPadding().encode(buffer);
    }

    private static final String[] randomKeys = new String[10000];

    static {
        // Generate a number of random keys to be used when publishing
        byte[] buffer = new byte[7];
        for (int i = 0; i < randomKeys.length; i++) {
            random.nextBytes(buffer);
            randomKeys[i] = BaseEncoding.base64Url().omitPadding().encode(buffer);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkloadGenerator.class);
}
