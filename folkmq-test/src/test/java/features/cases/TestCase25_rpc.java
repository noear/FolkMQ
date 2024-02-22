package features.cases;

import org.noear.folkmq.client.*;
import org.noear.folkmq.server.MqServerDefault;
import org.noear.socketd.transport.core.Reply;
import org.noear.socketd.transport.core.entity.StringEntity;

/**
 * @author noear
 * @since 1.2
 */
public class TestCase25_rpc extends BaseTestCase {
    public TestCase25_rpc(int port) {
        super(port);
    }

    @Override
    public void start() throws Exception {
        super.start();

        //服务端
        server = new MqServerDefault()
                .start(getPort());

        //客户端
        client = new MqClientDefault("folkmq://127.0.0.1:" + getPort())
                .nameAs("demoapp")
                .response(new MqResponseRouter().doOn("test.hello", m -> {
                    m.acknowledge(true, new StringEntity("demoapp: me to! rev: " + m.getContent()));
                }))
                .connect();

        MqClient client2 = new MqClientDefault("folkmq://127.0.0.1:" + getPort())
                .nameAs("testapp")
                .response(m -> {
                    m.acknowledge(true, new StringEntity("testapp: me to!"));
                })
                .connect();


        //开始 rpc 请求
        Reply reply = client2.request("demoapp", "test.hello", new MqMessage("hello")).await();
        String rst = reply.dataAsString();

        //检验客户端
        assert rst.contains("hello");
        assert rst.contains("me to");
        assert rst.contains("demoapp");
    }
}