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

package com.netflix.zuul.netty.server;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.netty.common.CategorizedThreadFactory;
import com.netflix.netty.common.LeastConnsEventLoopChooserFactory;
import com.netflix.netty.common.metrics.EventLoopGroupMetrics;
import com.netflix.netty.common.status.ServerStatusManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorChooserFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.ThreadPerTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 *
 * NOTE: Shout-out to <a href="https://github.com/adamfisk/LittleProxy">LittleProxy</a> which was great as a reference.
 *
 * User: michaels
 * Date: 11/8/14
 * Time: 8:39 PM
 */
public class Server
{
    public static final DynamicBooleanProperty USE_EPOLL = new DynamicBooleanProperty("zuul.server.netty.socket.epoll", false);

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private static final DynamicBooleanProperty USE_LEASTCONNS_FOR_EVENTLOOPS = new DynamicBooleanProperty("zuul.server.eventloops.use_leastconns", false);

    private final EventLoopGroupMetrics eventLoopGroupMetrics;

    private final Thread jvmShutdownHook;
    private ServerGroup serverGroup;
    private final ClientConnectionsShutdown clientConnectionsShutdown;
    private final ServerStatusManager serverStatusManager;

    private final Map<Integer, ChannelInitializer> portsToChannelInitializers;
    private final EventLoopConfig eventLoopConfig;

    public Server(Map<Integer, ChannelInitializer> portsToChannelInitializers, ServerStatusManager serverStatusManager, ClientConnectionsShutdown clientConnectionsShutdown, EventLoopGroupMetrics eventLoopGroupMetrics)
    {
        this(portsToChannelInitializers, serverStatusManager, clientConnectionsShutdown, eventLoopGroupMetrics, new DefaultEventLoopConfig());
    }

    public Server(Map<Integer, ChannelInitializer> portsToChannelInitializers, ServerStatusManager serverStatusManager, ClientConnectionsShutdown clientConnectionsShutdown, EventLoopGroupMetrics eventLoopGroupMetrics, EventLoopConfig eventLoopConfig)
    {
        this.portsToChannelInitializers = portsToChannelInitializers;
        this.serverStatusManager = serverStatusManager;
        this.clientConnectionsShutdown = clientConnectionsShutdown;
        this.eventLoopConfig = eventLoopConfig;
        this.eventLoopGroupMetrics = eventLoopGroupMetrics;
        this.jvmShutdownHook = new Thread(() -> stop(), "Zuul-JVM-shutdown-hook");
    }

    public void stop()
    {
        LOG.warn("Shutting down Zuul.");
        serverGroup.stop();

        // remove the shutdown hook that was added when the proxy was started, since it has now been stopped
        try {
            Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
        } catch (IllegalStateException e) {
            // ignore -- IllegalStateException means the VM is already shutting down
        }

        LOG.warn("Completed zuul shutdown.");
    }

    public void start(boolean sync)
    {
        // 初始化服务组
        serverGroup = new ServerGroup("Salamander", eventLoopConfig.acceptorCount(), eventLoopConfig.eventLoopCount(), eventLoopGroupMetrics);
        // ---------------------关键方法--------------------------
        // 初始化通讯
        serverGroup.initializeTransport();
        try {
            List<ChannelFuture> allBindFutures = new ArrayList<>();

            // Setup each of the channel initializers on requested ports.
            // 在请求的端口上设置每个通道初始值设定项。
            for (Map.Entry<Integer, ChannelInitializer> entry : portsToChannelInitializers.entrySet())
            {
                allBindFutures.add(setupServerBootstrap(entry.getKey(), entry.getValue()));
            }

            // Once all server bootstraps are successfully initialized, then bind to each port.
            // 成功初始化所有服务器引导后，绑定到每个端口。
            for (ChannelFuture f: allBindFutures) {
                // Wait until the server socket is closed.
                // 等到服务器套接字关闭。
                ChannelFuture cf = f.channel().closeFuture();
                if (sync) {
                    cf.sync();
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** This is just for use in unit-testing. */
    public void waitForEachEventLoop() throws InterruptedException, ExecutionException
    {
        for (EventExecutor exec : serverGroup.clientToProxyWorkerPool)
        {
            exec.submit(() -> {
                // Do nothing.
            }).get();
        }
    }

    private ChannelFuture setupServerBootstrap(int port, ChannelInitializer channelInitializer)
            throws InterruptedException
    {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.clientToProxyBossPool,
                serverGroup.clientToProxyWorkerPool);

        // Choose socket options.
        Map<ChannelOption, Object> channelOptions = new HashMap<>();
        channelOptions.put(ChannelOption.SO_BACKLOG, 128);
        //channelOptions.put(ChannelOption.SO_TIMEOUT, SERVER_SOCKET_TIMEOUT.get());
        channelOptions.put(ChannelOption.SO_LINGER, -1);
        channelOptions.put(ChannelOption.TCP_NODELAY, true);
        channelOptions.put(ChannelOption.SO_KEEPALIVE, true);

        // Choose EPoll or NIO.
        if (USE_EPOLL.get()) {
            LOG.warn("Proxy listening with TCP transport using EPOLL");
            serverBootstrap = serverBootstrap.channel(EpollServerSocketChannel.class);
            channelOptions.put(EpollChannelOption.TCP_DEFER_ACCEPT, Integer.valueOf(-1));
        }
        else {
            LOG.warn("Proxy listening with TCP transport using NIO");
            serverBootstrap = serverBootstrap.channel(NioServerSocketChannel.class);
        }

        // Apply socket options.
        for (Map.Entry<ChannelOption, Object> optionEntry : channelOptions.entrySet()) {
            serverBootstrap = serverBootstrap.option(optionEntry.getKey(), optionEntry.getValue());
        }

        serverBootstrap.childHandler(channelInitializer);
        serverBootstrap.validate();

        LOG.info("Binding to port: " + port);

        // Flag status as UP just before binding to the port.
        serverStatusManager.localStatus(InstanceInfo.InstanceStatus.UP);

        // Bind and start to accept incoming connections.
        return serverBootstrap.bind(port).sync();
    }

    /**
     * Override for metrics or informational purposes
     *
     * @param clientToProxyBossPool - acceptor pool
     * @param clientToProxyWorkerPool - worker pool
     */
    public void postEventLoopCreationHook(EventLoopGroup clientToProxyBossPool, EventLoopGroup clientToProxyWorkerPool) {

    }


    private class ServerGroup
    {
        /** A name for this ServerGroup to use in naming threads.
         * 此ServerGroup的名称，用于命名线程。*/
        private final String name;
        /**
         * 接收线程数
         */
        private final int acceptorThreads;
        /**
         * 工作线程数
         */
        private final int workerThreads;
        private final EventLoopGroupMetrics eventLoopGroupMetrics;

        private EventLoopGroup clientToProxyBossPool;
        private EventLoopGroup clientToProxyWorkerPool;

        private volatile boolean stopped = false;

        private ServerGroup(String name, int acceptorThreads, int workerThreads, EventLoopGroupMetrics eventLoopGroupMetrics) {
            /**
             * 服务组名称
             */
            this.name = name;
            /**
             * 接收线程数
             */
            this.acceptorThreads = acceptorThreads;
            /**
             * 工作线程数
             */
            this.workerThreads = workerThreads;
            this.eventLoopGroupMetrics = eventLoopGroupMetrics;

            // 设置未捕获异常，而终止的方法
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(final Thread t, final Throwable e) {
                    LOG.error("Uncaught throwable", e);
                }
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(), "Zuul-ServerGroup-JVM-shutdown-hook"));
        }

        private void initializeTransport()
        {
            // TODO - try our own impl of ChooserFactory that load-balances across the eventloops using leastconns algo?
            // TODO - 试试我们自己的ChooserFactory impl，使用leastconns算法在eventloops上进行负载均衡？
            EventExecutorChooserFactory chooserFactory;
            // 判断是否使用最小连接数
            if (USE_LEASTCONNS_FOR_EVENTLOOPS.get()) {
                chooserFactory = new LeastConnsEventLoopChooserFactory(eventLoopGroupMetrics);
            } else {
                chooserFactory = DefaultEventExecutorChooserFactory.INSTANCE;
            }

            ThreadFactory workerThreadFactory = new CategorizedThreadFactory(name + "-ClientToZuulWorker");
            Executor workerExecutor = new ThreadPerTaskExecutor(workerThreadFactory);

            if (USE_EPOLL.get()) {
                clientToProxyBossPool = new EpollEventLoopGroup(
                        acceptorThreads,
                        new CategorizedThreadFactory(name + "-ClientToZuulAcceptor"));
                clientToProxyWorkerPool = new EpollEventLoopGroup(
                        workerThreads,
                        workerExecutor,
                        chooserFactory,
                        DefaultSelectStrategyFactory.INSTANCE
                );
            }
            else {
                clientToProxyBossPool = new NioEventLoopGroup(
                        acceptorThreads,
                        new CategorizedThreadFactory(name + "-ClientToZuulAcceptor"));
                clientToProxyWorkerPool = new NioEventLoopGroup(
                        workerThreads,
                        workerExecutor,
                        chooserFactory,
                        SelectorProvider.provider(),
                        DefaultSelectStrategyFactory.INSTANCE
                );
                // I/O操作和用户自定义任务的执行时间比为 9：1
                ((NioEventLoopGroup) clientToProxyWorkerPool).setIoRatio(90);
            }

            postEventLoopCreationHook(clientToProxyBossPool, clientToProxyWorkerPool);
        }

        synchronized private void stop()
        {
            LOG.warn("Shutting down");
            if (stopped) {
                LOG.warn("Already stopped");
                return;
            }

            // Flag status as down.
            // TODO - is this _only_ changing the local status? And therefore should we also implement a HealthCheckHandler
            // that we can flag to return DOWN here (would that then update Discovery? or still be a delay?)
            // 将状态标记为关闭。
            // TODO - 这是_only_改变本地状态？ 因此，我们是否还应该实现HealthCheckHandler
            // 我们可以标记在这里返回DOWN（然后会更新Discovery吗？或者仍然是延迟？）
            serverStatusManager.localStatus(InstanceInfo.InstanceStatus.DOWN);

            // Shutdown each of the client connections (blocks until complete).
            // NOTE: ClientConnectionsShutdown can also be configured to gracefully close connections when the
            // discovery status changes to DOWN. So if it has been configured that way, then this will be an additional
            // call to gracefullyShutdownClientChannels(), which will be a noop.

            // 关闭每个客户端连接（块直到完成）。
            // 注意：ClientConnectionsShutdown也可以配置为在正常时关闭连接
            // 发现状态更改为DOWN。 因此，如果它已经这样配置，那么这将是一个额外的
            // 调用gracefullyShutdownClientChannels（），这将是一个noop。
            clientConnectionsShutdown.gracefullyShutdownClientChannels();

            LOG.warn("Shutting down event loops");
            List<EventLoopGroup> allEventLoopGroups = new ArrayList<>();
            allEventLoopGroups.add(clientToProxyBossPool);
            allEventLoopGroups.add(clientToProxyWorkerPool);
            for (EventLoopGroup group : allEventLoopGroups) {
                group.shutdownGracefully();
            }

            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(20, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    LOG.warn("Interrupted while shutting down event loop");
                }
            }

            stopped = true;
            LOG.warn("Done shutting down");
        }
    }
}