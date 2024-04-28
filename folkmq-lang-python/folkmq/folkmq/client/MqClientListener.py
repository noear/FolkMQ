import json

from socketd.exception.SocketDExecption import SocketDAlarmException
from socketd.transport.core.Message import Message
from socketd.transport.core.Session import Session
from socketd.transport.core.entity.StringEntity import StringEntity
from socketd.transport.core.listener.EventListener import EventListener
from socketd.utils.LogConfig import log

from folkmq.client.MqMessageReceived import MqMessageReceivedImpl
from folkmq.common.MqConstants import MqConstants


class MqClientListener(EventListener):
    def init(self, client: 'MqClientDefault'):
        """初始化"""
        self._client = client

        self.do_on(MqConstants.MQ_EVENT_DISTRIBUTE, self.doOn_distribute)
        self.do_on(MqConstants.MQ_EVENT_REQUEST, self.doOn_request)

    def doOn_distribute(self, s:Session, m:Message):
        try:
            message = MqMessageReceivedImpl(self._client, s, m)
            self.onReceive(s,m,message,False)
        except Exception as e:
            log.warning(f"Client consume handle error, sid={m.sid()}, error: {e}")

    def doOn_request(self, s:Session, m:Message):
        try:
            message = MqMessageReceivedImpl(self._client, s, m)
            self.onReceive(s,m,message,True)
        except Exception as e:
            log.warning(f"Client consume handle error, sid={m.sid()}, error: {e}")

    def onReceive(self, s: Session, m: Message, message: MqMessageReceivedImpl, isRequest: bool):
        """接收时"""
        if isRequest:
            try:
                if message.isTransaction():
                    if self._client._transactionCheckback is not None:
                        self._client._transactionCheckback(message)
                    else:
                        s.send_alarm(m, "Client no checkback handler!")
                else:
                    if self._client._listenHandler is not None:
                        self._client._listenHandler(message)
                    else:
                        s.send_alarm(m, "Client no request handler!")
            except Exception as e:
                try:
                    if s.is_valid():
                        s.send_alarm(m, "Client request handle error:" + e)
                    log.warning(f"Client request handle error, key={message.getKey()}, error: {e}")
                except Exception as err:
                    log.warning(f"Client request handle error, key={message.getKey()}, error: {err}")
        else:
            subscription = self._client.getSubscription(message.getFullTopic(), message.getConsumerGroup())

            try:
                if subscription is not None:
                    # 有订阅
                    subscription.consume(message)
                    if subscription.isAutoAck():
                        self._client.reply(s, m, message, True, None)
                else:
                    # 没有订阅
                    self._client.reply(s, m, message, False, None)
            except Exception as e:
                try:
                    if subscription is not None:
                        # 有订阅
                        if subscription.isAutoAck():
                            self._client.reply(s, m, message, False, None)
                    else:
                        # 没有订阅
                        self._client.reply(s, m, message, False, None)

                    log.warning(f"Client consume handle error, key={message.getKey()}, error: {e}")
                except Exception as err:
                    log.warning(f"Client consume handle error, key={message.getKey()}, error: {err}")

    async def on_open(self, session:Session):
        """会话打开时"""
        await super().on_open(session)

        log.info(f"Client session opened, sessionId={session.session_id()}")

        if self._client.getSubscriptionSize() == 0:
            return

        subscribeData:dict[str,str] = {}
        for subscription in self._client.getSubscriptionAll():
            queueNameSet = subscribeData.get(subscription.getTopic())
            if queueNameSet is None:
                queueNameSet = set()
                subscribeData[subscription.getTopic()] = queueNameSet

            queueNameSet.add(subscription.getQueueName())

        jsonStr = json.dumps(subscribeData)
        entity = (StringEntity(jsonStr)
                  .meta_put(MqConstants.MQ_META_BATCH, "1")
                  .meta_put("@", MqConstants.BROKER_AT_SERVER))

        await session.send_and_request(MqConstants.MQ_EVENT_SUBSCRIBE, entity).waiter()

        log.info(f"Client onOpen batch subscribe successfully, sessionId={session.session_id()}")

    def on_close(self, session: Session):
        """会话关闭时"""
        super().on_close(session)

        log.info(f"Client session closed, sessionId={session.session_id()}")

    async def on_error(self, session: Session, error: Exception):
        """会话出错时"""
        await super().on_error(session, error)

        if isinstance(error, SocketDAlarmException):
            log.warning(f"Client error, sessionId={session.session_id()}, error:{error}")
        else:
            log.warning(f"Client error, sessionId={session.session_id()}, error:{error}")
