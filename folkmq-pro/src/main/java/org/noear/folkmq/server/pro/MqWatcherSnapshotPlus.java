package org.noear.folkmq.server.pro;


import org.noear.folkmq.server.MqMessageHolder;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.utils.RunUtils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.LongAdder;

/**
 * 消息观察者 - 快照持久化（增加实现策略）
 * <br/>
 * 持久化定位为副本，只要重启时能恢复订阅关系与消息即可
 *
 * @author noear
 * @since 1.0
 */
public class MqWatcherSnapshotPlus extends MqWatcherSnapshot{
    private final LongAdder save900Count;
    private final LongAdder save300Count;
    private final LongAdder save100Count;

    private final ScheduledFuture<?> save900Future;
    private final ScheduledFuture<?> save300Future;
    private final ScheduledFuture<?> save100Future;

    protected long save900Condition = 1L;
    protected long save300Condition = 10L;
    protected long save100Condition = 10000L;

    public MqWatcherSnapshotPlus() {
        this(null);
    }

    public MqWatcherSnapshotPlus(String dataPath) {
        super(dataPath);

        this.save900Count = new LongAdder();
        this.save300Count = new LongAdder();
        this.save100Count = new LongAdder();

        int fixedDelay900 = 1000 * 900; //900秒
        this.save900Future = RunUtils.scheduleWithFixedDelay(this::onSave900, fixedDelay900, fixedDelay900);

        int fixedDelay300 = 1000 * 300; //300秒
        this.save300Future = RunUtils.scheduleWithFixedDelay(this::onSave300, fixedDelay300, fixedDelay300);

        int fixedDelay100 = 1000 * 100; //100秒
        this.save100Future = RunUtils.scheduleWithFixedDelay(this::onSave100, fixedDelay100, fixedDelay100);
    }

    public MqWatcherSnapshotPlus save900Condition(long save900Condition) {
        if (save900Condition >= 1L) {
            this.save900Condition = save900Condition;
        }
        return this;
    }

    public MqWatcherSnapshotPlus save300Condition(long save300Condition) {
        if (save300Condition >= 1L) {
            this.save300Condition = save300Condition;
        }

        return this;
    }

    public MqWatcherSnapshotPlus save100Condition(long save100Condition) {
        if (save100Condition >= 1L) {
            this.save100Condition = save100Condition;
        }

        return this;
    }

    public long getSave900Count() {
        return save900Count.longValue();
    }

    public long getSave300Count() {
        return save300Count.longValue();
    }

    public long getSave100Count() {
        return save100Count.longValue();
    }

    private void onSave900() {
        long count = save900Count.sumThenReset();

        if (count >= save900Condition) {
            onSave();
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No trigger save900 condition!");
            }
        }
    }

    private void onSave300() {
        long count = save300Count.sumThenReset();

        if (count >= save300Condition) {
            onSave();
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No trigger save300 condition!");
            }
        }
    }

    private void onSave100() {
        long count = save100Count.sumThenReset();

        if (count >= save100Condition) {
            onSave();
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No trigger save100 condition!");
            }
        }
    }

    @Override
    public void onStopBefore() {
        if (save900Future != null) {
            save900Future.cancel(false);
        }

        if (save300Future != null) {
            save300Future.cancel(false);
        }

        if (save100Future != null) {
            save100Future.cancel(false);
        }
    }

    @Override
    public void onSubscribe(String topic, String consumer, Session session) {
        super.onSubscribe(topic, consumer, session);
        onChange();
    }

    @Override
    public void onUnSubscribe(String topic, String consumer, Session session) {
        super.onUnSubscribe(topic, consumer, session);
        onChange();
    }

    @Override
    public void onPublish(Message message) {
        super.onPublish(message);
        onChange();
    }

    @Override
    public void onAcknowledge(String consumer, MqMessageHolder messageHolder, boolean isOk) {
        super.onAcknowledge(consumer, messageHolder, isOk);
        onChange();
    }

    private void onChange() {
        //记数
        save900Count.increment();
        save300Count.increment();
        save100Count.increment();
    }
}
