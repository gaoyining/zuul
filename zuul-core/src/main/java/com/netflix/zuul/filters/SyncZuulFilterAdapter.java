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

import static com.netflix.zuul.filters.FilterSyncType.SYNC;
import static com.netflix.zuul.filters.FilterType.ENDPOINT;

/**
 * Base class to help implement SyncZuulFilter. Note that the class BaseSyncFilter does exist but it derives from
 * BaseFilter which in turn creates a new instance of CachedDynamicBooleanProperty for "filterDisabled" every time you
 * create a new instance of the ZuulFilter. Normally it is not too much of a concern as the instances of ZuulFilters
 * are "effectively" singleton and are cached by ZuulFilterLoader. However, if you ever have a need for instantiating a
 * new ZuulFilter instance per request - aka EdgeProxyEndpoint or Inbound/Outbound PassportStampingFilter creating new
 * instances of CachedDynamicBooleanProperty per instance of ZuulFilter will quickly kill your server's performance in
 * two ways -
 * a) Instances of CachedDynamicBooleanProperty are *very* heavy CPU wise to create due to extensive hookups machinery
 *    in their constructor
 * b) They leak memory as they add themselves to some ConcurrentHashMap and are never garbage collected.
 *
 * TL;DR use this as a base class for your ZuulFilter if you intend to create new instances of ZuulFilter
 *
 *
 * 用于帮助实现SyncZuulFilter的基类。
 * 请注意，类BaseSyncFilter确实存在，但它派生自BaseFilter，
 * 而BaseFilter每次创建ZuulFilter的新实例时都会为“filterDisabled”创建一个新的CachedDynamicBooleanProperty实例。
 * 通常，由于ZuulFilters的实例是“有效”的单例并且由ZuulFilterLoader缓存，所以它并不是太令人担忧。
 * 但是，如果您需要为每个请求实例化一个新的ZuulFilter实例 -
 * 也就是EdgeProxyEndpoint或Inbound / Outbound PassportStampingFilter为每个ZuulFilter实例创建CachedDynamicBooleanProperty
 * 的新实例将很快破坏您的服务器的性能
 * 两种方式 -
 * a) 由于构造函数中有大量的连接机制，CachedDynamicBooleanProperty的实例是非常*非常重的CPU创建
 *
 * b) 他们将内存添加到某些ConcurrentHashMap并且从不进行垃圾回收时泄漏内存。
 *
 *
 * Created by saroskar on 6/8/17.
 */
public abstract class SyncZuulFilterAdapter<I extends ZuulMessage, O extends ZuulMessage> implements SyncZuulFilter<I, O> {

    @Override
    public boolean isDisabled() {
        return false;
    }

    @Override
    public boolean shouldFilter(I msg) {
        return true;
    }

    @Override
    public int filterOrder() {
        // Set all Endpoint filters to order of 0, because they are not processed sequentially like other filter types.
        // 将所有端点过滤器设置为0，因为它们不像其他过滤器类型那样按顺序处理。
        return 0;
    }

    @Override
    public FilterType filterType() {
        return ENDPOINT;
    }

    @Override
    public boolean overrideStopFilterProcessing() {
        return false;
    }

    @Override
    public Observable<O> applyAsync(I input) {
        return Observable.just(apply(input));
    }

    @Override
    public FilterSyncType getSyncType() {
        return SYNC;
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
    public void incrementConcurrency() {
        //NOOP for sync filters
    }

    @Override
    public void decrementConcurrency() {
        //NOOP for sync filters
    }
}