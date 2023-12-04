package org.noear.folkmq.client;

import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.exception.FolkmqException;
import org.noear.socketd.SocketD;
import org.noear.socketd.exception.SocketdAlarmException;
import org.noear.socketd.exception.SocketdConnectionException;
import org.noear.socketd.transport.client.Client;
import org.noear.socketd.transport.client.ClientConfigHandler;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.listener.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 消息客户端默认实现
 *
 * @author noear
 * @since 1.0
 */
public class MqClientDefault extends EventListener implements MqClientInternal {
    private static final Logger log = LoggerFactory.getLogger(MqClientDefault.class);

    //服务端地址
    private String serverUrl;
    //客户端
    private Client client;
    //客户端会话
    private Session clientSession;
    //客户端配置
    private ClientConfigHandler clientConfigHandler;
    //订阅字典
    private Map<String, MqSubscription> subscriptionMap = new HashMap<>();

    //自动回执
    private boolean autoAcknowledge = true;
    //发布重试
    private int publishRetryTimes = 0;

    public MqClientDefault(String serverUrl) {
        this.serverUrl = serverUrl.replace("folkmq://", "sd:tcp://");

        //接收派发指令
        on(MqConstants.MQ_EVENT_DISTRIBUTE, (s, m) -> {
            MqMessageReceivedImpl message = null;

            try {
                message = new MqMessageReceivedImpl(this, m);
                MqSubscription subscription = subscriptionMap.get(message.getTopic());

                if (subscription != null) {
                    subscription.consume(message);
                }

                //是否自动回执
                if (autoAcknowledge) {
                    acknowledge(message, true);
                }
            } catch (Throwable e) {
                if (message != null) {
                    acknowledge(message, false);
                    log.warn("Client consumer handle error, tid={}", message.getTid(), e);
                } else {
                    log.warn("Client consumer handle error", e);
                }
            }
        });
    }

    @Override
    public MqClient connect() throws IOException {
        client = SocketD.createClient(this.serverUrl);

        if (clientConfigHandler != null) {
            client.config(clientConfigHandler);
        }

        clientSession = client.listen(this).open();

        return this;
    }

    @Override
    public void disconnect() throws IOException {
        if (clientSession != null) {
            clientSession.close();
        }
    }

    @Override
    public MqClient config(ClientConfigHandler configHandler) {
        clientConfigHandler = configHandler;
        return this;
    }

    /**
     * 自动回执
     */
    @Override
    public MqClient autoAcknowledge(boolean auto) {
        this.autoAcknowledge = auto;
        return this;
    }

    @Override
    public MqClient publishRetryTimes(int times) {
        this.publishRetryTimes = times;
        return this;
    }

    /**
     * 订阅主题
     *
     * @param topic           主题
     * @param consumer        消费者（实例 ip 或 集群 name）
     * @param consumerHandler 消费处理
     */
    @Override
    public void subscribe(String topic, String consumer, MqConsumeHandler consumerHandler) throws IOException {
        MqSubscription subscription = new MqSubscription(topic, consumer, consumerHandler);

        //支持Qos1
        subscriptionMap.put(topic, subscription);

        if (clientSession != null && clientSession.isValid()) {
            Entity entity = new StringEntity("")
                    .meta(MqConstants.MQ_META_TOPIC, subscription.getTopic())
                    .meta(MqConstants.MQ_META_CONSUMER, subscription.getConsumer())
                    .at(MqConstants.BROKER_AT_SERVER_ALL);

            clientSession.sendAndRequest(MqConstants.MQ_EVENT_SUBSCRIBE, entity);

            log.info("Client subscribe successfully: {}#{}", topic, consumer);
        }
    }

    @Override
    public void unsubscribe(String topic, String consumer) throws IOException {
        subscriptionMap.remove(topic);

        if (clientSession != null && clientSession.isValid()) {
            Entity entity = new StringEntity("")
                    .meta(MqConstants.MQ_META_TOPIC, topic)
                    .meta(MqConstants.MQ_META_CONSUMER, consumer)
                    .at(MqConstants.BROKER_AT_SERVER_ALL);

            clientSession.sendAndRequest(MqConstants.MQ_EVENT_UNSUBSCRIBE, entity);

            log.info("Client unsubscribe successfully: {}#{}", topic, consumer);
        }
    }

    @Override
    public void publish(String topic, IMqMessage message) throws IOException {
        if (clientSession == null) {
            throw new SocketdConnectionException("Not connected!");
        }

        Entity entity = publishEntityBuild(topic, message);

        if (message.getQos() > 0) {
            //::Qos1
            if (publishRetryTimes > 0) {
                //采用同步 + 重试支持
                int times = publishRetryTimes;
                while (times > 0) {
                    try {
                        Entity resp = clientSession.sendAndRequest(MqConstants.MQ_EVENT_PUBLISH, entity);
                        int confirm = Integer.parseInt(resp.metaOrDefault(MqConstants.MQ_META_CONFIRM, "0"));
                        if (confirm == 1) {
                            break;
                        } else {
                            String messsage = "Client message publish confirm failed: " + resp.dataAsString();
                            throw new FolkmqException(messsage);
                        }
                    } catch (Throwable e) {
                        times--;
                        if (times == 0) {
                            throw e;
                        }
                    }
                }
            } else {

            }
        } else {
            //::Qos0
            clientSession.send(MqConstants.MQ_EVENT_PUBLISH, entity);
        }
    }

    /**
     * 发布消息
     *
     * @param topic   主题
     * @param message 消息
     */
    @Override
    public CompletableFuture<Boolean> publishAsync(String topic, IMqMessage message) throws IOException {
        if (clientSession == null) {
            throw new SocketdConnectionException("Not connected!");
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Entity entity = publishEntityBuild(topic, message);

        if (message.getQos() > 0) {
            //采用异常 + 可选等待
            clientSession.sendAndRequest(MqConstants.MQ_EVENT_PUBLISH, entity, r -> {
                int confirm = Integer.parseInt(r.metaOrDefault(MqConstants.MQ_META_CONFIRM, "0"));
                if (confirm == 1) {
                    future.complete(true);
                } else {
                    String messsage = "Client message publish confirm failed: " + r.dataAsString();
                    future.completeExceptionally(new FolkmqException(messsage));
                }
            });
        } else {
            //::Qos0
            clientSession.send(MqConstants.MQ_EVENT_PUBLISH, entity);
            future.complete(true);
        }

        return future;
    }

    private Entity publishEntityBuild(String topic, IMqMessage message) {
        //构建消息实体
        StringEntity entity = new StringEntity(message.getContent());
        entity.meta(MqConstants.MQ_META_TID, message.getTid());
        entity.meta(MqConstants.MQ_META_TOPIC, topic);
        entity.meta(MqConstants.MQ_META_QOS, (message.getQos() == 0 ? "0" : "1"));
        if (message.getScheduled() == null) {
            entity.meta(MqConstants.MQ_META_SCHEDULED, "0");
        } else {
            entity.meta(MqConstants.MQ_META_SCHEDULED, String.valueOf(message.getScheduled().getTime()));
        }
        entity.at(MqConstants.BROKER_AT_SERVER);

        return entity;
    }

    /**
     * 消费回执
     *
     * @param message 收到的消息
     * @param isOk    回执
     */
    @Override
    public void acknowledge(MqMessageReceivedImpl message, boolean isOk) throws IOException {
        //发送“回执”，向服务端反馈消费情况
        if (message.getQos() > 0) {
            //此处用 replyEnd 不安全，时间长久可能会话断连过（流就无效了）
            clientSession.replyEnd(message.from, new StringEntity("")
                    .meta(MqConstants.MQ_META_ACK, isOk ? "1" : "0"));
        }
    }

    /**
     * 会话打开时
     */
    @Override
    public void onOpen(Session session) throws IOException {
        super.onOpen(session);

        log.info("Client session opened, sessionId={}", session.sessionId());

        //用于重连时重新订阅
        for (MqSubscription subscription : subscriptionMap.values()) {
            Entity entity = new StringEntity("")
                    .meta(MqConstants.MQ_META_TOPIC, subscription.getTopic())
                    .meta(MqConstants.MQ_META_CONSUMER, subscription.getConsumer())
                    .at(MqConstants.BROKER_AT_SERVER);

            session.send(MqConstants.MQ_EVENT_SUBSCRIBE, entity);
        }
    }

    /**
     * 会话关闭时
     */
    @Override
    public void onClose(Session session) {
        super.onClose(session);

        log.info("Client session closed, sessionId={}", session.sessionId());
    }

    /**
     * 会话出错时
     */
    @Override
    public void onError(Session session, Throwable error) {
        super.onError(session, error);

        if (log.isWarnEnabled()) {
            if (error instanceof SocketdAlarmException) {
                SocketdAlarmException alarmException = (SocketdAlarmException) error;
                log.warn("Client error, sessionId={}, from={}", session.sessionId(), alarmException.getFrom(), error);
            } else {
                log.warn("Client error, sessionId={}", session.sessionId(), error);
            }
        }
    }
}