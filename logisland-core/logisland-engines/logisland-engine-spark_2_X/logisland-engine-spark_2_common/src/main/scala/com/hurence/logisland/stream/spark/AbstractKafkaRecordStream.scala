/**
 * Copyright (C) 2016 Hurence (support@hurence.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
  * Copyright (C) 2016 Hurence (support@hurence.com)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package com.hurence.logisland.stream.spark

import java.io.ByteArrayInputStream
import java.util
import java.util.Collections

import com.hurence.logisland.component.PropertyDescriptor
import com.hurence.logisland.engine.EngineContext
import com.hurence.logisland.engine.spark.remote.PipelineConfigurationBroadcastWrapper
import com.hurence.logisland.record.Record
import com.hurence.logisland.serializer._
import com.hurence.logisland.stream.StreamProperties._
import com.hurence.logisland.stream.{AbstractRecordStream, StreamContext}
import com.hurence.logisland.util.kafka.KafkaSink
import com.hurence.logisland.util.spark._
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, OffsetAndMetadata, OffsetCommitCallback}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.security.JaasUtils
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.groupon.metrics.UserMetricsSystem
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.kafka010.{CanCommitOffsets, KafkaUtils, OffsetRange}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._


abstract class AbstractKafkaRecordStream extends AbstractRecordStream with SparkRecordStream {


    val NONE_TOPIC: String = "none"
    private val logger = LoggerFactory.getLogger(this.getClass)
    protected var kafkaSink: Broadcast[KafkaSink] = null
    protected var appName: String = ""
    @transient protected var ssc: StreamingContext = null
    protected var streamContext: StreamContext = null
    protected var engineContext: EngineContext = null
    protected var controllerServiceLookupSink: Broadcast[ControllerServiceLookupSink] = null
    protected var needMetricsReset = false

    override def getSupportedPropertyDescriptors: util.List[PropertyDescriptor] = {
        val descriptors: util.List[PropertyDescriptor] = new util.ArrayList[PropertyDescriptor]
        descriptors.add(ERROR_TOPICS)
        descriptors.add(INPUT_TOPICS)
        descriptors.add(OUTPUT_TOPICS)
        descriptors.add(AVRO_INPUT_SCHEMA)
        descriptors.add(AVRO_OUTPUT_SCHEMA)
        descriptors.add(INPUT_SERIALIZER)
        descriptors.add(OUTPUT_SERIALIZER)
        descriptors.add(ERROR_SERIALIZER)
        descriptors.add(KAFKA_TOPIC_AUTOCREATE)
        descriptors.add(KAFKA_TOPIC_DEFAULT_PARTITIONS)
        descriptors.add(KAFKA_TOPIC_DEFAULT_REPLICATION_FACTOR)
        descriptors.add(KAFKA_METADATA_BROKER_LIST)
        descriptors.add(KAFKA_ZOOKEEPER_QUORUM)
        descriptors.add(KAFKA_MANUAL_OFFSET_RESET)
        descriptors.add(KAFKA_BATCH_SIZE)
        descriptors.add(KAFKA_LINGER_MS)
        descriptors.add(KAFKA_ACKS)
        descriptors.add(WINDOW_DURATION)
        descriptors.add(SLIDE_DURATION)
        Collections.unmodifiableList(descriptors)
    }


    override def setup(appName: String, ssc: StreamingContext, streamContext: StreamContext, engineContext: EngineContext) = {
        this.appName = appName
        this.ssc = ssc
        this.streamContext = streamContext
        this.engineContext = engineContext

    }

    override def getStreamContext(): StreamingContext = this.ssc

    override def start() = {
        if (ssc == null)
            throw new IllegalStateException("stream not initialized")

        try {

            // Define the Kafka parameters, broker list must be specified
            val inputTopics = streamContext.getPropertyValue(INPUT_TOPICS).asString.split(",").toSet
            val outputTopics = streamContext.getPropertyValue(OUTPUT_TOPICS).asString.split(",").toSet
            val errorTopics = streamContext.getPropertyValue(ERROR_TOPICS).asString.split(",").toSet
            val metricsTopics = DEFAULT_METRICS_TOPIC.getValue.split(",").toSet

            val topicAutocreate = streamContext.getPropertyValue(KAFKA_TOPIC_AUTOCREATE).asBoolean().booleanValue()
            val topicDefaultPartitions = streamContext.getPropertyValue(KAFKA_TOPIC_DEFAULT_PARTITIONS).asInteger().intValue()
            val topicDefaultReplicationFactor = streamContext.getPropertyValue(KAFKA_TOPIC_DEFAULT_REPLICATION_FACTOR).asInteger().intValue()
            val brokerList = streamContext.getPropertyValue(KAFKA_METADATA_BROKER_LIST).asString
            val zkQuorum = streamContext.getPropertyValue(KAFKA_ZOOKEEPER_QUORUM).asString

            val kafkaBatchSize = streamContext.getPropertyValue(KAFKA_BATCH_SIZE).asString
            val kafkaLingerMs = streamContext.getPropertyValue(KAFKA_LINGER_MS).asString
            val kafkaAcks = streamContext.getPropertyValue(KAFKA_ACKS).asString
            val kafkaOffset = streamContext.getPropertyValue(KAFKA_MANUAL_OFFSET_RESET).asString


            val kafkaSinkParams = Map(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokerList,
                ProducerConfig.CLIENT_ID_CONFIG -> appName,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG -> classOf[ByteArraySerializer].getCanonicalName,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG -> classOf[ByteArraySerializer].getName,
                ProducerConfig.ACKS_CONFIG -> kafkaAcks,
                ProducerConfig.RETRIES_CONFIG -> "3",
                ProducerConfig.LINGER_MS_CONFIG -> kafkaLingerMs,
                ProducerConfig.BATCH_SIZE_CONFIG -> kafkaBatchSize,
                ProducerConfig.RETRY_BACKOFF_MS_CONFIG -> "1000",
                ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG -> "1000")

            kafkaSink = ssc.sparkContext.broadcast(KafkaSink(kafkaSinkParams))
            controllerServiceLookupSink = ssc.sparkContext.broadcast(
                ControllerServiceLookupSink(engineContext.getControllerServiceConfigurations)
            )

            // TODO deprecate topic creation here (must be done through the agent)
            if (topicAutocreate) {
                val zkUtils = ZkUtils.apply(zkQuorum, 10000, 10000, JaasUtils.isZkSecurityEnabled)
                createTopicsIfNeeded(zkUtils, inputTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, outputTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, errorTopics, topicDefaultPartitions, topicDefaultReplicationFactor)
                createTopicsIfNeeded(zkUtils, metricsTopics, 1, 1)
            }


            val kafkaParams = Map[String, Object](
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokerList,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[ByteArrayDeserializer],
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[ByteArrayDeserializer],
                ConsumerConfig.GROUP_ID_CONFIG -> appName,
                ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG -> "50",
                ConsumerConfig.RETRY_BACKOFF_MS_CONFIG -> "100",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> kafkaOffset,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG -> "30000"
                /*,
                ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG -> "5000"*/
            )


            logger.info(s"starting Kafka direct stream on topics $inputTopics from $kafkaOffset offsets")
            @transient val kafkaStream = KafkaUtils.createDirectStream[Array[Byte], Array[Byte]](
                ssc,
                PreferConsistent,
                Subscribe[Array[Byte], Array[Byte]](inputTopics, kafkaParams)
            )

            // do the parallel processing

            val stream = if (streamContext.getPropertyValue(WINDOW_DURATION).isSet) {
                if (streamContext.getPropertyValue(SLIDE_DURATION).isSet)
                    kafkaStream.window(
                        Seconds(streamContext.getPropertyValue(WINDOW_DURATION).asLong()),
                        Seconds(streamContext.getPropertyValue(SLIDE_DURATION).asLong())
                    )
                else
                    kafkaStream.window(Seconds(streamContext.getPropertyValue(WINDOW_DURATION).asLong()))

            } else kafkaStream


            stream
                .foreachRDD(rdd => {

                    this.streamContext.getProcessContexts().clear();
                    this.streamContext.getProcessContexts().addAll(
                        PipelineConfigurationBroadcastWrapper.getInstance().get(this.streamContext.getIdentifier))

                    if (!rdd.isEmpty()) {


                        val offsetRanges = process(rdd)
                        // some time later, after outputs have completed
                        if (offsetRanges.nonEmpty) {
                            // kafkaStream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges.get)


                            kafkaStream.asInstanceOf[CanCommitOffsets].commitAsync(offsetRanges.get, new OffsetCommitCallback() {
                                def onComplete(m: java.util.Map[TopicPartition, OffsetAndMetadata], e: Exception) {
                                    if (null != e) {
                                        logger.error("error commiting offsets", e)
                                    }
                                }
                            })


                            needMetricsReset = true
                        }
                        else if (needMetricsReset) {
                            try {

                                for (partitionId <- 0 to rdd.getNumPartitions) {
                                    val pipelineMetricPrefix = streamContext.getIdentifier + "." +
                                        "partition" + partitionId + "."
                                    val pipelineTimerContext = UserMetricsSystem.timer(pipelineMetricPrefix + "Pipeline.processing_time_ms").time()

                                    streamContext.getProcessContexts.foreach(processorContext => {
                                        UserMetricsSystem.timer(pipelineMetricPrefix + processorContext.getIdentifier + ".processing_time_ms")
                                            .time()
                                            .stop()

                                        ProcessorMetrics.resetMetrics(pipelineMetricPrefix + processorContext.getIdentifier + ".")
                                    })
                                    pipelineTimerContext.stop()
                                }
                            } catch {
                                case ex: Throwable =>
                                    logger.error(s"exception : ${ex.toString}")
                                    None
                            } finally {
                                needMetricsReset = false
                            }
                        }
                    }

                })
        } catch {
            case ex: Throwable =>
                ex.printStackTrace()
                logger.error("something bad happened, please check Kafka or Zookeeper health : {}", ex)
        }
    }


    /**
      * to be overriden by subclasses
      *
      * @param rdd
      */
    def process(rdd: RDD[ConsumerRecord[Array[Byte], Array[Byte]]]): Option[Array[OffsetRange]]


    /**
      * build a serializer
      *
      * @param inSerializerClass the serializer type
      * @param schemaContent     an Avro schema
      * @return the serializer
      */
    def getSerializer(inSerializerClass: String, schemaContent: String): RecordSerializer = {
       SerializerProvider.getSerializer(inSerializerClass, schemaContent)
    }

    /**
      *
      * @param partition
      * @param serializer
      * @return
      */
    def deserializeRecords(partition: Iterator[ConsumerRecord[Array[Byte], Array[Byte]]], serializer: RecordSerializer): List[Record] = {
        partition.flatMap(rawEvent => {
            try {
                val bais = new ByteArrayInputStream(rawEvent.value())
                val deserialized = serializer.deserialize(bais)
                bais.close()

                Some(deserialized)
            } catch {
                case t: Throwable =>
                    logger.error(s"exception while deserializing events ${t.getMessage}")
                    None
            }
        }).toList
    }


    /**
      * Topic creation
      *
      * @param zkUtils
      * @param topics
      * @param topicDefaultPartitions
      * @param topicDefaultReplicationFactor
      */
    def createTopicsIfNeeded(zkUtils: ZkUtils,
                             topics: Set[String],
                             topicDefaultPartitions: Int,
                             topicDefaultReplicationFactor: Int): Unit = {

        topics.foreach(topic => {

            if (!topic.equals(NONE_TOPIC) && !AdminUtils.topicExists(zkUtils, topic)) {
                AdminUtils.createTopic(zkUtils, topic, topicDefaultPartitions, topicDefaultReplicationFactor)
                Thread.sleep(1000)
                logger.info(s"created topic $topic with" +
                    s" $topicDefaultPartitions partitions and" +
                    s" $topicDefaultReplicationFactor replicas")
            }
        })
    }
}


