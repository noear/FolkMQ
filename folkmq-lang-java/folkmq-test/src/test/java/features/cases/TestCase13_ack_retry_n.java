package features.cases;

import org.noear.folkmq.FolkMQ;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.borker.MqBorkerDefault;
import org.noear.folkmq.borker.MqBorkerInternal;
import org.noear.folkmq.borker.MqQueue;

import java.util.concurrent.CountDownLatch;

/**
 * @author noear
 * @since 1.0
 */
public class TestCase13_ack_retry_n extends BaseTestCase {
    public TestCase13_ack_retry_n(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqBorkerDefault()
                .start(getPort());

        //客户端
        CountDownLatch countDownLatch = new CountDownLatch(5);

        client = FolkMQ.createClient("folkmq://127.0.0.1:" + getPort())
                .autoAcknowledge(false)
                .connect();

        client.subscribe("demo", "a", ((message) -> {
            System.out.println(message);

            if(message.getTimes() > 0) {
                message.acknowledge(true);
                countDownLatch.countDown();
            }else{
                message.acknowledge(false);
            }
        }));

        client.publishAsync("demo", new MqMessage("demo1"));
        client.publishAsync("demo",  new MqMessage("demo2"));
        client.publishAsync("demo",  new MqMessage("demo3"));
        client.publishAsync("demo",  new MqMessage("demo4"));
        client.publishAsync("demo",  new MqMessage("demo5"));

        countDownLatch.await();

        //检验客户端
        assert countDownLatch.getCount() == 0;

        Thread.sleep(100);

        //检验服务端
        MqBorkerInternal serverInternal = server.getServerInternal();
        System.out.println("server topicConsumerMap.size=" + serverInternal.getQueueMap().size());
        assert serverInternal.getQueueMap().size() == 1;

        MqQueue topicConsumerQueue = serverInternal.getQueueMap().values().toArray(new MqQueue[1])[0];
        System.out.println("server topicConsumerQueue.size=" + topicConsumerQueue.messageTotal());
        assert topicConsumerQueue.messageTotal() == 0;
        assert topicConsumerQueue.messageTotal2() == 0;
    }
}
