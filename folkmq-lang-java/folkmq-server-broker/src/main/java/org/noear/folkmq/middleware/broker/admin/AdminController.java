package org.noear.folkmq.middleware.broker.admin;

import org.noear.folkmq.middleware.broker.admin.dso.LicenceUtils;
import org.noear.folkmq.middleware.broker.admin.dso.ViewQueueService;
import org.noear.folkmq.middleware.broker.admin.model.ServerInfoVo;
import org.noear.folkmq.middleware.broker.admin.model.ServerVo;
import org.noear.folkmq.middleware.broker.admin.model.TopicVo;
import org.noear.folkmq.middleware.broker.mq.BrokerListenerFolkmq;
import org.noear.folkmq.client.MqMessage;
import org.noear.folkmq.common.MqConstants;
import org.noear.folkmq.common.MqUtils;
import org.noear.snack.core.utils.DateUtil;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.ModelAndView;
import org.noear.solon.core.handle.Result;
import org.noear.solon.validation.annotation.Logined;
import org.noear.solon.validation.annotation.NotEmpty;
import org.noear.solon.validation.annotation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

import static javax.management.Query.attr;

/**
 * 管理控制器
 *
 * @author noear
 * @since 1.0
 */
@Logined
@Valid
@Controller
public class AdminController extends BaseController {
    static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Inject
    BrokerListenerFolkmq brokerListener;

    @Inject
    ViewQueueService viewQueueService;


    @Mapping("/admin")
    public ModelAndView admin() {
        if (LicenceUtils.getGlobal().isValid()) {
            //有效许可证
            ModelAndView vm = view("admin");

            vm.put("isValid", LicenceUtils.getGlobal().isValid());

            if (LicenceUtils.getGlobal().isValid()) {
                if (LicenceUtils.getGlobal().isExpired()) {
                    vm.put("licenceBtn", "过期授权");
                } else {
                    vm.put("licenceBtn", "正版授权");
                }
            } else {
                vm.put("licenceBtn", "无效授权");
            }

            return vm;
        } else {
            //无效许可证
            ModelAndView vm = view("admin_licence_invalid");
            return vm;
        }
    }

    @Mapping("/admin/licence")
    public ModelAndView licence() {
        ModelAndView vm = view("admin_licence");

        if (LicenceUtils.getGlobal().isValid() == false) {
            vm.put("sn", "无效授权（请获取企业版授权：<a href='https://folkmq.noear.org' target='_blank'>https://folkmq.noear.org</a>）");

            vm.put("isAuthorized", false);
        } else {
            if (LicenceUtils.getGlobal().isExpired()) {
                String hint = "（已过期，请重新获取授权：<a href='https://folkmq.noear.org' target='_blank'>https://folkmq.noear.org</a>）";
                vm.put("sn", LicenceUtils.getGlobal().getSn() + hint);
            } else {
                vm.put("sn", LicenceUtils.getGlobal().getSn());
            }

            vm.put("isAuthorized", true);
            vm.put("editionName", LicenceUtils.getGlobal().getEditionName());
            vm.put("subscribeDate", LicenceUtils.getGlobal().getSubscribe());
            vm.put("subscribeMonths", LicenceUtils.getGlobal().getMonthsStr());
            vm.put("consumer", LicenceUtils.getGlobal().getConsumer());
        }

        return vm;
    }

    @Post
    @Mapping("/admin/licence/ajax/verify")
    public Result licence_ajax_verify(String licence) {
        boolean isOk = LicenceUtils.getGlobal().load(licence);

        if (isOk) {
            return Result.succeed();
        } else {
            return Result.failure("许可证无效");
        }
    }

    @Mapping("/admin/topic")
    public ModelAndView topic() {
        Map<String, Set<String>> subscribeMap = brokerListener.getSubscribeMap();

        List<TopicVo> list = new ArrayList<>();

        //用 list 转一下，免避线程安全
        for (String topic : new ArrayList<String>(subscribeMap.keySet())) {
            Set<String> queueSet = subscribeMap.get(topic);

            TopicVo topicVo = new TopicVo();
            topicVo.setTopic(topic);
            if (queueSet != null) {
                topicVo.setQueueCount(queueSet.size());
                topicVo.setQueueList(queueSet.toString());
            } else {
                topicVo.setQueueCount(0);
                topicVo.setQueueList("");
            }

            list.add(topicVo);

            //不超过99
            if (list.size() == 99) {
                break;
            }
        }

        list.sort(Comparator.comparing(TopicVo::getTopic));

        return view("admin_topic").put("list", list);
    }

    @Mapping("/admin/server")
    public ModelAndView server() throws IOException {
        List<ServerVo> list = new ArrayList<>();
        Collection<Session> tmp = brokerListener.getPlayerAll(MqConstants.BROKER_AT_SERVER);

        if (tmp != null) {
            List<Session> serverList = new ArrayList<>(tmp);

            //用 list 转一下，免避线程安全
            for (Session session : serverList) {
                InetSocketAddress socketAddress = session.remoteAddress();
                String adminPort = session.param("port");
                String adminAddr = socketAddress.getAddress().getHostAddress();

                ServerVo serverVo = new ServerVo();
                serverVo.sid = session.sessionId();
                serverVo.addree = adminAddr + ":" + socketAddress.getPort();
                serverVo.adminUrl = "http://" + adminAddr + ":" + adminPort + "/admin";

                ServerInfoVo infoVo = session.attr("ServerInfoVo");
                if (infoVo != null) {
                    serverVo.memoryRatio = String.format("%.2f%%", infoVo.memoryRatio * 100);
                } else {
                    serverVo.memoryRatio = "-";
                }

                list.add(serverVo);

                //不超过99
                if (list.size() == 99) {
                    break;
                }
            }
        }

        return view("admin_server").put("list", list);
    }

    @Mapping("/admin/server/ajax/save")
    public Result server_save(@NotEmpty String sid) throws IOException {
        Collection<Session> tmp = brokerListener.getPlayerAll(MqConstants.BROKER_AT_SERVER);

        if (tmp != null) {
            List<Session> serverList = new ArrayList<>(tmp);
            for (Session s1 : serverList) {
                if (s1.sessionId().equals(sid)) {
                    s1.send(MqConstants.MQ_EVENT_SAVE, new StringEntity(""));
                    return Result.succeed();
                }
            }
        }

        return Result.failure("操作失败");
    }

    @Mapping("/admin/publish")
    public ModelAndView publish() {
        return view("admin_publish");
    }

    @Mapping("/admin/publish/ajax/post")
    public Result publish_ajax_post(String topic, String scheduled, int qos, String content) {
        try {
            if (brokerListener.hasSubscribe(topic)) {
                Date scheduledDate = DateUtil.parse(scheduled);
                MqMessage message = new MqMessage(content).qos(qos).scheduled(scheduledDate);
                Message routingMessage = MqUtils.getV2().routingMessageBuild(topic, message);

                if (brokerListener.publishDo(routingMessage, qos)) {
                    return Result.succeed();
                } else {
                    return Result.failure("集群没有服务节点");
                }
            } else {
                return Result.failure("主题不存在!");
            }
        } catch (Exception e) {
            return Result.failure(e.getLocalizedMessage());
        }
    }
}