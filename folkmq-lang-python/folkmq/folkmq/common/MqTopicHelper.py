class MqTopicHelper:
    #获取完整主题
    @staticmethod
    def getFullTopic(namespace:str, topic:str):
        if namespace:
            return f"{namespace}:{topic}"
        else:
            return topic

    #获取主题
    @staticmethod
    def getTopic(fullTopic:str):
        idx = fullTopic.index(":")
        if  idx > 0:
            return fullTopic[idx + 1]
        else:
            return fullTopic