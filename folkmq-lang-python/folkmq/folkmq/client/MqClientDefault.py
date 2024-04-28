from typing import Callable, Dict

from socketd import SocketD
from socketd.cluster.ClusterClientSession import ClusterClientSession
from socketd.exception.SocketDExecption import SocketDConnectionException, SocketDException
from socketd.transport.client.ClientConfig import ClientConfig
from socketd.transport.core import Entity
from socketd.transport.core.Message import Message
from socketd.transport.core.Session import Session
from socketd.transport.core.entity.EntityDefault import EntityDefault
from socketd.transport.core.entity.StringEntity import StringEntity
from socketd.transport.stream.RequestStream import RequestStream
from socketd.utils.LogConfig import log
from socketd.utils.RunUtils import RunUtils

from folkmq.client.MqAlarm import MqAlarm
from folkmq.client.MqClient import MqClientInternal
from folkmq.client.MqClientListener import MqClientListener
from folkmq.client.MqMessage import MqMessage
from folkmq.client.MqMessageReceived import MqMessageReceived, MqMessageReceivedImpl
from folkmq.client.MqSubscription import MqSubscription
from folkmq.client.MqTransaction import MqTransaction, MqTransactionImpl
from folkmq.common.MqAssert import MqAssert
from folkmq.common.MqConstants import MqConstants
from folkmq.common.MqMetasV2 import MqMetasV2
from folkmq.common.MqTopicHelper import MqTopicHelper
from folkmq.common.MqUtils import MqUtils
from folkmq.exception.FolkmqException import FolkmqException


# 消息客户端默认实现
class MqClientDefault(MqClientInternal):
    def __init__(self, urls:tuple[str], clientListener:MqClientListener|None = None):
        self._urls:tuple[str] = urls

        if clientListener is not None:
            self._clientListener = clientListener
        else:
            self._clientListener =  MqClientListener()

        self._clientListener.init(self)

        self._name:str|None = None
        self._namespace:str|None = None
        self._clientSession:ClusterClientSession|None = None
        self._clientConfigHandler:Callable[[ClientConfig], None]|None = None
        self._subscriptionMap:Dict[str, MqSubscription] = {}

        self._transactionCheckback:Callable[[MqMessageReceived],None] = None
        self._listenHandler:Callable[[MqMessageReceived],None] = None



    def name(self) -> str:
        return self._name

    def nameAs(self, name: str) -> 'MqClient':
        self._name = name
        return self

    def namespace(self) -> str:
        return self._namespace

    def namespaceAs(self, namespace: str) -> 'MqClient':
        self._namespace = namespace
        return self

    async def connect(self) -> 'MqClient':
        serverUrls:[str] = []

        for url in self._urls:
            url = url.replace("folkmq:ws://", "sd:ws://")
            url = url.replace("folkmq://", "sd:tcp://")
            serverUrls.append(url)

        self._clientSession = await (SocketD.create_cluster_client(*serverUrls)
            .config(self.__config_handle)
            .listen(self._clientListener)
            .open())

        return self

    def __config_handle(self, c:ClientConfig):
        from folkmq.FolkMQ import FolkMQ

        (c.meta_put(MqConstants.FOLKMQ_VERSION, FolkMQ.versionCodeAsString())
          .heartbeat_interval(6_000)
          .io_threads(1)
          .codec_threads(1)
          .exchange_threads(1))

        if self._name:
            c.meta_put("@", self._name)

        if self._namespace:
            c.meta_put(MqConstants.FOLKMQ_NAMESPACE, self._namespace)

        if self._clientConfigHandler:
            self._clientConfigHandler(c)

    async def disconnect(self):
        await self._clientSession.close()

    def config(self, configHandler: Callable[[ClientConfig], None]) -> 'MqClient':
        self._clientConfigHandler = configHandler
        return self

    def autoAcknowledge(self, auto: bool) -> 'MqClient':
        self._autoAcknowledge = auto
        return self

    async def subscribe(self, topic: str, consumerGroup: str | None, autoAck: bool | None,
                  consumerHandler: Callable[[MqMessageReceived], None]):
        if consumerGroup is None:
            consumerGroup = self.name()

        if autoAck is None:
            autoAck = self._autoAcknowledge

        MqAssert.requireNonNull(topic, "Param 'topic' can't be null")
        MqAssert.requireNonNull(consumerGroup, "Param 'consumerGroup' can't be null")
        MqAssert.requireNonNull(consumerHandler, "Param 'consumerHandler' can't be null")

        MqAssert.assertMeta(topic, "topic")
        MqAssert.assertMeta(consumerGroup, "consumerGroup")

        #支持命名空间
        topic = MqTopicHelper.getFullTopic(self._namespace, topic)

        subscription = MqSubscription(topic, consumerGroup, autoAck, consumerHandler)
        self._subscriptionMap[subscription.getQueueName()] = subscription

        if self._clientSession is not None:
            for session in self._clientSession.get_session_all():
                #如果有连接会话，则执行订阅
                entity = (StringEntity("")
                          .meta_put(MqConstants.MQ_META_TOPIC, subscription.getTopic())
                          .meta_put(MqConstants.MQ_META_CONSUMER_GROUP, subscription.getConsumerGroup())
                          .at(MqConstants.BROKER_AT_SERVER_ALL))
                #使用 Qos1
                await session.send_and_request(MqConstants.MQ_EVENT_SUBSCRIBE, entity, 30_000).waiter()
                log.info(f"Client subscribe successfully: {topic}#{consumerGroup}, sessionId={session.session_id()}")

    async def unsubscribe(self, topic: str, consumerGroup: str | None):
        if consumerGroup is None:
            consumerGroup = self.name()

        MqAssert.requireNonNull(topic, "Param 'topic' can't be null")
        MqAssert.requireNonNull(consumerGroup, "Param 'consumerGroup' can't be null")

        MqAssert.assertMeta(topic, "topic")
        MqAssert.assertMeta(consumerGroup, "consumerGroup")

        #支持命名空间
        topic = MqTopicHelper.getFullTopic(self._namespace, topic)

        queueName = topic + MqConstants.SEPARATOR_TOPIC_CONSUMER_GROUP + consumerGroup
        self._subscriptionMap.pop(queueName)

        if self._clientSession is not None:
            for session in self._clientSession.get_session_all():
                #如果有连接会话，则执行订阅
                entity = (StringEntity("")
                          .meta_put(MqConstants.MQ_META_TOPIC, topic)
                          .meta_put(MqConstants.MQ_META_CONSUMER_GROUP, consumerGroup)
                          .at(MqConstants.BROKER_AT_SERVER_ALL))
                #使用 Qos1
                await session.send_and_request(MqConstants.MQ_EVENT_UNSUBSCRIBE, entity, 30_000).waiter()
                log.info(f"Client unsubscribe successfully: {topic}#{consumerGroup}， sessionId={session.session_id()}")

    async def publish(self, topic: str, message: MqMessage):
        MqAssert.requireNonNull(topic, "Param 'topic' can't be null")
        MqAssert.requireNonNull(message, "Param 'message' can't be null")

        MqAssert.assertMeta(topic, "topic")

        if self._clientSession is None:
            raise SocketDConnectionException("Not connected!")

        # 支持命名空间
        topic = MqTopicHelper.getFullTopic(self._namespace, topic)
        session = self._clientSession.get_session_any(self._diversionOrNull(topic, message))

        if session is None or session.is_valid() == False:
            raise SocketDException("No session is available!")

        entity = MqUtils.getOf(session).publishEntityBuild(topic, message)

        if message.getQos() > 0:
            resp = await session.send_and_request(MqConstants.MQ_EVENT_PUBLISH, entity).waiter()
            confirm = int(resp.meta_or_default(MqConstants.MQ_META_CONFIRM, "0"))
            if confirm != 1:
                messsage = "Client message publish confirm failed: " + resp.data_as_string()
                raise FolkmqException(messsage)
        else:
            session.send(MqConstants.MQ_EVENT_PUBLISH, entity)

    async def unpublish(self, topic: str, key: str):
        MqAssert.requireNonNull(topic, "Param 'topic' can't be null")
        MqAssert.requireNonNull(key, "Param 'key' can't be null")

        MqAssert.assertMeta(topic, "topic")
        MqAssert.assertMeta(key, "key")

        if self._clientSession is None:
            raise SocketDConnectionException("Not connected!")

        #支持命名空间
        topic = MqTopicHelper.getFullTopic(self._namespace, topic)
        session = self._clientSession.get_session_any(None)
        if session is None or session.is_valid() == False:
            raise SocketDException("No session is available!")

        entity = (StringEntity("")
                  .meta_put(MqConstants.MQ_META_TOPIC, topic)
                  .meta_put(MqConstants.MQ_META_KEY, key)
                  .at(MqConstants.BROKER_AT_SERVER_ALL))

        resp = await session.send_and_request(MqConstants.MQ_EVENT_UNPUBLISH, entity).waiter()
        confirm = int(resp.meta_or_default(MqConstants.MQ_META_CONFIRM, "0"))
        if confirm != 1:
            messsage = "Client message unpublish confirm failed: " + resp.data_as_string()
            raise FolkmqException(messsage)



    async def listen(self, listenHandler: Callable[[MqMessageReceived], None]):
        # 检查必要条件
        if self._name is None:
            return FolkmqException("Client 'name' can't be empty")

        self._listenHandler = listenHandler

    def send(self, message: MqMessage, toName: str, timeout: int | None = None) -> RequestStream | None:
        # 检查必要条件
        if self._name is None:
            return FolkmqException("Client 'name' can't be empty")

        # 检查参数
        MqAssert.requireNonNull(toName, "Param 'toName' can't be null")
        MqAssert.requireNonNull(message, "Param 'message' can't be null")

        MqAssert.assertMeta(toName, "toName")

        if self._clientSession is None:
            raise SocketDConnectionException("Not connected!")

        session = self._clientSession.get_session_any(None)
        if session is None or session.is_valid() == False:
            raise SocketDException("No session is available!")

        message.internalSender(self.name())
        entity = MqUtils.getOf(session).publishEntityBuild("", message)
        entity.put_meta(MqMetasV2.MQ_META_CONSUMER_GROUP, toName)
        entity.at(toName)

        if message.getQos() > 0:
            return session.send_and_request(MqConstants.MQ_EVENT_REQUEST, entity, timeout)
        else:
            session.send(MqConstants.MQ_EVENT_REQUEST, entity)
            return None

    def transactionCheckback(self, transactionCheckback: Callable[[MqMessageReceived], None]):
        if transactionCheckback is not None:
            self._transactionCheckback = transactionCheckback
        return self

    def newTransaction(self) -> MqTransaction:
        if self._name is None:
            return FolkmqException("Client 'name' can't be empty")

        return MqTransactionImpl(self)

    async def publish2(self, tmid: str, keyAry: [str], isRollback: bool):
        if keyAry is None or len(keyAry) == 0:
            return

        if self._clientSession is None:
            raise SocketDConnectionException("Not connected!")

        session = self._clientSession.get_session_any(tmid)

        if session is None or session.is_valid() == False:
            raise SocketDException("No session is available!")

        entity = (StringEntity(",".join(keyAry))
                  .meta_put(MqConstants.MQ_META_ROLLBACK, ( "1" if isRollback else "0"))
                  .at(MqConstants.BROKER_AT_SERVER_HASH)) # 事务走哈希

        resp = await session.send_and_request(MqConstants.MQ_EVENT_PUBLISH2, entity).waiter()

        confirm = int(resp.meta_or_default(MqConstants.MQ_META_CONFIRM, "0"))
        if confirm != 1:
            messsage = "Client message publish2 confirm failed: " + resp.data_as_string()
            raise FolkmqException(messsage)


    def reply(self, session: Session, f: Message, message: MqMessageReceivedImpl, isOk: bool, entity: Entity):
        """发送“回执”，向服务端反馈消费情况"""
        if message.getQos() > 0:
            if session.is_valid():
                if entity is None:
                    entity = EntityDefault()

                if isinstance(entity, MqAlarm):
                    RunUtils.taskTry(session.send_alarm(f, entity.data_as_string()))
                else:
                    entity.put_meta(MqConstants.MQ_META_ACK, "1" if isOk else "0")
                    RunUtils.taskTry(session.reply_end(f, entity))

    def _diversionOrNull(self, fullTopic: str, message: MqMessage) -> str | None:
        if message.isTransaction():
            return message.getTmid()
        elif message.isSequence():
            if message.getSequenceSharding():
                return message.getSequenceSharding()
            else:
                return fullTopic
        else:
            return None

    def getSubscription(self, fullTopic: str, consumerGroup: str):
        queueName = fullTopic + MqConstants.SEPARATOR_TOPIC_CONSUMER_GROUP + consumerGroup
        return self._subscriptionMap.get(queueName)

    def getSubscriptionAll(self) -> [MqSubscription]:
        return self._subscriptionMap.values()
    def getSubscriptionSize(self):
        return len(self._subscriptionMap)