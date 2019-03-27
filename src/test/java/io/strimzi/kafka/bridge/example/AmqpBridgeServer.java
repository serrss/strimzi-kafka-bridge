/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.kafka.bridge.example;

import io.strimzi.kafka.bridge.amqp.AmqpBridge;
import io.strimzi.kafka.bridge.amqp.AmqpBridgeConfig;
import io.vertx.core.Vertx;

import java.io.IOException;

/**
 * Class example on running the bridge server
 */
public class AmqpBridgeServer {
	
	public static void main(String[] args) {
		
		Vertx vertx = Vertx.vertx();

		AmqpBridgeConfig bridgeConfigProperties = AmqpBridgeConfig.fromMap(System.getenv());
		
		AmqpBridge bridge = new AmqpBridge(bridgeConfigProperties);

		vertx.deployVerticle(bridge);
		
		try {
			System.in.read();
			vertx.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
