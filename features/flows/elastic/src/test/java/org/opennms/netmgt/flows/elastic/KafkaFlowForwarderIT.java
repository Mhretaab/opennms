/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2020 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2020 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.flows.elastic;

import static com.jayway.awaitility.Awaitility.await;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.opennms.core.test.elastic.ElasticSearchRule;
import org.opennms.core.test.elastic.ElasticSearchServerConfig;
import org.opennms.core.test.kafka.JUnitKafkaServer;
import org.opennms.features.jest.client.RestClientFactory;
import org.opennms.features.jest.client.index.IndexStrategy;
import org.opennms.features.jest.client.template.IndexSettings;
import org.opennms.netmgt.dao.mock.MockNodeDao;
import org.opennms.netmgt.dao.mock.MockSessionUtils;
import org.opennms.netmgt.dao.mock.MockSnmpInterfaceDao;
import org.opennms.netmgt.flows.classification.ClassificationEngine;
import org.opennms.netmgt.flows.persistence.KafkaFlowForwarder;
import org.opennms.netmgt.flows.persistence.model.FlowDocument;
import org.osgi.service.cm.ConfigurationAdmin;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;

import io.searchbox.client.JestClient;

public class KafkaFlowForwarderIT {

    private KafkaFlowForwarder flowForwarder;

    private static final String topicName = "flowDocuments";
    private final List<FlowDocument> flowDocuments = new ArrayList<>();
    private Hashtable<String, Object> kafkaConfig = new Hashtable<>();

    @Rule
    public JUnitKafkaServer kafkaServer = new JUnitKafkaServer();

    @Rule
    public ElasticSearchRule elasticSearchRule = new ElasticSearchRule(new ElasticSearchServerConfig()
            .withStartDelay(0)
            .withManualStartup()
    );


    @Before
    public void setup() throws IOException {
        kafkaConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServer.getKafkaConnectString());
        kafkaConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "flow-test");
        kafkaConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        kafkaConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());
        kafkaConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getCanonicalName());
        ConfigurationAdmin configAdmin = mock(ConfigurationAdmin.class, RETURNS_DEEP_STUBS);
        when(configAdmin.getConfiguration(KafkaFlowForwarder.KAFKA_CLIENT_PID).getProperties()).thenReturn(kafkaConfig);
        flowForwarder = new KafkaFlowForwarder(configAdmin);
        flowForwarder.setTopicName(topicName);
        flowForwarder.init();
    }


    @Test(timeout = 60000)
    public void testKafkaPersistenceForFlows() throws Exception {
        // start ES
        elasticSearchRule.startServer();
        final RestClientFactory restClientFactory = new RestClientFactory(elasticSearchRule.getUrl());
        final MockDocumentEnricherFactory mockDocumentEnricherFactory = new MockDocumentEnricherFactory();
        final DocumentEnricher documentEnricher = mockDocumentEnricherFactory.getEnricher();
        final ClassificationEngine classificationEngine = mockDocumentEnricherFactory.getClassificationEngine();
        try (final JestClient jestClient = restClientFactory.createClient()) {
            final ElasticFlowRepository elasticFlowRepository = new ElasticFlowRepository(new MetricRegistry(),
                    jestClient, IndexStrategy.MONTHLY, documentEnricher, classificationEngine,
                    new MockSessionUtils(), new MockNodeDao(), new MockSnmpInterfaceDao(),
                    new MockIdentity(), new MockTracerRegistry(), flowForwarder, new IndexSettings(),
                    3, 12000);
            elasticFlowRepository.setEnableFlowForwarding(true);

            // It does not matter what we persist here, as the response is fixed.
            // We only have to ensure that the list is not empty
            elasticFlowRepository.persist(Lists.newArrayList(FlowDocumentTest.getMockFlow()), FlowDocumentTest.getMockFlowSource());
        }
        KafkaConsumerRunner kafkaConsumerRunner = new KafkaConsumerRunner(kafkaConfig, topicName);
        Executors.newSingleThreadExecutor().execute(kafkaConsumerRunner);
        await().atMost(30, TimeUnit.SECONDS).pollInterval(5, TimeUnit.SECONDS).until(() ->
                getFlowDocuments().size(), Matchers.greaterThan(0));
        elasticSearchRule.stopServer();
        kafkaConsumerRunner.destroy();
    }

    @After
    public void destroy() {
        flowForwarder.destroy();
    }

    public List<FlowDocument> getFlowDocuments() {
        return flowDocuments;
    }

    private class KafkaConsumerRunner implements Runnable {

        private final KafkaConsumer<String, byte[]> kafkaConsumer;
        private final String topic;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public KafkaConsumerRunner(Hashtable properties, String topic) {
            this.kafkaConsumer = new KafkaConsumer<String, byte[]>(properties);
            this.topic = topic;
        }

        @Override
        public void run() {
            try {
                kafkaConsumer.subscribe(Arrays.asList(topic));
                while (!closed.get()) {
                    ConsumerRecords<String, byte[]> records = kafkaConsumer.poll(Duration.ofMillis(1000));
                    records.forEach(consumerRecord -> {
                        try {
                            flowDocuments.add(FlowDocument.parseFrom(consumerRecord.value()));
                        } catch (InvalidProtocolBufferException e) {
                            //pass
                        }
                    });
                }
            } catch (Exception e) {

            }
        }

        public void destroy() {
            closed.set(true);
        }

    }
}
