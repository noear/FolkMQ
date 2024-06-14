package benchmark;

import org.noear.folkmq.FolkMQ;
import org.noear.folkmq.client.MqClient;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.broker.MqBorker;

import java.util.Date;
import java.util.concurrent.CountDownLatch;

public class BenchmarkScheduledTest {
    public static void main(String[] args) throws Exception {
        //服务端
        MqBorker server = FolkMQ.createBorker()
                .addAccess("folkmq", "YapLHTx19RlsEE16")
                .start(18602);

        Thread.sleep(1000);
        int count = 100_000;
        CountDownLatch countDownLatch = new CountDownLatch(count);

        //客户端
        MqClient client = FolkMQ.createClient("folkmq://127.0.0.1:18602?ak=folkmq&sk=YapLHTx19RlsEE16")
                .connect();

        //订阅
        client.subscribe("hot", "a", message -> {
            //System.out.println("::" + topic + " - " + message);
        });

        client.subscribe("test", "a", message -> {
            //System.out.println("::" + topic + " - " + message);
            countDownLatch.countDown();
        });

        //发布预热
        for (int i = 0; i < 100; i++) {
            client.publish("hot", new MqMessage("hot-" + i));
        }

        //发布测试
        long start_time = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            client.publishAsync("test", new MqMessage("test-" + i).scheduled(new Date(System.currentTimeMillis() + 5000)));
        }
        long sendTime = System.currentTimeMillis() - start_time;

        System.out.println("sendTime: " + sendTime + "ms");
        countDownLatch.await();

        long consumeTime = System.currentTimeMillis() - start_time;

        System.out.println("sendTime: " + sendTime + "ms");
        System.out.println("consumeTime: " + consumeTime + "ms, count: " + (count - countDownLatch.getCount()));
    }
}
