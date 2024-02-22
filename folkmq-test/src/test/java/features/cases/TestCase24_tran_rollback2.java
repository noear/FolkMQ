package features.cases;

import org.noear.folkmq.client.MqClientDefault;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.client.MqTransaction;
import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.server.MqQueue;
import org.noear.folkmq.server.MqServerDefault;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author noear
 * @since 1.2
 */
public class TestCase24_tran_rollback2 extends BaseTestCase {
    public TestCase24_tran_rollback2(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqServerDefault()
                .start(getPort());

        //客户端
        CountDownLatch countDownLatch = new CountDownLatch(2);

        client = new MqClientDefault("folkmq://127.0.0.1:" + getPort())
                .nameAs("demoapp")
                .config(c -> c.metaPut("ak", "").metaPut("sk", ""))
                .response(m -> {
                    if (m.isTransaction()) {
                        m.acknowledge(false);
                    }
                })
                .connect();

        client.subscribe("demo", "a", ((message) -> {
            System.out.println(message);
            countDownLatch.countDown();
        }));


        MqTransaction tran = client.beginTransaction();
        try {
            client.publish("demo", new MqMessage("demo1"));
            client.publish("demo", new MqMessage("demo2"));
        } catch (Throwable e) {
            tran.rollback();
        }


        countDownLatch.await(66, TimeUnit.SECONDS);

        //检验客户端
        assert countDownLatch.getCount() == 2;

        MqQueue queue = server.getServerInternal().getQueue("demo#" + MqConstants.MQ_TRAN_CONSUMER_GROUP);
        assert queue != null;
        System.out.println(queue.messageTotal());
        assert queue.messageTotal() == 0L;
    }
}