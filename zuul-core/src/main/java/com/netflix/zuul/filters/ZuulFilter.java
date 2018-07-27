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

import com.netflix.zuul.exception.ZuulFilterConcurrencyExceededException;
import com.netflix.zuul.message.ZuulMessage;
import io.netty.handler.codec.http.HttpContent;
import rx.Observable;

/**
 * BAse interface for ZuulFilters
 *
 * @author Mikey Cohen
 *         Date: 10/27/11
 *         Time: 3:03 PM
 */
public interface ZuulFilter<I extends ZuulMessage, O extends ZuulMessage> extends ShouldFilter<I>
{
    boolean isDisabled();

    String filterName();

    /**
     * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
     * important for a filter. filterOrders do not need to be sequential.
     *
     * 还必须为过滤器定义filterOrder（）。 如果优先级对于过滤器不重要，
     * 则过滤器可能具有相同的filterOrder。 filterOrders不需要是顺序的。
     *
     * @return the int order of a filter
     */
    int filterOrder();

    /**
     * to classify a filter by type. Standard types in Zuul are "in" for pre-routing filtering,
     * "end" for routing to an origin, "out" for post-routing filters.
     *
     * 按类型对过滤器进行分类。 Zuul中的标准类型是“in”用于路由前过滤，“end”用于路由到源，“out”用于路由后过滤器。
     *
     * @return FilterType
     */
    FilterType filterType();

    /**
     * Whether this filter's shouldFilter() method should be checked, and apply() called, even
     * if SessionContext.stopFilterProcessing has been set.
     *
     * 是否应该检查此过滤器的shouldFilter（）方法，并调用apply（），即使已设置了SessionContext.stopFilterProcessing。
     *
     * @return boolean
     */
    boolean overrideStopFilterProcessing();

    /**
     * Called by zuul filter runner before sending request through this filter. The filter can throw
     * ZuulFilterConcurrencyExceededException if it has reached its concurrent requests limit and does
     * not wish to process the request. Generally only useful for async filters.
     *
     * 在通过此过滤器发送请求之前由zuul过滤器运行程序调用。
     * 如果ZuulFilterConcurrencyExceededException已达到其并发请求限制并且不希望处理该请求，
     * 则该过滤器可以抛出ZuulFilterConcurrencyExceededException。 通常仅对异步过滤器有用。
     */
    void incrementConcurrency() throws ZuulFilterConcurrencyExceededException;

    /**
     * if shouldFilter() is true, this method will be invoked. this method is the core method of a ZuulFilter
     *
     * 如果shouldFilter（）为true，则将调用此方法。 这种方法是ZuulFilter的核心方法
     */
    Observable<O> applyAsync(I input);

    /**
     * Called by zuul filter after request is processed by this filter.
     *
     * 在此过滤器处理请求后由zuul过滤器调用。
     *
     */
    void decrementConcurrency();

    FilterSyncType getSyncType();

    /**
     * Choose a default message to use if the applyAsync() method throws an exception.
     *
     * 如果applyAsync（）方法抛出异常，请选择要使用的默认消息。
     *
     * @return ZuulMessage
     */
    O getDefaultOutput(I input);

    /**
     * Filter indicates it needs to read and buffer whole body before it can operate on the messages by returning true.
     * The decision can be made at runtime, looking at the request type. For example if the incoming message is a MSL
     * message MSL decryption filter can return true here to buffer whole MSL message before it tries to decrypt it.
     * @return true if this filter needs to read whole body before it can run, false otherwise
     *
     * 过滤器指示它需要读取并缓冲整个主体，然后才能通过返回true来对消息进行操作。 可以在运行时做出决定，查看请求类型。
     * 例如，如果传入消息是MSL消息，则MSL解密过滤器可以在此处返回true以在尝试解密之前缓冲整个MSL消息。
     * @return 如果此过滤器需要先读取整个主体才能运行，否则返回false
     */
    boolean needsBodyBuffered(I input);

    /**
     * Optionally transform HTTP content chunk received
     *
     * （可选）转换收到的HTTP内容块
     *
     * @param chunk
     * @return
     */
    HttpContent processContentChunk(ZuulMessage zuulMessage, HttpContent chunk);
}
