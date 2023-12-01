package features.cases;

import org.noear.folkmq.client.MqClientDefault;
import org.noear.folkmq.server.MqServerDefault;
import org.noear.folkmq.server.MqServiceInternal;
import org.noear.folkmq.server.MqTopicConsumerQueue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        server = new MqServerDefault()
                .start(getPort());

        //客户端
        CountDownLatch countDownLatch = new CountDownLatch(5);

        client = new MqClientDefault("folkmq://127.0.0.1:" + getPort())
                .autoAcknowledge(false)
                .connect();

        client.subscribe("demo", "a", ((message) -> {
            System.out.println(message);

            if(message.getTimes() > 1) {
                countDownLatch.countDown();
                message.acknowledge(true);
            }else{
                message.acknowledge(false);
            }
        }));

        client.publish("demo", "demo1");
        client.publish("demo", "demo2");
        client.publish("demo", "demo3");
        client.publish("demo", "demo4");
        client.publish("demo", "demo5");

        countDownLatch.await(40, TimeUnit.SECONDS);

        //检验客户端
        assert countDownLatch.getCount() == 0;

        Thread.sleep(100);

        //检验服务端
        MqServiceInternal serverInternal = server.getServerInternal();
        System.out.println("server topicConsumerMap.size=" + serverInternal.getTopicConsumerMap().size());
        assert serverInternal.getTopicConsumerMap().size() == 1;

        MqTopicConsumerQueue topicConsumerQueue = serverInternal.getTopicConsumerMap().values().toArray(new MqTopicConsumerQueue[1])[0];
        System.out.println("server topicConsumerQueue.size=" + topicConsumerQueue.messageTotal());
        assert topicConsumerQueue.messageTotal() == 0;
        assert topicConsumerQueue.messageTotal2() == 0;
    }
}
