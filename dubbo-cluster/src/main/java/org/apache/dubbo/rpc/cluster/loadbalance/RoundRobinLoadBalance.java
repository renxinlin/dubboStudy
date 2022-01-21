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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    private static final int RECYCLE_PERIOD = 60000;

    protected static class WeightedRoundRobin {
        // 当前Invoker的权重
        private int weight;

        // 当前的轮询值 每次增加为weight  每次减少为所有Invoker的weight和
        private AtomicLong current = new AtomicLong(0);

        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }

        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 获取特定调用的调用者地址列表缓存
     *
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }


    /*


                      Invoker1 Invoker2 Invoker3   选择结果
      weight          100      100      100
      current         0        0        0

      current第一轮    100      100      100
      current第一轮    -200     100      100       选择Invoker1  [轮询值为100-100*3](current-总权重)

      current第二轮    -100     200      200
      current第二轮    -100     -100     200       选择Invoker2  [200-100*3](current-总权重)

      current第三轮     0     0      300
      current第三轮     0      0      0            选择Invoker3  [300-100*3](current-总权重)

      一个周期后,所有的Invoker的current值基本回归


     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取当前调用方法的 加权轮询元信息(WeightedRoundRobin)
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // {methodWeightMap: key->当前方法,val-> 所有Invoker对应的轮询信息}   {map,当前所有的invoker key -> 当前Invoker的唯一id ,val -> 当前的轮询信息 }
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        // 所有的Invoker遍历处理
        for (Invoker<T> invoker : invokers) {
            // 获取当前invoker的唯一标识
            String identifyString = invoker.getUrl().toIdentityString();
            // 获取每一个Invoker的权重
            int weight = getWeight(invoker, invocation);
            //  给每一个轮询信息设置权重
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                wrr.setWeight(weight);
                return wrr;
            });
            if (weight != weightedRoundRobin.getWeight()) {
                weightedRoundRobin.setWeight(weight);
            }
            // 每次增加自己的当前轮询值为权重weight
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);

            // 找出当前轮询值最小的Invoker
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }


        if (invokers.size() != map.size()) {
            // 如果一个WeightedRoundRobin 60秒都没有参与过for循环里的轮询处理,则移除该Invoker
            // 就要借助methodWeightMap记忆轮询的信息,又清除了Invoker变动残存的轮询信息
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }
        if (selectedInvoker != null) {
            // 每次被选中后,当前的轮询值减去所有Invoker的轮询值  从而达到轮询效果
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}



