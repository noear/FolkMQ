package features.cases;

import org.noear.folkmq.client.MqClientDefault;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.server.MqServer;
import org.noear.folkmq.server.MqServerDefault;
import org.noear.folkmq.server.MqServiceInternal;
import org.noear.folkmq.server.MqTopicConsumerQueue;
import org.noear.socketd.transport.core.Listener;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.server.Server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author noear
 * @since 1.0
 */
public class TestCase15_send_multi_server extends BaseTestCase {
    public TestCase15_send_multi_server(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqServerDefault()
                .start(getPort());

        MqServer server2 = new MqServerDefault()
                .start(getPort() + 10000);

        //客户端
        CountDownLatch countDownLatch = new CountDownLatch(4);

        client = new MqClientDefault("folkmq://127.0.0.1:" + getPort() +",folkmq://127.0.0.1:" + (10000+getPort()))
                .connect();

        client.subscribe("demo", "a", ((message) -> {
            System.out.println(message);
            countDownLatch.countDown();
        }));

        client.publishAsync("demo",  new MqMessage("demo1"));
        client.publishAsync("demo",  new MqMessage("demo2"));
        client.publishAsync("demo",  new MqMessage("demo3"));
        client.publishAsync("demo",  new MqMessage("demo4"));

        countDownLatch.await(1, TimeUnit.SECONDS);

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



        //检验服务端
        MqServiceInternal serverInternal2 = server2.getServerInternal();
        System.out.println("server topicConsumerMap.size=" + serverInternal2.getTopicConsumerMap().size());
        assert serverInternal2.getTopicConsumerMap().size() == 1;

        MqTopicConsumerQueue topicConsumerQueue2 = serverInternal2.getTopicConsumerMap().values().toArray(new MqTopicConsumerQueue[1])[0];
        System.out.println("server topicConsumerQueue.size=" + topicConsumerQueue2.messageTotal());
        assert topicConsumerQueue2.messageTotal() == 0;
        assert topicConsumerQueue2.messageTotal2() == 0;
    }
}