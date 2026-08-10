package com.threebody.app.event;

/** 实验消息监听器，由 web 层实现并注册到应用服务。应用层不依赖任何框架。 */
@FunctionalInterface
public interface ExperimentEventListener {

    void onMessage(ExperimentMessage message);
}