package org.noear.folkmq.server.pro;

import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.server.*;
import org.noear.snack.ONode;
import org.noear.snack.core.Feature;
import org.noear.snack.core.Options;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Flags;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.core.internal.MessageDefault;
import org.noear.socketd.utils.RunUtils;
import org.noear.socketd.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 消息观察者 - 快照持久化（持久化定位为副本，只要重启时能恢复订阅关系与消息即可）
 * <br/>
 * 关键：onStart.., onStop.., onSubscribe, onPublish。
 * 提示：onSubscribe, onPublish 做同步处理（可靠性高），做异步处理（性能高）。具体看场景需求
 *
 * @author noear
 * @since 1.0
 */
public class MqWatcherSnapshot extends MqWatcherDefault {
    private static final Logger log = LoggerFactory.getLogger(MqWatcherSnapshot.class);
    private static final String file_suffix = ".fdb";

    private MqServerInternal serverInternal;
    private File directory;

    public MqWatcherSnapshot() {
        this("./data/fdb/");
    }

    public MqWatcherSnapshot(String dataPath) {
        this.directory = new File(dataPath);

        if (this.directory.exists() == false) {
            this.directory.mkdirs();
        }
    }

    @Override
    public void init(MqServerInternal serverInternal) {
        this.serverInternal = serverInternal;
    }

    @Override
    public synchronized void onStartBefore() {
        loadSubscribeMap();
    }

    @Override
    public void onStartAfter() {
        RunUtils.asyncAndTry(this::loadTopicConsumerQueue);
    }

    /**
     * 加载订阅关系（确保线程安全）
     */
    private void loadSubscribeMap() {
        File subscribeMapFile = new File(directory, "subscribe-map.fdb");
        if (subscribeMapFile.exists() == false) {
            return;
        }

        try {
            String subscribeMapJsonStr = IoUtils.readFile(subscribeMapFile);

            ONode subscribeMapJson = ONode.loadStr(subscribeMapJsonStr);
            for (String topic : subscribeMapJson.obj().keySet()) {
                ONode topicConsumerList = subscribeMapJson.get(topic);
                for (ONode topicConsumer : topicConsumerList.ary()) {
                    String consumer = topicConsumer.getString().split(MqConstants.SEPARATOR_TOPIC_CONSUMER)[1];
                    serverInternal.subscribeDo(topic, consumer, null);
                }
            }

            log.info("Server persistent load subscribeMap completed");
        } catch (Exception e) {
            log.warn("Server persistent load subscribeMap failed", e);
        }
    }

    /**
     * 加载主题消费队列记录（确保线程安全）
     */
    private void loadTopicConsumerQueue() {
        Map<String, Set<String>> subscribeMap = serverInternal.getSubscribeMap();
        if (subscribeMap.size() == 0) {
            return;
        }

        List<String> topicList = new ArrayList<>(subscribeMap.keySet());

        Set<String> topicConsumerSet = new HashSet<>();
        for (String topic : topicList) {
            Set<String> topicConsumerSetTmp = subscribeMap.get(topic);
            if (topicConsumerSetTmp != null) {
                topicConsumerSet.addAll(topicConsumerSetTmp);
            }
        }

        for (String topicConsumer : topicConsumerSet) {
            try {
                loadTopicConsumerQueue1(topicConsumer);

                log.info("Server persistent load messageQueue completed, topicConsumer={}", topicConsumer);
            } catch (Exception e) {
                log.warn("Server persistent load messageQueue failed, topicConsumer={}", topicConsumer, e);
            }
        }
    }

    private boolean loadTopicConsumerQueue1(String topicConsumer) throws IOException {
        String topicConsumerQueueFileName = topicConsumer.replace(MqConstants.SEPARATOR_TOPIC_CONSUMER, "/") + file_suffix;
        File topicConsumerQueueFile = new File(directory, topicConsumerQueueFileName);
        if (topicConsumerQueueFile.exists() == false) {
            return false;
        }

        String topicConsumerQueueJsonStr = IoUtils.readFile(topicConsumerQueueFile);
        ONode topicConsumerQueueJson = ONode.loadStr(topicConsumerQueueJsonStr);

        for (ONode messageJson : topicConsumerQueueJson.ary()) {
            String metaString = messageJson.get("meta").getString();
            String data = messageJson.get("data").getString();

            Entity entity = new StringEntity(data).metaString(metaString);
            Message message = new MessageDefault()
                    .sid(Utils.guid())
                    .flag(Flags.Message)
                    .entity(entity);
            serverInternal.exchangeDo(message);
        }

        return true;
    }


    //////////////////////////////////////////

    @Override
    public void onStopAfter() {
        onSave();
    }

    @Override
    public synchronized void onSave() {
        saveSubscribeMap();
        saveTopicConsumerQueue();
    }

    /**
     * 保存订阅关系（确保线程安全）
     */
    private void saveSubscribeMap() {
        Map<String, Set<String>> subscribeMap = serverInternal.getSubscribeMap();
        if (subscribeMap.size() == 0) {
            return;
        }

        ONode subscribeMapJson = new ONode(Options.def().add(Feature.PrettyFormat)).asObject();
        List<String> topicList = new ArrayList<>(subscribeMap.keySet());
        for (String topic : topicList) {
            List<String> topicConsumerList = new ArrayList<>(subscribeMap.get(topic));
            subscribeMapJson.set(topic, topicConsumerList);
        }
        File subscribeMapFile = new File(directory, "subscribe-map.fdb");

        try {
            if (subscribeMapFile.exists() == false) {
                subscribeMapFile.createNewFile();
            }

            IoUtils.saveFile(subscribeMapFile, subscribeMapJson.toJson());

            log.info("Server persistent saveSubscribeMap completed");
        } catch (Exception e) {
            log.warn("Server persistent saveSubscribeMap failed");
        }
    }

    /**
     * 保存主题消费队列记录（确保线程安全）
     */
    private void saveTopicConsumerQueue() {
        Map<String, Set<String>> subscribeMap = serverInternal.getSubscribeMap();
        if (subscribeMap.size() == 0) {
            return;
        }

        List<String> topicList = new ArrayList<>(subscribeMap.keySet());

        Set<String> topicConsumerSet = new HashSet<>();
        for (String topic : topicList) {
            Set<String> topicConsumerSetTmp = subscribeMap.get(topic);
            if (topicConsumerSetTmp != null) {
                topicConsumerSet.addAll(topicConsumerSetTmp);
            }
        }

        Map<String, MqTopicConsumerQueue> topicConsumerMap = serverInternal.getTopicConsumerMap();

        for (String topicConsumer : topicConsumerSet) {
            MqTopicConsumerQueue topicConsumerQueue = topicConsumerMap.get(topicConsumer);

            try {
                saveTopicConsumerQueue1(topicConsumer, topicConsumerQueue);

                log.info("Server persistent messageQueue completed, topicConsumer={}", topicConsumer);
            } catch (IOException e) {
                log.warn("Server persistent messageQueue failed, topicConsumer={}", topicConsumer, e);
            }
        }

        log.info("Server persistent saveTopicConsumerQueue completed");
    }

    private void saveTopicConsumerQueue1(String topicConsumer, MqTopicConsumerQueue topicConsumerQueue) throws IOException {
        ONode topicConsumerQueueJson = new ONode(Options.def().add(Feature.PrettyFormat)).asArray();

        if (topicConsumerQueue != null) {
            List<MqMessageHolder> messageList = new ArrayList<>(topicConsumerQueue.getMessageMap().values());
            for (MqMessageHolder messageHolder : messageList) {
                if (messageHolder.isDone()) {
                    continue;
                }

                try {
                    Entity entity = messageHolder.getContent();
                    ONode entityJson = topicConsumerQueueJson.addNew();
                    entityJson.set("meta", entity.metaString());
                    entityJson.set("data", entity.dataAsString());
                } catch (IOException e) {
                    log.warn("Server persistent message failed, tid={}", messageHolder.getTid(), e);
                }
            }
        }

        String[] topicConsumerAry = topicConsumer.split(MqConstants.SEPARATOR_TOPIC_CONSUMER);
        File topicConsumerQueueDir = new File(directory, topicConsumerAry[0]);
        if (topicConsumerQueueDir.exists() == false) {
            topicConsumerQueueDir.mkdirs();
        }

        String topicConsumerQueueFileName = topicConsumerAry[0] + "/" + topicConsumerAry[1] + file_suffix;
        File topicConsumerQueueFile = new File(directory, topicConsumerQueueFileName);
        if (topicConsumerQueueFile.exists() == false) {
            topicConsumerQueueFile.createNewFile();
        }

        IoUtils.saveFile(topicConsumerQueueFile, topicConsumerQueueJson.toJson());
    }
}