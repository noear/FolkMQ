package features.cases;

import org.noear.folkmq.FolkMQ;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.client.MqTransaction;
import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.borker.MqQueue;
import org.noear.folkmq.borker.MqBorkerDefault;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author noear
 * @since 1.2
 */
public class TestCase22_tran_commit2 extends BaseTestCase {
    public TestCase22_tran_commit2(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqBorkerDefault()
                .start(getPort());

        //客户端
        CountDownLatch countDownLatch = new CountDownLatch(2);

        client = FolkMQ.createClient("folkmq://127.0.0.1:" + getPort())
                .nameAs("demoapp")
                .config(c -> c.metaPut("ak", "").metaPut("sk", ""))
                .transactionCheckback(m -> {
                    System.out.println("来请求消息了：" + m);
                    if (m.isTransaction()) {
                        m.acknowledge(true);
                    }
                })
                .connect();

        client.subscribe("demo", "a", ((message) -> {
            System.out.println(message);
            countDownLatch.countDown();
        }));


        MqTransaction tran = client.newTransaction();
        try {
            client.publish("demo", new MqMessage("demo1").transaction(tran));
            client.publish("demo", new MqMessage("demo2").transaction(tran));
        } catch (Throwable e) {
            tran.rollback();
        }

        countDownLatch.await(66, TimeUnit.SECONDS);

        //检验客户端
        assert countDownLatch.getCount() == 0;

        MqQueue queue = server.getServerInternal().getQueue("demo#" + MqConstants.MQ_TRAN_CONSUMER_GROUP);
        assert queue != null;
        System.out.println(queue.messageTotal());
        assert queue.messageTotal() == 0L;
    }
}
