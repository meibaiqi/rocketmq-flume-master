/**
 * Copyright 2006-2014 handu.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ju51.data.flume.source.rocketmq;

import com.alibaba.rocketmq.client.consumer.MQPullConsumer;
import com.alibaba.rocketmq.client.consumer.PullResult;
import com.alibaba.rocketmq.client.consumer.PullStatus;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接收消息
 *
 * @author Jinkai.Ma<majinkai@handu.com>
 * @since 2015-01-16
 */
public class RocketMQSource extends AbstractSource implements Configurable, PollableSource {

    private static final Logger LOG = LoggerFactory.getLogger(RocketMQSource.class);

    private String topic;
    private String tags;
    private int maxNums;
    private MQPullConsumer consumer;

    @Override
    public void configure(Context context) {
        // 初始化配置项
        topic = Preconditions.checkNotNull(context.getString(RocketMQSourceUtil.TOPIC_CONFIG), "RocketMQ topic must be specified. For example: a1.sources.r1.topic=TestTopic");
        tags = context.getString(RocketMQSourceUtil.TAGS_CONFIG, RocketMQSourceUtil.TAGS_DEFAULT);
        maxNums = context.getInteger(RocketMQSourceUtil.MAXNUMS_CONFIG, RocketMQSourceUtil.MAXNUMS_DEFAULT);

        // 初始化Consumer
        consumer = Preconditions.checkNotNull(RocketMQSourceUtil.getConsumer(context));
    }

    @Override
    public Status process() throws EventDeliveryException {
        List<Event> eventList = Lists.newArrayList();
        Event event;
        Map<String, String> headers;
        try {
            Set<MessageQueue> mqs = Preconditions.checkNotNull(consumer.fetchSubscribeMessageQueues(topic));
            for (MessageQueue mq : mqs) {
                // 获取offset
                long offset = this.getMessageQueueOffset(mq);
                PullResult pullResult = consumer.pull(mq, tags, offset, maxNums);
                // 发现新消息写入Event
                if (pullResult.getPullStatus() == PullStatus.FOUND) {
                    for (MessageExt messageExt : pullResult.getMsgFoundList()) {
                        event = new SimpleEvent();
                        headers = new HashMap<String, String>();
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("MessageQueue={}, Message: {}", mq, messageExt.getBody());
                        }
                        event.setBody(messageExt.getBody());
                        event.setHeaders(headers);
                        eventList.add(event);
                    }
                    // 更新offset
                    this.putMessageQueueOffset(mq, pullResult.getNextBeginOffset());
                }
            }
        } catch (Exception e) {
            LOG.error("RocketMQSource consume message exception", e);
            return Status.BACKOFF;
        }
        // 批量处理事件
        getChannelProcessor().processEventBatch(eventList);
        return Status.READY;
    }

    @Override
    public synchronized void start() {
        try {
            // 启动Consumer
            consumer.start();
        } catch (MQClientException e) {
            LOG.error("RocketMQSource start consumer failed", e);
            Throwables.propagate(e);
        }
        super.start();
    }

    @Override
    public synchronized void stop() {
        // 停止Consumer
        consumer.shutdown();
        super.stop();
    }

    private void putMessageQueueOffset(MessageQueue mq, long offset) throws MQClientException {
        // 存储Offset，客户端每隔5s会定时刷新到Broker或者写入本地缓存文件
        consumer.updateConsumeOffset(mq, offset);
    }

    private long getMessageQueueOffset(MessageQueue mq) throws MQClientException {
        // 从Broker获取Offset
        long offset = consumer.fetchConsumeOffset(mq, false);

        if (offset < 0L) {
            offset = 0L;
        }
        return offset;
    }

}
