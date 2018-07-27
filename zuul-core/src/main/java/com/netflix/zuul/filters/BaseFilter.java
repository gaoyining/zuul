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
package com.netflix.zuul.filters;

import com.netflix.config.CachedDynamicBooleanProperty;
import com.netflix.config.CachedDynamicIntProperty;
import com.netflix.spectator.api.Counter;
import com.netflix.zuul.exception.ZuulFilterConcurrencyExceededException;
import com.netflix.zuul.message.ZuulMessage;
import com.netflix.zuul.netty.SpectatorUtils;
import io.netty.handler.codec.http.HttpContent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Base abstract class for ZuulFilters. The base class defines abstract methods to define:
 * filterType() - to classify a filter by type. Standard types in Zuul are "pre" for pre-routing filtering,
 * "route" for routing to an origin, "post" for post-routing filters, "error" for error handling.
 * We also support a "static" type for static responses see  StaticResponseFilter.
 * <p/>
 * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
 * important for a filter. filterOrders do not need to be sequential.
 * <p/>
 * ZuulFilters may be disabled using Archaius Properties.
 * <p/>
 * By default ZuulFilters are static; they don't carry state. This may be overridden by overriding the isStaticFilter() property to false
 *
 *
 * ZuulFilters的基本抽象类。 基类定义了要定义的抽象方法：
 * filterType（） - 按类型对过滤器进行分类。
 * Zuul中的标准类型是用于路由前过滤的“pre”，用于路由到源的“route”，用于路由后过滤器的“post”，用于错误处理的“error”。
 * 我们还支持静态响应的“static”类型，请参阅StaticResponseFilter。
 * <P/>
 * 还必须为过滤器定义filterOrder（）。 如果优先级对于过滤器不重要，则过滤器可能具有相同的filterOrder。 filterOrders不需要是顺序的。
 * <P/>
 * 可以使用Archaius属性禁用ZuulFilters。
 * <P/>
 * 默认情况下，ZuulFilters是静态的; 他们没有携带状态。 这可以通过将isStaticFilter（）属性重写为false来覆盖
 *
 * @author Mikey Cohen
 *         Date: 10/26/11
 *         Time: 4:29 PM
 */
public abstract class BaseFilter<I extends ZuulMessage, O extends ZuulMessage> implements ZuulFilter<I,O>
{
    /**
     * 基础名称
     */
    private final String baseName;
    /**
     * 并发个数
     */
    private final AtomicInteger concurrentCount;
    /**
     * 并发拒绝数
     */
    private final Counter concurrencyRejections;

    /**
     * 禁用的filter名
     */
    private final CachedDynamicBooleanProperty filterDisabled;
    /**
     * 最大并发数
     */
    private final CachedDynamicIntProperty filterConcurrencyLimit;

    /**
     * 并发保护协议是否开启
     */
    private static final CachedDynamicBooleanProperty concurrencyProtectEnabled = new CachedDynamicBooleanProperty("zuul.filter.concurrency.protect.enabled", true);


    protected BaseFilter() {
        baseName = this.getClass().getSimpleName() + "." + filterType().toString();
        concurrentCount = SpectatorUtils.newGauge("zuul.filter.concurrency.current", baseName, new AtomicInteger(0));
        concurrencyRejections = SpectatorUtils.newCounter("zuul.filter.concurrency.rejected", baseName);
        filterDisabled = new CachedDynamicBooleanProperty(disablePropertyName(), false);
        filterConcurrencyLimit = new CachedDynamicIntProperty(maxConcurrencyPropertyName(), 4000);
    }

    @Override
    public String filterName() {
        return this.getClass().getName();
    }

    @Override
    public boolean overrideStopFilterProcessing()
    {
        return false;
    }

    /**
     * The name of the Archaius property to disable this filter. by default it is zuul.[classname].[filtertype].disable
     *
     * 用于禁用此过滤器的Archaius属性的名称。 默认情况下，它是zuul.[classname].[filtertype].disable
     *
     * @return
     */
    public String disablePropertyName() {
        return "zuul." + baseName + ".disable";
    }

    /**
     * The name of the Archaius property for this filter's max concurrency. by default it is zuul.[classname].[filtertype].concurrency.limit
     *
     * 此过滤器的最大并发数的Archaius属性的名称。 默认情况下，它是zuul.[classname].[filtertype].concurrency.limit
     *
     * @return
     */
    public String maxConcurrencyPropertyName() {
        return "zuul." + baseName + ".concurrency.limit";
    }

    /**
     * If true, the filter has been disabled by archaius and will not be run
     *
     * 如果为true，则过滤器已被archaius禁用，并且不会运行
     *
     * @return
     */
    @Override
    public boolean isDisabled() {
        return filterDisabled.get();
    }

    @Override
    public O getDefaultOutput(I input)
    {
        return (O)input;
    }

    @Override
    public FilterSyncType getSyncType()
    {
        return FilterSyncType.ASYNC;
    }

    @Override
    public String toString()
    {
        return String.valueOf(filterType()) + ":" + String.valueOf(filterName());
    }

    @Override
    public boolean needsBodyBuffered(I input) {
        return false;
    }

    @Override
    public HttpContent processContentChunk(ZuulMessage zuulMessage, HttpContent chunk) {
        return chunk;
    }

    @Override
    public void incrementConcurrency() throws ZuulFilterConcurrencyExceededException {
        final int limit = filterConcurrencyLimit.get();
        if ((concurrencyProtectEnabled.get()) && (concurrentCount.get() >= limit)) {
            concurrencyRejections.increment();
            throw new ZuulFilterConcurrencyExceededException(this, limit);
        }
        concurrentCount.incrementAndGet();
    }

    @Override
    public void decrementConcurrency() {
        concurrentCount.decrementAndGet();
    }

    public static class TestUnit {
        @Mock
        private BaseFilter f1;
        @Mock
        private BaseFilter f2;
        @Mock
        private ZuulMessage req;

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);
        }


        @Test
        public void testShouldFilter() {
            class TestZuulFilter extends BaseSyncFilter
            {
                @Override
                public int filterOrder() {
                    return 0;
                }

                @Override
                public FilterType filterType() {
                    return FilterType.INBOUND;
                }

                @Override
                public boolean shouldFilter(ZuulMessage req) {
                    return false;
                }

                @Override
                public ZuulMessage apply(ZuulMessage req) {
                    return null;
                }
            }

            TestZuulFilter tf1 = spy(new TestZuulFilter());
            TestZuulFilter tf2 = spy(new TestZuulFilter());

            when(tf1.shouldFilter(req)).thenReturn(true);
            when(tf2.shouldFilter(req)).thenReturn(false);
        }
    }
}
