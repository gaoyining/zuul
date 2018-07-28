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

package com.netflix.zuul.netty.filter;

import com.netflix.spectator.impl.Preconditions;
import com.netflix.zuul.FilterUsageNotifier;
import com.netflix.zuul.filters.ZuulFilter;
import com.netflix.zuul.message.ZuulMessage;
import com.netflix.zuul.message.http.HttpRequestMessage;
import com.netflix.zuul.message.http.HttpResponseMessage;
import com.netflix.zuul.passport.CurrentPassport;
import com.netflix.zuul.passport.PassportState;
import io.netty.handler.codec.http.HttpContent;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is supposed to be thread safe and hence should not have any non final member variables
 * Created by saroskar on 5/17/17.
 *
 * zuul 过滤器链执行
 * 这个类应该是线程安全的，因此不应该有任何非最终成员变量由saroskar在5/17/17创建。
 */
@ThreadSafe
public class ZuulFilterChainRunner<T extends ZuulMessage> extends BaseZuulFilterRunner<T, T> {

    /**
     * 过滤器组
     */
    private final ZuulFilter<T, T>[] filters;

    public ZuulFilterChainRunner(ZuulFilter<T, T>[] zuulFilters, FilterUsageNotifier usageNotifier, FilterRunner<T, ?> nextStage) {
        super(zuulFilters[0].filterType(), usageNotifier, nextStage);
        this.filters = zuulFilters;
    }

    public ZuulFilterChainRunner(ZuulFilter<T, T>[] zuulFilters, FilterUsageNotifier usageNotifier) {
        this(zuulFilters, usageNotifier, null);
    }

    @Override
    public void filter(final T inMesg) {
        runFilters(inMesg, initRunningFilterIndex(inMesg));
    }

    @Override
    protected void resume(final T inMesg) {
        final AtomicInteger runningFilterIdx = getRunningFilterIndex(inMesg);
        runningFilterIdx.incrementAndGet();
        runFilters(inMesg, runningFilterIdx);
    }

    /**
     * 执行过滤器
     * @param mesg
     * @param runningFilterIdx
     */
    private final void runFilters(final T mesg, final AtomicInteger runningFilterIdx) {
        T inMesg = mesg;
        String filterName = "-";
        try {
            Preconditions.checkNotNull(mesg, "Input message");
            // 获得当前运行的Filter的下标值
            int i = runningFilterIdx.get();

            // 循环调用filter 执行
            while (i < filters.length) {
                // 获得对应的 ZuulFilter
                final ZuulFilter<T, T> filter = filters[i];
                // filter name
                filterName = filter.filterName();
                // ---------------------------------关键方法-------------------------------
                // 调用 filter 进行处理
                final T outMesg = filter(filter, inMesg);
                if (outMesg == null) {
                    // either async filter or waiting for the message body to be buffered
                    // 异步过滤器或等待缓冲消息体
                    return;
                }
                inMesg = outMesg;
                // 将下标志值 +1，继续循环体
                i = runningFilterIdx.incrementAndGet();
            }

            // Filter chain has reached its end, pass result to the next stage
            // 过滤器链已到达终点，将结果传递给下一个阶段
            // 执行下个阶段，这里对应着我们自己再构建 new InboundPassportStampingFilter(FILTERS_INBOUND_END)
            // 通过这段代码，我们知道了 Zuul2 的Chain是由 ChainRunner运行，和Netty的Head tail的链方式大相径庭。
            invokeNextStage(inMesg);
        }
        catch (Exception ex) {
            handleException(inMesg, filterName, ex);
        }
    }

    @Override
    public void filter(T inMesg, HttpContent chunk) {
        String filterName = "-";
        try {
            Preconditions.checkNotNull(inMesg, "input message");

            // 获得filter index
            final AtomicInteger runningFilterIdx = getRunningFilterIndex(inMesg);
            final int limit = runningFilterIdx.get();
            for (int i = 0; i < limit; i++) {
                final ZuulFilter<T, T> filter = filters[i];
                filterName = filter.filterName();
                if ((! filter.isDisabled()) && (! shouldSkipFilter(inMesg, filter))) {
                    // 执行filter逻辑
                    final HttpContent newChunk = filter.processContentChunk(inMesg, chunk);
                    if (newChunk == null)  {
                        // Filter wants to break the chain and stop propagating this chunk any further
                        // 过滤器想要break链并且不再继续传播这个块
                        return;
                    }
                    // deallocate original chunk if necessary
                    // 如有必要，请释放原始块
                    if ((newChunk != chunk) && (chunk.refCnt() > 0)) {
                        chunk.release(chunk.refCnt());
                    }
                    chunk = newChunk;
                }
            }

            if (limit >= filters.length) {
                // Filter chain has run to end, pass down the channel pipeline
                // 过滤链已经运行结束，传递通道管道
                invokeNextStage(inMesg, chunk);
            } else {
                inMesg.bufferBodyContents(chunk);

                // 等待表示
                boolean isAwaitingBody = isFilterAwaitingBody(inMesg);

                // Record passport states for start and end of buffering bodies.
                // 记录缓冲体开始和结束的passport状态。
                if (isAwaitingBody) {
                    // 需要等待body主体
                    CurrentPassport passport = CurrentPassport.fromSessionContext(inMesg.getContext());
                    if (inMesg.hasCompleteBody()) {
                        // 有完成body
                        if (inMesg instanceof HttpRequestMessage) {
                            passport.addIfNotAlready(PassportState.FILTERS_INBOUND_BUF_END);
                        } else if (inMesg instanceof HttpResponseMessage) {
                            passport.addIfNotAlready(PassportState.FILTERS_OUTBOUND_BUF_END);
                        }
                    }
                    else {
                        // 没有完成body
                        if (inMesg instanceof HttpRequestMessage) {
                            passport.addIfNotAlready(PassportState.FILTERS_INBOUND_BUF_START);
                        } else if (inMesg instanceof HttpResponseMessage) {
                            passport.addIfNotAlready(PassportState.FILTERS_OUTBOUND_BUF_START);
                        }
                    }
                }

                if (isAwaitingBody && inMesg.hasCompleteBody()) {
                    // whole body has arrived, resume filter chain
                    // 全部body已到，恢复过滤链
                    runFilters(inMesg, runningFilterIdx);
                }
            }
        }
        catch (Exception ex) {
            handleException(inMesg, filterName, ex);
        }
    }

}
