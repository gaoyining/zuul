/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.sample;

import com.google.inject.AbstractModule;
import com.netflix.discovery.AbstractDiscoveryClientOptionalArgs;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.netty.common.accesslog.AccessLogPublisher;
import com.netflix.netty.common.status.ServerStatusManager;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.netflix.zuul.BasicRequestCompleteHandler;
import com.netflix.zuul.FilterFileManager;
import com.netflix.zuul.RequestCompleteHandler;
import com.netflix.zuul.context.SessionContextDecorator;
import com.netflix.zuul.context.ZuulSessionContextDecorator;
import com.netflix.zuul.init.ZuulFiltersModule;
import com.netflix.zuul.netty.server.BaseServerStartup;
import com.netflix.zuul.netty.server.ClientRequestReceiver;
import com.netflix.zuul.origins.BasicNettyOriginManager;
import com.netflix.zuul.origins.OriginManager;
import com.netflix.zuul.stats.BasicRequestMetricsPublisher;
import com.netflix.zuul.stats.RequestMetricsPublisher;

/**
 * Zuul Sample Module
 *
 * Author: Arthur Gonigberg
 * Date: November 20, 2017
 */
public class ZuulSampleModule extends AbstractModule {
    @Override
    protected void configure() {
        // sample specific bindings
        bind(BaseServerStartup.class).to(SampleServerStartup.class);

        // use provided basic netty origin manager
        bind(OriginManager.class).to(BasicNettyOriginManager.class);

        // zuul filter loading
        // 加载zuul filter 过滤器
        install(new ZuulFiltersModule());
        bind(FilterFileManager.class).asEagerSingleton();

        // general server bindings
        // 一般服务器绑定

        // health/discovery status
        // 健康/发现状态
        bind(ServerStatusManager.class);
        // decorate new sessions when requests come in
        // 在请求进来时装饰新会话
        bind(SessionContextDecorator.class).to(ZuulSessionContextDecorator.class);
        // atlas metrics registry
        // 注册表监控集合
        bind(Registry.class).to(DefaultRegistry.class);
        // metrics post-request completion
        // 监控请求完成后
        bind(RequestCompleteHandler.class).to(BasicRequestCompleteHandler.class);
        // discovery client
        // 发现客户端
        bind(AbstractDiscoveryClientOptionalArgs.class).to(DiscoveryClient.DiscoveryClientOptionalArgs.class);
        // timings publisher
        // 时间发布者
        bind(RequestMetricsPublisher.class).to(BasicRequestMetricsPublisher.class);

        // access logger, including request ID generator
        // 访问记录器，包括请求ID生成器
        bind(AccessLogPublisher.class).toInstance(new AccessLogPublisher("ACCESS",
                (channel, httpRequest) -> ClientRequestReceiver.getRequestFromChannel(channel).getContext().getUUID()));
    }
}
