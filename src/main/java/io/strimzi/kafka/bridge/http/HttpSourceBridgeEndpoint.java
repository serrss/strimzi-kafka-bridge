/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.kafka.bridge.http;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.strimzi.kafka.bridge.BridgeContentType;
import io.strimzi.kafka.bridge.EmbeddedFormat;
import io.strimzi.kafka.bridge.Endpoint;
import io.strimzi.kafka.bridge.SourceBridgeEndpoint;
import io.strimzi.kafka.bridge.config.BridgeConfig;
import io.strimzi.kafka.bridge.converter.MessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpBinaryMessageConverter;
import io.strimzi.kafka.bridge.http.converter.HttpJsonMessageConverter;
import io.strimzi.kafka.bridge.http.model.HttpBridgeError;
import io.strimzi.kafka.bridge.http.model.HttpBridgeResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HttpSourceBridgeEndpoint<K, V> extends SourceBridgeEndpoint<K, V> {

    private MessageConverter<K, V, Buffer, Buffer> messageConverter;
    private boolean closing;

    public HttpSourceBridgeEndpoint(Vertx vertx, BridgeConfig bridgeConfig,
                                    EmbeddedFormat format, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
        super(vertx, bridgeConfig, format, keySerializer, valueSerializer);
    }

    @Override
    public void open() {
        this.name = this.bridgeConfig.getBridgeID() == null ? "kafka-bridge-producer-" + UUID.randomUUID() : this.bridgeConfig.getBridgeID() + "-" + UUID.randomUUID();
        this.closing = false;
        super.open();
    }

    @Override
    @SuppressWarnings("checkstyle:NPathComplexity")
    public void handle(Endpoint<?> endpoint) {
        RoutingContext routingContext = (RoutingContext) endpoint.get();

        messageConverter = this.buildMessageConverter();

        String topic = routingContext.pathParam("topicname");

        List<KafkaProducerRecord<K, V>> records;
        Integer partition = null;
        if (routingContext.pathParam("partitionid") != null) {
            try {
                partition = Integer.parseInt(routingContext.pathParam("partitionid"));
            } catch (NumberFormatException ne) {
                HttpBridgeError error = new HttpBridgeError(
                        HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                        "Specified partition is not a valid number");
                HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                        BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
                return;
            }
        }
        try {
            records = messageConverter.toKafkaRecords(topic, partition, routingContext.getBody());
        } catch (Exception e) {
            HttpBridgeError error = new HttpBridgeError(
                    HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    e.getMessage());
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.UNPROCESSABLE_ENTITY.code(),
                    BridgeContentType.KAFKA_JSON, error.toJson().toBuffer());
            return;
        }
        List<HttpBridgeResult<?>> results = new ArrayList<>(records.size());

        // start sending records asynchronously
        List<Future> sendHandlers = new ArrayList<>(records.size());
        for (KafkaProducerRecord<K, V> record : records) {
            Future<RecordMetadata> fut = Future.future();
            sendHandlers.add(fut);
            this.send(record, fut.completer());
        }

        // wait for ALL futures completed
        List<KafkaProducerRecord<K, V>> finalRecords = records;
        CompositeFuture.join(sendHandlers).setHandler(done -> {

            for (int i = 0; i < sendHandlers.size(); i++) {
                // check if, for each future, the sending operation is completed successfully or failed
                if (sendHandlers.get(i).succeeded() && sendHandlers.get(i).result() != null) {
                    RecordMetadata metadata = (RecordMetadata) sendHandlers.get(i).result();
                    log.debug("Delivered record {} to Kafka on topic {} at partition {} [{}]", finalRecords.get(i), metadata.getTopic(), metadata.getPartition(), metadata.getOffset());
                    results.add(new HttpBridgeResult<>(metadata));
                } else {
                    String msg = sendHandlers.get(i).cause().getMessage();
                    int code = handleError(sendHandlers.get(i).cause());
                    log.error("Failed to deliver record {}", finalRecords.get(i), done.cause());
                    results.add(new HttpBridgeResult<>(new HttpBridgeError(code, msg)));
                }
            }
            HttpUtils.sendResponse(routingContext, HttpResponseStatus.OK.code(),
                    BridgeContentType.KAFKA_JSON, buildOffsets(results).toBuffer());
            
            if (this.closing) {
                this.close();
            }
        });
    }

    @Override
    public void handle(Endpoint<?> endpoint, Handler<?> handler) {

    }

    private JsonObject buildOffsets(List<HttpBridgeResult<?>> results) {
        JsonObject jsonResponse = new JsonObject();
        JsonArray offsets = new JsonArray();

        for (HttpBridgeResult<?> result : results) {
            JsonObject offset = null;
            if (result.getResult() instanceof RecordMetadata) {
                RecordMetadata metadata = (RecordMetadata) result.getResult();
                offset = new JsonObject()
                        .put("partition", metadata.getPartition())
                        .put("offset", metadata.getOffset());
            } else if (result.getResult() instanceof HttpBridgeError) {
                HttpBridgeError error = (HttpBridgeError) result.getResult();
                offset = error.toJson();
            }
            offsets.add(offset);
        }
        jsonResponse.put("offsets", offsets);
        return jsonResponse;
    }

    private int handleError(Throwable ex) {
        if (ex instanceof TimeoutException && ex.getMessage() != null &&
            ex.getMessage().contains("not present in metadata")) {
            this.closing = true;
            return HttpResponseStatus.NOT_FOUND.code();
        } else {
            return HttpResponseStatus.INTERNAL_SERVER_ERROR.code();
        }
    }

    private MessageConverter<K, V, Buffer, Buffer> buildMessageConverter() {
        switch (this.format) {
            case JSON:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpJsonMessageConverter();
            case BINARY:
                return (MessageConverter<K, V, Buffer, Buffer>) new HttpBinaryMessageConverter();
        }
        return null;
    }
}
