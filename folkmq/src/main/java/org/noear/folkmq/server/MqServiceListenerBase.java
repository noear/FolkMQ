package org.noear.folkmq.server;

import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.common.MqResolver;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.listener.EventListener;
import org.noear.socketd.utils.StrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author noear
 * @since 1.1
 */
public abstract class MqServiceListenerBase extends EventListener implements MqServiceInternal {
    protected static final Logger log = LoggerFactory.getLogger(MqServiceListener.class);


    //观察者
    protected MqWatcher watcher;
    //群集模式（有经理人的模式）
    protected boolean brokerMode;
    //订阅锁
    protected final Object SUBSCRIBE_LOCK = new Object();
    //所有会话
    protected final Map<String, Session> sessionAllMap = new ConcurrentHashMap<>();
    //服务端访问账号
    protected final Map<String, String> serverAccessMap = new ConcurrentHashMap<>();

    //订阅关系(topic=>[queueName]) //queueName='topic#consumer'
    protected final Map<String, Set<String>> subscribeMap = new ConcurrentHashMap<>();
    //队列字典(queueName=>Queue)
    protected final Map<String, MqQueue> queueMap = new ConcurrentHashMap<>();
    //预备消息
    protected final Map<String, String> readyMessageMap = new ConcurrentHashMap<>();

    //派发线程
    protected Thread distributeThread;

    protected final AtomicBoolean isStarted = new AtomicBoolean(false);

    /**
     * 获取所有会话
     */
    @Override
    public Collection<Session> getSessionAll() {
        return sessionAllMap.values();
    }

    /**
     * 获取所有会话数量
     */
    @Override
    public int getSessionCount() {
        return sessionAllMap.size();
    }

    /**
     * 获取订阅集合
     */
    @Override
    public Map<String, Set<String>> getSubscribeMap() {
        return Collections.unmodifiableMap(subscribeMap);
    }

    /**
     * 获取队列集合
     */
    @Override
    public Map<String, MqQueue> getQueueMap() {
        return Collections.unmodifiableMap(queueMap);
    }

    /**
     * 获取队列
     * */
    @Override
    public MqQueue getQueue(String queueName) {
        return queueMap.get(queueName);
    }

    /**
     * 移除队列
     */
    @Override
    public void removeQueue(String queueName) {
        //先删订阅关系
        String[] ss = queueName.split(MqConstants.SEPARATOR_TOPIC_CONSUMER_GROUP);
        Set<String> tmp = subscribeMap.get(ss[0]);
        tmp.remove(queueName);

        //再删队列
        queueMap.remove(queueName);
    }

    /**
     * 执行订阅
     */
    @Override
    public void subscribeDo(String topic, String consumerGroup, Session session) {
        String queueName = topic + MqConstants.SEPARATOR_TOPIC_CONSUMER_GROUP + consumerGroup;

        synchronized (SUBSCRIBE_LOCK) {
            //::1.构建订阅关系，并获取队列
            MqQueue queue = queueGetOrInit(topic, consumerGroup, queueName);


            //::2.标识会话身份（从持久层恢复时，会话可能为 null）

            if (session != null) {
                log.info("Server channel subscribe topic={}, consumerGroup={}, sessionId={}", topic, consumerGroup, session.sessionId());

                //会话绑定队列（可以绑定多个队列）
                session.attrPut(queueName, "1");

                //加入队列会话
                queue.addSession(session);
            }
        }
    }

    protected MqQueue queueGetOrInit(String topic, String consumerGroup, String queueName) {
        //建立订阅关系(topic=>[queueName]) //queueName='topic#consumer'
        Set<String> queueNameSet = subscribeMap.computeIfAbsent(topic, n -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        queueNameSet.add(queueName);

        //队列映射关系(queueName=>Queue)
        MqQueue queue = queueMap.get(queueName);
        if (queue == null) {
            queue = new MqQueueDefault((MqServiceListener) this, watcher, topic, consumerGroup, queueName);
            queueMap.put(queueName, queue);
        }

        return queue;
    }

    /**
     * 执行取消订阅
     */
    @Override
    public void unsubscribeDo(String topic, String consumerGroup, Session session) {
        if (session == null) {
            return;
        }

        log.info("Server channel unsubscribe topic={}, consumerGroup={}, sessionId={}", topic, consumerGroup, session.sessionId());

        String queueName = topic + MqConstants.SEPARATOR_TOPIC_CONSUMER_GROUP + consumerGroup;

        //1.获取相关队列
        MqQueue queue = queueMap.get(queueName);

        //2.移除队列绑定
        session.attrMap().remove(queueName);

        //3.退出队列会话
        if (queue != null) {
            queue.removeSession(session);
        }
    }

    /**
     * 执行路由
     */
    @Override
    public void routingDo(MqResolver mr, Message message) {
        //复用解析
        String sender = mr.getSender(message);
        String tid = mr.getTid(message);
        String topic = mr.getTopic(message);
        int qos = mr.getQos(message);
        int times = mr.getTimes(message);
        long expiration = mr.getExpiration(message);
        long scheduled = mr.getScheduled(message);
        boolean sequence = mr.isSequence(message);
        boolean transaction = mr.isTransaction(message);

        if (scheduled == 0) {
            //默认为当前ms（相对于后面者，有个排序作用）
            scheduled = System.currentTimeMillis();
        }


        //取出所有订阅的主题消费者
        Set<String> topicConsumerSet = subscribeMap.get(topic);

        if (topicConsumerSet != null) {
            //避免遍历 Set 时，出现 add or remove 而异常
            List<String> topicConsumerList = new ArrayList<>(topicConsumerSet);

            for (String topicConsumer : topicConsumerList) {
                if (topicConsumer.endsWith(MqConstants.MQ_TRAN_CONSUMER_GROUP2)) {
                    //避免重复进入事务缓存队列
                    continue;
                }

                routingDo(mr, topicConsumer, message, tid, qos, sequence, expiration, transaction, sender, times, scheduled);
            }
        }
    }

    protected void routingToQueue(MqResolver mr, Message message, String queueName) {
        //复用解析
        String sender = mr.getSender(message);
        String tid = mr.getTid(message);
        int qos = mr.getQos(message);
        int times = mr.getTimes(message);
        long expiration = mr.getExpiration(message);
        long scheduled = mr.getScheduled(message);
        boolean sequence = mr.isSequence(message);
        boolean transaction = mr.isTransaction(message);

        if (scheduled == 0) {
            //默认为当前ms（相对于后面者，有个排序作用）
            scheduled = System.currentTimeMillis();
        }

        //取出所有订阅的主题消费者
        routingDo(mr, queueName, message, tid, qos, sequence, expiration, transaction, sender, times, scheduled);
    }

    /**
     * 执行路由
     */
    public void routingDo(MqResolver mr, String queueName, Message message, String tid, int qos, boolean sequence, long expiration, boolean transaction, String sender, int times, long scheduled) {
        MqQueue queue = queueMap.get(queueName);

        if (queue != null) {
            MqMessageHolder messageHolder = new MqMessageHolder(mr, queueName, queue.getConsumerGroup(), message, tid, qos, sequence, expiration, transaction, sender, times, scheduled);
            queue.add(messageHolder);
        }
    }

    /**
     * 执行取消路由
     */
    public void unRoutingDo(Message message) {
        String tid = message.meta(MqConstants.MQ_META_TID);
        //可能是非法消息
        if (StrUtils.isEmpty(tid)) {
            log.warn("The tid cannot be null, sid={}", message.sid());
            return;
        }

        //复用解析
        String topic = message.meta(MqConstants.MQ_META_TOPIC);

        //取出所有订阅的主题消费者
        Set<String> topicConsumerSet = subscribeMap.get(topic);

        if (topicConsumerSet != null) {
            //避免遍历 Set 时，出现 add or remove 而异常
            List<String> topicConsumerList = new ArrayList<>(topicConsumerSet);

            for (String topicConsumer : topicConsumerList) {
                MqQueue queue = queueMap.get(topicConsumer);
                queue.removeAt(tid);
            }
        }
    }

    /**
     * 执行派发
     */
    protected void distributeDo() {
        while (!distributeThread.isInterrupted()) {
            try {
                int count = 0;

                if (isStarted.get()) {
                    List<MqQueue> queueList = new ArrayList<>(queueMap.values());
                    for (MqQueue queue : queueList) {
                        try {
                            if (queue.distribute()) {
                                count++;
                            }
                        } catch (Throwable e) {
                            if (log.isWarnEnabled()) {
                                log.warn("MqQueue take error, queue={}", queue.getQueueName(), e);
                            }
                        }
                    }
                }

                if (count == 0) {
                    //一点消息都没有，就修复下
                    Thread.sleep(100);
                }
            } catch (Throwable e) {
                if (e instanceof InterruptedException == false) {
                    if (log.isWarnEnabled()) {
                        log.warn("MqQueue distribute error", e);
                    }
                }
            }
        }

        if (log.isWarnEnabled()) {
            log.warn("MqQueue take stoped!");
        }
    }
}