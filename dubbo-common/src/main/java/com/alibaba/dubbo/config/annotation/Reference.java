/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.dubbo.config.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Deprecated
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface Reference {

    Class<?> interfaceClass() default void.class;

    String interfaceName() default "";
    // 说明: 多版本 集群选择Invoker 消费者存在版本号 则按版本号调用
    // 样例: 1.0.0
    String version() default "";
    // 说明: 分组
    // 样例: dubbo://127.0.0.1:8888
    String group() default "";
    // 说明: 直连消费者
    // 样例: dubbo://127.0.0.1:8888
    String url() default "";
    // 客户端类型 默认netty 对应Netty4框架
    String client() default "";
    // 是否泛化调用 参见高级特性泛化调用
    boolean generic() default false;
    // 本地暴露
    // 同一个进程的消费者与服务提供者直接调用,不通过网络通信
    boolean injvm() default true;
    // 启动检查提供者是否存在
    boolean check() default true;
    // 是否bean初始化过程中完成服务引用 afterPropertiesSet时完成加载
    boolean init() default false;
    // 是否初始化通信层[比如:NettyClient]
    boolean lazy() default false;
    // 开启本地存根
    boolean stubevent() default false;

    String reconnect() default "";
    // 是否开启粘滞功能  【如果调用过提供者,则一直调用该提供者 所有叫粘滞】
    boolean sticky() default false;
    // 动态代理策略 默认javassist
    String proxy() default "";
    // 本地存根的接口配置
    String stub() default "";
    // 集群容错策略 默认failover
    String cluster() default "";
    // 底层构建 remote层的连接数
    int connections() default 0;

    int callbacks() default 0;

    String onconnect() default "";

    String ondisconnect() default "";

    // 服务治理负责人
    String owner() default "";
    // 服务调用者所在的分层layer
    String layer() default "";
    // 重试次数结合特定的负载均衡一起使用
    int retries() default 2;
    // 负载均衡策略
    String loadbalance() default "";
    // 是否异步执行 AsyncToSyncInvoker
    boolean async() default false;
    // 最小活跃数配置 配合ActiveLimitFilter等工作
    int actives() default 0;
    // true等待消息发出，消息发送失败将抛出异常; false不等待消息发出，将消息放入IO队列,即刻返回
    boolean sent() default false;
    // mock 集群容错降级配置
    String mock() default "";
    // 是否开启jsr验证 基于filter实现
    String validation() default "";
    // 默认的超时配置
    int timeout() default 0;
    // 缓存的使用方式,结合cachefilter实现,为空无缓存
    // cache = lru | cache = lfu 等
    String cache() default "";
    // 基于spi的过滤器配置
    // demo filter = filter1,filter2
    String[] filter() default {};
    // 基于spi的监听器配置,基本不会被使用
    String[] listener() default {};
//    ----------------------------------------------------------------------------------------
    // 参数配置
    String[] parameters() default {};
    // 引用application配置
    String application() default "";
    // 引用module配置
    String module() default "";
    // 引用consumer配置
    String consumer() default "";
    // 引用monitor配置
    String monitor() default "";
    // 配置单注册中心或多注册中心
    String[] registry() default {};

}

