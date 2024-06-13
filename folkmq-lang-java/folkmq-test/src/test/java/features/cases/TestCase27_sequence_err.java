package features.cases;

import org.noear.folkmq.FolkMQ;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.server.MqServerDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author noear
 * @since 1.3
 */
public class TestCase27_sequence_err extends BaseTestCase {
    public TestCase27_sequence_err(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqServerDefault()
                .start(getPort());

        //客户端
        int count = 10;
        CountDownLatch countDownLatch = new CountDownLatch(count);

        client = FolkMQ.createClient("folkmq://127.0.0.1:" + getPort())
                .autoAcknowledge(false)
                .connect();

        List<Integer> msgList = new ArrayList<>();
        client.subscribe("demo", "a", ((message) -> {
            int id = Integer.parseInt(message.getBodyAsString());
            System.out.println("------: " + id);

            if (message.getTimes() > 0 || id % 2 == 0) {
                msgList.add(id);
                countDownLatch.countDown();
                message.acknowledge(true);
            } else {
                message.acknowledge(false);
            }
        }));

        for (int i = 0; i < count; i++) {
            client.publish("demo", new MqMessage(String.valueOf(i)).attr("id", String.valueOf(i)).sequence(true));
        }

        countDownLatch.await(52, TimeUnit.SECONDS);

        //检验客户端
        if(countDownLatch.getCount() > 0) {
            System.out.println("还有未收：" + countDownLatch.getCount());
        }

        //检验客户端
        assert countDownLatch.getCount() == 0;

        int val = 0;
        for (Integer v1 : msgList) {
            if (v1 < val) {
                System.out.println(v1);
                assert false;
            } else {
                val = v1;
            }
        }

        assert true;
    }
}