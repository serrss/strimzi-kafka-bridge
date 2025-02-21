/*
 * Copyright 2016, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.amqp;

import io.strimzi.kafka.bridge.EmbeddedFormat;
import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SourceBridgeEndpoint;
import io.strimzi.kafka.bridge.config.BridgeConfig;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonLink;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Class in charge for handling incoming AMQP traffic
 * from senders and bridging into Apache Kafka
 */
public class AmqpSourceBridgeEndpoint<K, V> extends SourceBridgeEndpoint<K, V> {

    // converter from AMQP message to ConsumerRecord
    private MessageConverter<K, V, Message, Collection<Message>> converter;

    // receiver link for handling incoming message
    private Map<String, ProtonReceiver> receivers;

    public AmqpSourceBridgeEndpoint(Vertx vertx, BridgeConfig bridgeConfig,
                                    EmbeddedFormat format, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        super(vertx, bridgeConfig, format, keySerializer, valueSerializer);
        this.receivers = new HashMap<>();
    }

    @Override
    public void close() {

        if (this.receivers != null) {
            this.receivers.forEach((name, receiver) -> {
                receiver.close();
            });
            this.receivers.clear();
        }

        // close Kafka related stuff
        super.close();
    }

    @Override
    public void handle(Endpoint<?> endpoint) {

        ProtonLink<?> link = (ProtonLink<?>) endpoint.get();
        AmqpConfig amqpConfig = (AmqpConfig) this.bridgeConfig.getAmqpConfig();

        if (!(link instanceof ProtonReceiver)) {
            throw new IllegalArgumentException("This Proton link must be a receiver");
        }

        if (this.converter == null) {
            try {
                this.converter = (MessageConverter<K, V, Message, Collection<Message>>) AmqpBridge.instantiateConverter(amqpConfig.getMessageConverter());
            } catch (AmqpErrorConditionException e) {
                AmqpBridge.detachWithError(link, e.toCondition());
                return;
            }
        }

        ProtonReceiver receiver = (ProtonReceiver) link;
        this.name = receiver.getName();

        // the delivery state is related to the acknowledgement from Apache Kafka
        receiver.setTarget(receiver.getRemoteTarget())
                .setAutoAccept(false)
                .closeHandler(ar -> {
                    if (ar.succeeded()) {
                        this.processCloseReceiver(ar.result());
                    }
                })
                .detachHandler(ar -> {
                    this.processCloseReceiver(receiver);
                })
                .handler((delivery, message) -> {
                    this.processMessage(receiver, delivery, message);
                });

        if (receiver.getRemoteQoS() == ProtonQoS.AT_MOST_ONCE) {
            // sender settle mode is SETTLED (so AT_MOST_ONCE QoS), we assume Apache Kafka
            // no problem in throughput terms so use prefetch due to no ack from Kafka server
            receiver.setPrefetch(amqpConfig.getFlowCredit());
        } else {
            // sender settle mode is UNSETTLED (or MIXED) (so AT_LEAST_ONCE QoS).
            // Thanks to the ack from Kafka server we can modulate flow control
            receiver.setPrefetch(0)
                    .flow(amqpConfig.getFlowCredit());
        }

        receiver.open();

        this.receivers.put(receiver.getName(), receiver);
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {

    }

    /**
     * Send an "accepted" delivery to the AMQP remote sender
     *
     * @param linkName AMQP link name
     * @param delivery AMQP delivery
     */
    private void acceptedDelivery(String linkName, ProtonDelivery delivery) {

        delivery.disposition(Accepted.getInstance(), true);
        log.debug("Delivery sent [accepted] on link {}", linkName);
    }

    /**
     * Send a "rejected" delivery to the AMQP remote sender
     *
     * @param linkName AMQP link name
     * @param delivery AMQP delivery
     * @param cause exception related to the rejection cause
     */
    private void rejectedDelivery(String linkName, ProtonDelivery delivery, Throwable cause) {

        Rejected rejected = new Rejected();
        rejected.setError(new ErrorCondition(Symbol.valueOf(AmqpBridge.AMQP_ERROR_SEND_TO_KAFKA),
                cause.getMessage()));
        delivery.disposition(rejected, true);
        log.debug("Delivery sent [rejected] on link {}", linkName);
    }

    /**
     * Process the message received on the related receiver link
     *
     * @param receiver Proton receiver instance
     * @param delivery Proton delivery instance
     * @param message AMQP message received
     */
    private void processMessage(ProtonReceiver receiver, ProtonDelivery delivery, Message message) {

        // replace unsupported "/" (in a topic name in Kafka) with "."
        String kafkaTopic = (receiver.getTarget().getAddress() != null) ?
                receiver.getTarget().getAddress().replace('/', '.') :
                null;

        KafkaProducerRecord<K, V> krecord = this.converter.toKafkaRecord(kafkaTopic, null, message);

        if (delivery.remotelySettled()) {

            // message settled (by sender), no feedback need by Apache Kafka, no disposition to be sent
            this.send(krecord, null);

        } else {
            // message unsettled (by sender), feedback needed by Apache Kafka, disposition to be sent accordingly
            this.send(krecord, writeResult -> {

                if (writeResult.failed()) {

                    Throwable exception = writeResult.cause();
                    // record not delivered, send REJECTED disposition to the AMQP sender
                    log.error("Error on delivery to Kafka {}", exception.getMessage());
                    this.rejectedDelivery(receiver.getName(), delivery, exception);

                } else {

                    RecordMetadata metadata = writeResult.result();
                    // record delivered, send ACCEPTED disposition to the AMQP sender
                    log.debug("Delivered to Kafka on topic {} at partition {} [{}]", metadata.getTopic(), metadata.getPartition(), metadata.getOffset());
                    this.acceptedDelivery(receiver.getName(), delivery);
                }
            });
        }
    }

    /**
     * Handle for detached link by the remote sender
     * @param receiver Proton receiver instance
     */
    private void processCloseReceiver(ProtonReceiver receiver) {

        log.info("Remote AMQP sender detached");

        // close and remove the receiver link
        receiver.close();
        this.receivers.remove(receiver.getName());

        // if the source endpoint has no receiver links, it can be closed
        if (this.receivers.isEmpty()) {
            this.close();
        }
    }
}
