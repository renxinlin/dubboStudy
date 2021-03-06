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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.retry.ReExportTask;
import org.apache.dubbo.registry.support.SkipFailbackWrapperException;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.governance.GovernanceRuleRepository;
import org.apache.dubbo.rpc.cluster.support.MergeableCluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CLUSTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.EXTRA_KEYS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.RELEASE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.FilterConstants.VALIDATION_KEY;
import static org.apache.dubbo.common.constants.QosConstants.ACCEPT_FOREIGN_IP;
import static org.apache.dubbo.common.constants.QosConstants.QOS_ENABLE;
import static org.apache.dubbo.common.constants.QosConstants.QOS_HOST;
import static org.apache.dubbo.common.constants.QosConstants.QOS_PORT;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.OVERRIDE_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;
import static org.apache.dubbo.registry.Constants.CONFIGURATORS_SUFFIX;
import static org.apache.dubbo.registry.Constants.CONSUMER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY;
import static org.apache.dubbo.registry.Constants.DEFAULT_REGISTRY_RETRY_PERIOD;
import static org.apache.dubbo.registry.Constants.PROVIDER_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTER_IP_KEY;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.registry.Constants.REGISTRY_RETRY_PERIOD_KEY;
import static org.apache.dubbo.registry.Constants.SIMPLIFIED_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.remoting.Constants.CHECK_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.EXCHANGER_KEY;
import static org.apache.dubbo.remoting.Constants.SERIALIZATION_KEY;
import static org.apache.dubbo.rpc.Constants.DEPRECATED_KEY;
import static org.apache.dubbo.rpc.Constants.INTERFACES;
import static org.apache.dubbo.rpc.Constants.MOCK_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WARMUP_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.WEIGHT_KEY;

/**
 * RegistryProtocol
 */
public class RegistryProtocol implements Protocol {
    public static final String[] DEFAULT_REGISTER_PROVIDER_KEYS = {
            APPLICATION_KEY, CODEC_KEY, EXCHANGER_KEY, SERIALIZATION_KEY, CLUSTER_KEY, CONNECTIONS_KEY, DEPRECATED_KEY,
            GROUP_KEY, LOADBALANCE_KEY, MOCK_KEY, PATH_KEY, TIMEOUT_KEY, TOKEN_KEY, VERSION_KEY, WARMUP_KEY,
            WEIGHT_KEY, TIMESTAMP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    public static final String[] DEFAULT_REGISTER_CONSUMER_KEYS = {
            APPLICATION_KEY, VERSION_KEY, GROUP_KEY, DUBBO_VERSION_KEY, RELEASE_KEY
    };

    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    /*
     provider ?????????listener
    *
    * */
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<>();
    private final Map<String, ServiceConfigurationListener> serviceConfigurationListeners = new ConcurrentHashMap<>();
    private final ProviderConfigurationListener providerConfigurationListener = new ProviderConfigurationListener();
    //To solve the problem of RMI repeated exposure port conflicts, the services that have been exposed are no longer exposed.
    //providerurl <--> exporter
    // ????????????rmi??????????????????????????????????????????????????????????????????????????????
    private final ConcurrentMap<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<>();
    // protocol  ?????????????????????????????????
    private Protocol protocol;
    //   ?????????????????????????????????
    private RegistryFactory registryFactory;
    private ProxyFactory proxyFactory;

    private ConcurrentMap<URL, ReExportTask> reExportFailedTasks = new ConcurrentHashMap<>();
    private HashedWheelTimer retryTimer = new HashedWheelTimer(new NamedThreadFactory("DubboReexportTimer", true), DEFAULT_REGISTRY_RETRY_PERIOD, TimeUnit.MILLISECONDS, 128);

    //Filter the parameters that do not need to be output in url(Starting with .)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (CollectionUtils.isNotEmptyMap(params)) {
            return params.keySet().stream()
                    .filter(k -> k.startsWith(HIDE_KEY_PREFIX))
                    .toArray(String[]::new);
        } else {
            return new String[0];
        }
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    @Override
    public int getDefaultPort() {
        return 9090;
    }

    public Map<URL, NotifyListener> getOverrideListeners() {
        return overrideListeners;
    }

    private void register(URL registryUrl, URL registeredProviderUrl) {
        Registry registry = registryFactory.getRegistry(registryUrl);
        registry.register(registeredProviderUrl);
    }

    private void registerStatedUrl(URL registryUrl, URL registeredProviderUrl, boolean registered) {
        ProviderModel model = ApplicationModel.getProviderModel(registeredProviderUrl.getServiceKey());
        model.addStatedUrl(new ProviderModel.RegisterStatedURL(
                registeredProviderUrl,
                registryUrl,
                registered));
    }

    @Override
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        // ?????????????????? URL ???register???????????????????????????,??????zookeeper://
        // zookeeper://127.0.0.1:2181/org.apache.dubbo.registry.RegistryService?application=demo-provider&dubbo=2.0.2
        // &export=dubbo://192.168.111.223:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.111.223&bind.port=20880&delay=1&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&metadata-type=remote&methods=sayHello,sayHelloAsync&pid=19409&qos.port=22222&release=&side=provider&timestamp=1612529550788&metadata-type=remote&pid=19409&qos.port=22222&timestamp=1612529545776
        URL registryUrl = getRegistryUrl(originInvoker);
        // url to export locally
        // ????????????servicebean?????????export????????????url ??????????????? URL
        //         dubbo://192.168.111.223:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=demo-provider&bind.ip=192.168.111.223&bind.port=20880&delay=1&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=org.apache.dubbo.demo.DemoService&metadata-type=remote&methods=sayHello,sayHelloAsync&pid=19409&qos.port=22222&release=&side=provider&timestamp=1612529550788
        URL providerUrl = getProviderUrl(originInvoker);

        // Subscribe the override data
        // ???????????????overrid category???configurators
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(providerUrl);
        // ?????? OverrideListener ????????????????????? category=configurators ??????
        // provider://192.168.82.94:19012/com.renxl.demo.DYOmsClient?anyhost=true&application=renxlApplication&bean.name=ServiceBean:com.renxl.demo.DYOmsClient&bind.ip=192.168.82.94&bind.port=19012&category=configurators&check=false&delay=-1&deprecated=false&dubbo=2.0.2&dynamic=true&generic=false&interface=com.renxl.demo.DYOmsClient&methods=renxlmethod&pid=39115&qos.enable=false&release=2.7.4.1&side=provider&timeout=3000&timestamp=1641978804642
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl, originInvoker);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);

        providerUrl = overrideUrlWithConfig(providerUrl, overrideSubscribeListener);

        //export invoker
        //  // step-1: ??????dubboProtocol ???????????? ??????exporter ??????netty
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker, providerUrl);

        // url to registry step-2: ???????????????????????????zkClient
        final Registry registry = getRegistry(originInvoker);
        // ??????provider url???????????????key  ?????????provider?????????????????????
        final URL registeredProviderUrl = getUrlToRegistry(providerUrl, registryUrl);

        // decide if we need to delay publish
        boolean register = providerUrl.getParameter(REGISTER_KEY, true);
        if (register) {
            // // step-3: ????????????????????????????????????????????????
            // ??????????????????????????????????????????????????????????????????????????? ??????Provider??????
            register(registryUrl, registeredProviderUrl);
        }

        // register stated url on provider model
        registerStatedUrl(registryUrl, registeredProviderUrl, register);


        exporter.setRegisterUrl(registeredProviderUrl);
        exporter.setSubscribeUrl(overrideSubscribeUrl);
        // step-4: ?????? override ??????
        // Deprecated! Subscribe to override rules in 2.6.x or before.
        /*
            ???????????? ???????????????  ?????????notifyOverrideListener registryDirectory

         */
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);


        /*
                            /dubbo
                                   /interface
            Providers ???????????????????????????????????? URL ???????????????
            Consumers ????????????????????????????????? URL ???????????????
            Routers ????????????????????????????????????????????????
            Configurators ???????????????????????????????????????????????????????????? URL ???????????????
         */
        // ????????? ?????????zk?????????????????? ???????????????????????????????????? ??????????????????
        //
        notifyExport(exporter);
        //Ensure that a new exporter instance is returned every time export
        return new DestroyableExporter<>(exporter);
    }

    private <T> void notifyExport(ExporterChangeableWrapper<T> exporter) {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(exporter.getOriginInvoker().getUrl(), "registry.protocol.listener");
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onExport(this, exporter);
            }
        }
    }

    private URL overrideUrlWithConfig(URL providerUrl, OverrideListener listener) {
        providerUrl = providerConfigurationListener.overrideUrl(providerUrl);
        ServiceConfigurationListener serviceConfigurationListener = new ServiceConfigurationListener(providerUrl, listener);
        serviceConfigurationListeners.put(providerUrl.getServiceKey(), serviceConfigurationListener);
        return serviceConfigurationListener.overrideUrl(providerUrl);
    }

    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T> doLocalExport(final Invoker<T> originInvoker, URL providerUrl) {
        // // ????????? `bounds` ???????????? Key
        String key = getCacheKey(originInvoker);
        // ??? `bounds` ???????????????????????????????????????
        return (ExporterChangeableWrapper<T>) bounds.computeIfAbsent(key, s -> {
            // ?????? Invoker Delegate ??????
            Invoker<?> invokerDelegate = new InvokerDelegate<>(originInvoker, providerUrl);
            // ????????????????????? Exporter ??????
            // ?????? ?????????Exporter?????? + originInvoker ????????? ExporterChangeableWrapper ?????? [DubboProtocol#export(invoker)]
            return new ExporterChangeableWrapper<>((Exporter<T>) protocol.export(invokerDelegate), originInvoker);
        });
    }

    public <T> void reExport(Exporter<T> exporter, URL newInvokerUrl) {
        if (exporter instanceof ExporterChangeableWrapper) {
            ExporterChangeableWrapper<T> exporterWrapper = (ExporterChangeableWrapper<T>) exporter;
            Invoker<T> originInvoker = exporterWrapper.getOriginInvoker();
            reExport(originInvoker, newInvokerUrl);
        }
    }

    /**
     * Reexport the invoker of the modified url
     *
     * @param originInvoker
     * @param newInvokerUrl
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    public <T> void reExport(final Invoker<T> originInvoker, URL newInvokerUrl) {
        String key = getCacheKey(originInvoker);
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        URL registeredUrl = exporter.getRegisterUrl();

        URL registryUrl = getRegistryUrl(originInvoker);
        URL newProviderUrl = getUrlToRegistry(newInvokerUrl, registryUrl);

        // update local exporter
        Invoker<T> invokerDelegate = new InvokerDelegate<T>(originInvoker, newInvokerUrl);
        exporter.setExporter(protocol.export(invokerDelegate));

        // update registry
        if (!newProviderUrl.equals(registeredUrl)) {
            try {
                doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl);
            } catch (Exception e) {
                ReExportTask oldTask = reExportFailedTasks.get(registeredUrl);
                if (oldTask != null) {
                    return;
                }
                ReExportTask task = new ReExportTask(
                        () -> doReExport(originInvoker, exporter, registryUrl, registeredUrl, newProviderUrl),
                        registeredUrl,
                        null
                );
                oldTask = reExportFailedTasks.putIfAbsent(registeredUrl, task);
                if (oldTask == null) {
                    // never has a retry task. then start a new task for retry.
                    retryTimer.newTimeout(task, registryUrl.getParameter(REGISTRY_RETRY_PERIOD_KEY, DEFAULT_REGISTRY_RETRY_PERIOD), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private <T> void doReExport(final Invoker<T> originInvoker, ExporterChangeableWrapper<T> exporter,
                                URL registryUrl, URL oldProviderUrl, URL newProviderUrl) {
        if (getProviderUrl(originInvoker).getParameter(REGISTER_KEY, true)) {
            Registry registry = null;
            try {
                registry = getRegistry(originInvoker);
            } catch (Exception e) {
                throw new SkipFailbackWrapperException(e);
            }

            logger.info("Try to unregister old url: " + oldProviderUrl);
            registry.reExportUnregister(oldProviderUrl);

            logger.info("Try to register new url: " + newProviderUrl);
            registry.reExportRegister(newProviderUrl);
        }
        try {
            ProviderModel.RegisterStatedURL statedUrl = getStatedUrl(registryUrl, newProviderUrl);
            statedUrl.setProviderUrl(newProviderUrl);
            exporter.setRegisterUrl(newProviderUrl);
        } catch (Exception e) {
            throw new SkipFailbackWrapperException(e);
        }
    }

    private ProviderModel.RegisterStatedURL getStatedUrl(URL registryUrl, URL providerUrl) {
        ProviderModel providerModel = ApplicationModel.getServiceRepository()
                .lookupExportedService(providerUrl.getServiceKey());

        List<ProviderModel.RegisterStatedURL> statedUrls = providerModel.getStatedUrl();
        return statedUrls.stream()
                .filter(u -> u.getRegistryUrl().equals(registryUrl)
                        && u.getProviderUrl().getProtocol().equals(providerUrl.getProtocol()))
                .findFirst().orElseThrow(() -> new IllegalStateException("There should have at least one registered url."));
    }

    /**
     * Get an instance of registry based on the address of invoker
     *
     * @param originInvoker
     * @return
     */
    protected Registry getRegistry(final Invoker<?> originInvoker) {
        URL registryUrl = getRegistryUrl(originInvoker);
        // registryFactory$Adaptive  zookeeper
        return registryFactory.getRegistry(registryUrl);
    }

    protected URL getRegistryUrl(Invoker<?> originInvoker) {
        URL registryUrl = originInvoker.getUrl();
//        ????????????url?????????????????? ??????????????????
        if (REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
//            ????????????registry????????????????????????????????????????????? ??????zookeeper
            String protocol = registryUrl.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(REGISTRY_KEY);
        }
        return registryUrl;
    }

    protected URL getRegistryUrl(URL url) {
        return URLBuilder.from(url)
                .setProtocol(url.getParameter(REGISTRY_KEY, DEFAULT_REGISTRY))
                .removeParameter(REGISTRY_KEY)
                .build();
    }


    /**
     * Return the url that is registered to the registry and filter the url parameter once
     *
     * @param providerUrl
     * @return url to registry.
     */
    private URL getUrlToRegistry(final URL providerUrl, final URL registryUrl) {
        //The address you see at the registry
        if (!registryUrl.getParameter(SIMPLIFIED_KEY, false)) {
            return providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameters(
                    MONITOR_KEY, BIND_IP_KEY, BIND_PORT_KEY, QOS_ENABLE, QOS_HOST, QOS_PORT, ACCEPT_FOREIGN_IP, VALIDATION_KEY,
                    INTERFACES);
        } else {
            String extraKeys = registryUrl.getParameter(EXTRA_KEYS_KEY, "");
            // if path is not the same as interface name then we should keep INTERFACE_KEY,
            // otherwise, the registry structure of zookeeper would be '/dubbo/path/providers',
            // but what we expect is '/dubbo/interface/providers'
            if (!providerUrl.getPath().equals(providerUrl.getParameter(INTERFACE_KEY))) {
                if (StringUtils.isNotEmpty(extraKeys)) {
                    extraKeys += ",";
                }
                extraKeys += INTERFACE_KEY;
            }
            String[] paramsToRegistry = getParamsToRegistry(DEFAULT_REGISTER_PROVIDER_KEYS
                    , COMMA_SPLIT_PATTERN.split(extraKeys));
            return URL.valueOf(providerUrl, paramsToRegistry, providerUrl.getParameter(METHODS_KEY, (String[]) null));
        }

    }

    private URL getSubscribedOverrideUrl(URL registeredProviderUrl) {
        return registeredProviderUrl.setProtocol(PROVIDER_PROTOCOL)
                .addParameters(CATEGORY_KEY, CONFIGURATORS_CATEGORY, CHECK_KEY, String.valueOf(false));
    }

    /**
     * Get the address of the providerUrl through the url of the invoker
     *
     * @param originInvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> originInvoker) {
        String export = originInvoker.getUrl().getParameterAndDecoded(EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + originInvoker.getUrl());
        }
        return URL.valueOf(export);
    }

    /**
     * Get the key cached in bounds by invoker
     *
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker) {
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }

    /**
     *
     * @param type Service class
     * @param url zookeeper://dev.zk.mockuai.com:2181/com.alibaba.dubbo.registry.RegistryService?application=ec-shopcenter-web&dubbo=2.0.2&pid=36236&qos.enable=false&refer=application=ec-shopcenter-web&default.check=false&default.cluster=failfast&default.timeout=3000&default.validation=true&dubbo=2.0.2&interface=com.mockuai.ec.usercenter.common.api.AuthorityService&methods=listRole,listAdminInterfacePermission,listGuestUserWithPwd,deleteMenuById,listUserAuthorityMenu,updateRole,addMenu,getRole,listMenu,listGuestUser,deleteRole,getGuestUser,updateGuestUser,updateMenu,addGuestUser,getGuestUserWithPwd,getMenu,deleteGuestUser,checkUserAuth,listGuestUserWithAuthUrl,addRole&pid=36236&qos.enable=false&register.ip=192.168.2.115&revision=1.0.4&side=consumer&timestamp=1612808139933&timestamp=1612808152907
     * @param <T> ??????????????????
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        //zookeeper://dev.zk.mockuai.com:2181/org.apache.dubbo.registry.RegistryService?application=ec-omscenter-admin&dubbo=2.0.2&pid=54433&qos.enable=false&refer=application%3Dec-omscenter-admin%26check%3Dfalse%26cluster%3Dfailfast%26dubbo%3D2.0.2%26interface%3Dcom.mockuai.ec.deliverycenter.common.api.DeliveryService%26lazy%3Dfalse%26methods%3DaddPackages%2CaddGeneralExcelDelivery%2CaddDeliveryPackages%2CexportDeliveryInfoStatusDTOList%2CgetDeliveryRegionByName%2CgetDeliveryInfoDetail%2CupdateDeliveryInfo%2CqueryDeliveryInfoListByOrderId%2CgetDeliveryImportLogCount%2CbGetDeliveryInfoById%2CaddErpDeliveryInfo%2CqueryDeliveryRegionNew%2ClistDeliveryInfoById%2CqueryDeliveryInfoByExpressNo%2CcQueryPackagesIds%2CqueryLogisticsCompany%2CupdateGeneralExcelDelivery%2CupdateDeliveryPackages%2CaQueryKsRelExpressInfo%2CqueryPackages%2CqueryDeliveryImportLog%2CbQueryPackagesRealLogistics%2CupdateDeliveryInfoByExpressNo%2CaGetDeliveryInfoById%2CqueryDeliveryInfoByIds%2CupdatePackages%2CmgetDeliveryLogistcisInfo%2CcGetDeliveryInfoById%2CqueryLogisticsCompanyHasCache%2CaddDeliveryInfo%2CgetDeliveryInfoById%2CaddImportFileLog%2CaQueryPackagesIds%2CaQueryPackages%2CgetDeliveryInfoByExpressNo%2CqueryLogisticsCompanyTemplate%2CqueryDeliveryRegion%2CbQueryPackages%2CqueryDeliveryInfo%2CqueryDeliveryInfoByExpressNoV2%2CcQueryPackages%26pid%3D54433%26qos.enable%3Dfalse%26register.ip%3D192.168.2.103%26release%3D2.7.4.1%26revision%3D2.0.6%26side%3Dconsumer%26sticky%3Dfalse%26timeout%3D3000%26timestamp%3D1642246833742&release=2.7.4.1&timestamp=1642248195560
        /*
        protocol = "zookeeper"
        username = null
        password = null
        host = "dev.zk.mockuai.com"
        port = 2181
        path = "org.apache.dubbo.registry.RegistryService"
        parameters = {Collections$UnmodifiableMap@13226}
         "application" -> "ec-omscenter-admin"
         "refer" -> "application%3Dec-omscenter-admin%26check%3Dfalse%26cluster%3Dfailfast%26dubbo%3D2.0.2%26interface%3Dcom.mockuai.ec.deliverycenter.common.api.DeliveryService%26lazy%3Dfalse%26methods%3DaddPackages%2CaddGeneralExcelDelivery%2CaddDeliveryPackages%2CexportDeliveryInfoStatusDTOList%2CgetDeliveryRegionByName%2CgetDeliveryInfoDetail%2CupdateDeliveryInfo%2CqueryDeliveryInfoListByOrderId%2CgetDeliveryImportLogCount%2CbGetDeliveryInfoById%2CaddErpDeliveryInfo%2CqueryDeliveryRegionNew%2ClistDeliveryInfoById%2CqueryDeliveryInfoByExpressNo%2CcQueryPackagesIds%2CqueryLogisticsCompany%2CupdateGeneralExcelDelivery%2CupdateDeliveryPackages%2CaQueryKsRelExpressInfo%2CqueryPackages%2CqueryDeliveryImportLog%2CbQueryPackagesRealLogistics%2CupdateDeliveryInfoByExpressNo%2CaGetDeliveryInfoById%2CqueryDeliveryInfoByIds%2CupdatePackages%2CmgetDeliveryLogistcisInfo%2CcGetDeliveryInfoById%2CqueryLogisticsCompanyHasCache%2CaddDeliveryInfo%2CgetDeliveryInfoById%2CaddImportFileLog%2CaQueryPackagesIds%2CaQueryPackag"
         "release" -> "2.7.4.1"
         "dubbo" -> "2.0.2"
         "pid" -> "54433"
         "qos.enable" -> "false"
         "timestamp" -> "1642248195560"

         */
        url = getRegistryUrl(url);
        // ??????url?????????????????? ??????zookeeper????????????[??????????????????zk server?????????client??????]
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
            return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(REFER_KEY));
        String group = qs.get(GROUP_KEY);
        // ?????????
        if (group != null && group.length() > 0) {
            if ((COMMA_SPLIT_PATTERN.split(group)).length > 1 || "*".equals(group)) { // group = "group1,group2"
                return doRefer(Cluster.getCluster(MergeableCluster.NAME), registry, type, url);
            }
        }
        // ?????????
        Cluster cluster = Cluster.getCluster(qs.get(CLUSTER_KEY));
        return doRefer(cluster, registry, type, url);
    }

    /**
     *
     * @param cluster  ????????????
     * @param registry ?????????????????????
     * @param type     ???????????????
     * @param url      url
     * @param <T>
     * @return
     */
    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        // ?????? RegistryDirectory ??????????????????????????????  RegistryDirectory??????????????????NotifyListener ??????????????????????????????????????????????????????????????????
        // ??????????????????Invoker????????? RegistryDirectory ,????????????Invoker???????????????zk?????????
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // all attributes of REFER_KEY
        // ???????????? URL
        Map<String, String> parameters = new HashMap<String, String>(directory.getConsumerUrl().getParameters());
        URL subscribeUrl = new URL(CONSUMER_PROTOCOL, parameters.remove(REGISTER_IP_KEY), 0, type.getName(), parameters);
        // ????????????????????????????????????????????????
        if (directory.isShouldRegister()) {
            directory.setRegisteredConsumerUrl(subscribeUrl);
            /**
             * ???zk consumers????????????????????????
             * consumer://192.168.2.115/com.mockuai.ec.usercenter.common.api.AuthorityService?application=ec-shopcenter-web&category=consumers&check=false&default.check=false&default.cluster=failfast&default.timeout=3000&default.validation=true&dubbo=2.0.2
             * &interface=com.mockuai.ec.usercenter.common.api.AuthorityService&methods=listRole,listAdminInterfacePermission,addRole&pid=36236&qos.enable=false&revision=1.0.4&side=consumer&timestamp=1612808139933
             * */
            registry.register(directory.getRegisteredConsumerUrl());
        }
        directory.buildRouterChain(subscribeUrl);
        //   ??????zk ???providers configurators?????????router????????????
//        consumer://192.168.2.115/com.mockuai.ec.usercenter.common.api.AuthorityService?application=ec-shopcenter-web&category=providers,configurators,routers&default.check=false&default.cluster=failfast&default.timeout=3000&default.validation=true&dubbo=2.0.2
//        &interface=com.mockuai.ec.usercenter.common.api.AuthorityService&methods=listRole,listAdminInterfacePermission,listGuestUserWithPwd,deleteMenuById,listUserAuthorityMenu,updateRole,addMenu,getRole,listMenu,listGuestUser,deleteRole,getGuestUser,updateGuestUser,updateMenu,addGuestUser,getGuestUserWithPwd,getMenu,deleteGuestUser,checkUserAuth,listGuestUserWithAuthUrl,addRole&pid=36236&qos.enable=false&revision=1.0.4&side=consumer&timestamp=1612808139933


        ////////////////////////////////////////////////////////////////////////////////////////////////
        ////////////////////////////////////
        //     // ?????? providers???configurators???routers ??????????????? notify ???????????????provider ??????dubboProtocol
        ////////////////////////////////////////////////////////////////////////////////////////////////
        // ???directory ????????????dubboInvoker ?????? ??????????????????????????????????????????RegisterDirectory???notify ????????????dubbo????????????Invoker???directory
        directory.subscribe(toSubscribeUrl(subscribeUrl));

        // ?????? Invoker ??????
        //     // ???????????????????????????????????????????????????????????????????????????????????????????????????????????? ??????failover??????
        Invoker<T> invoker = cluster.join(directory);
        List<RegistryProtocolListener> listeners = findRegistryProtocolListeners(url);
        if (CollectionUtils.isEmpty(listeners)) {
            return invoker;
        }
        // ?????? ??????
        RegistryInvokerWrapper<T> registryInvokerWrapper = new RegistryInvokerWrapper<>(directory, cluster, invoker);
        for (RegistryProtocolListener listener : listeners) {
            listener.onRefer(this, registryInvokerWrapper);
        }
        return registryInvokerWrapper;
    }

    public <T> void reRefer(Invoker<T> invoker, URL newSubscribeUrl) {
        if (!(invoker instanceof RegistryInvokerWrapper)) {
            return;
        }

        RegistryInvokerWrapper<T> invokerWrapper = (RegistryInvokerWrapper<T>) invoker;
        URL oldSubscribeUrl = invokerWrapper.getUrl();
        RegistryDirectory<T> directory = invokerWrapper.getDirectory();
        Registry registry = directory.getRegistry();
        registry.unregister(directory.getRegisteredConsumerUrl());
        directory.unSubscribe(toSubscribeUrl(oldSubscribeUrl));

        directory.setRegisteredConsumerUrl(newSubscribeUrl);
        registry.register(directory.getRegisteredConsumerUrl());
        directory.buildRouterChain(newSubscribeUrl);
        directory.subscribe(toSubscribeUrl(newSubscribeUrl));

        invokerWrapper.setInvoker(invokerWrapper.getCluster().join(directory));
    }

    private static URL toSubscribeUrl(URL url) {
        return url.addParameter(CATEGORY_KEY, PROVIDERS_CATEGORY + "," + CONFIGURATORS_CATEGORY + "," + ROUTERS_CATEGORY);
    }

    private List<RegistryProtocolListener> findRegistryProtocolListeners(URL url) {
        return ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getActivateExtension(url, "registry.protocol.listener");
    }

    // available to test
    public String[] getParamsToRegistry(String[] defaultKeys, String[] additionalParameterKeys) {
        int additionalLen = additionalParameterKeys.length;
        String[] registryParams = new String[defaultKeys.length + additionalLen];
        System.arraycopy(defaultKeys, 0, registryParams, 0, defaultKeys.length);
        System.arraycopy(additionalParameterKeys, 0, registryParams, defaultKeys.length, additionalLen);
        return registryParams;
    }

    @Override
    public void destroy() {
        List<RegistryProtocolListener> listeners = ExtensionLoader.getExtensionLoader(RegistryProtocolListener.class)
                .getLoadedExtensionInstances();
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (RegistryProtocolListener listener : listeners) {
                listener.onDestroy();
            }
        }

        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for (Exporter<?> exporter : exporters) {
            exporter.unexport();
        }
        bounds.clear();

        ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                .removeListener(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX, providerConfigurationListener);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

    //Merge the urls of configurators
    private static URL getConfigedInvokerUrl(List<Configurator> configurators, URL url) {
        if (configurators != null && configurators.size() > 0) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    public static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private final Invoker<T> invoker;

        /**
         * @param invoker
         * @param url     invoker.getUrl return this value
         */
        public InvokerDelegate(Invoker<T> invoker, URL url) {
            super(invoker, url);
            this.invoker = invoker;
        }

        public Invoker<T> getInvoker() {
            if (invoker instanceof InvokerDelegate) {
                return ((InvokerDelegate<T>) invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }

    private static class DestroyableExporter<T> implements Exporter<T> {

        private Exporter<T> exporter;

        public DestroyableExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        @Override
        public void unexport() {
            exporter.unexport();
        }
    }

    /**
     * Reexport: the exporter destroy problem in protocol
     * 1.Ensure that the exporter returned by registryprotocol can be normal destroyed
     * 2.No need to re-register to the registry after notify
     * 3.The invoker passed by the export method , would better to be the invoker of exporter
     */
    private class OverrideListener implements NotifyListener {
        private final URL subscribeUrl;
        private final Invoker originInvoker;


        private List<Configurator> configurators;

        public OverrideListener(URL subscribeUrl, Invoker originalInvoker) {
            this.subscribeUrl = subscribeUrl;
            this.originInvoker = originalInvoker;
        }

        /**
         * @param urls The list of registered information, is always not empty, The meaning is the same as the
         *             return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
         */
        @Override
        public synchronized void notify(List<URL> urls) {
            logger.debug("original override urls: " + urls);

            List<URL> matchedUrls = getMatchedUrls(urls, subscribeUrl.addParameter(CATEGORY_KEY,
                    CONFIGURATORS_CATEGORY));
            logger.debug("subscribe url: " + subscribeUrl + ", override urls: " + matchedUrls);

            // No matching results
            if (matchedUrls.isEmpty()) {
                return;
            }

            this.configurators = Configurator.toConfigurators(classifyUrls(matchedUrls, UrlUtils::isConfigurator))
                    .orElse(configurators);

            doOverrideIfNecessary();
        }

        public synchronized void doOverrideIfNecessary() {
            final Invoker<?> invoker;
            if (originInvoker instanceof InvokerDelegate) {
                invoker = ((InvokerDelegate<?>) originInvoker).getInvoker();
            } else {
                invoker = originInvoker;
            }
            //The origin invoker
            URL originUrl = RegistryProtocol.this.getProviderUrl(invoker);
            String key = getCacheKey(originInvoker);
            ExporterChangeableWrapper<?> exporter = bounds.get(key);
            if (exporter == null) {
                logger.warn(new IllegalStateException("error state, exporter should not be null"));
                return;
            }
            //The current, may have been merged many times
            URL currentUrl = exporter.getInvoker().getUrl();
            //Merged with this configuration
            URL newUrl = getConfigedInvokerUrl(configurators, currentUrl);
            newUrl = getConfigedInvokerUrl(providerConfigurationListener.getConfigurators(), newUrl);
            newUrl = getConfigedInvokerUrl(serviceConfigurationListeners.get(originUrl.getServiceKey())
                    .getConfigurators(), newUrl);
            if (!currentUrl.equals(newUrl)) {
                RegistryProtocol.this.reExport(originInvoker, newUrl);
                logger.info("exported provider url changed, origin url: " + originUrl +
                        ", old export url: " + currentUrl + ", new export url: " + newUrl);
            }
        }

        private List<URL> getMatchedUrls(List<URL> configuratorUrls, URL currentSubscribe) {
            List<URL> result = new ArrayList<URL>();
            for (URL url : configuratorUrls) {
                URL overrideUrl = url;
                // Compatible with the old version
                if (url.getParameter(CATEGORY_KEY) == null && OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
                    overrideUrl = url.addParameter(CATEGORY_KEY, CONFIGURATORS_CATEGORY);
                }

                // Check whether url is to be applied to the current service
                if (UrlUtils.isMatch(currentSubscribe, overrideUrl)) {
                    result.add(url);
                }
            }
            return result;
        }
    }

    private class ServiceConfigurationListener extends AbstractConfiguratorListener {
        private URL providerUrl;
        private OverrideListener notifyListener;

        public ServiceConfigurationListener(URL providerUrl, OverrideListener notifyListener) {
            this.providerUrl = providerUrl;
            this.notifyListener = notifyListener;
            this.initWith(DynamicConfiguration.getRuleKey(providerUrl) + CONFIGURATORS_SUFFIX);
        }

        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            notifyListener.doOverrideIfNecessary();
        }
    }

    private class ProviderConfigurationListener extends AbstractConfiguratorListener {

        public ProviderConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + CONFIGURATORS_SUFFIX);
        }

        /**
         * Get existing configuration rule and override provider url before exporting.
         *
         * @param providerUrl
         * @param <T>
         * @return
         */
        private <T> URL overrideUrl(URL providerUrl) {
            return RegistryProtocol.getConfigedInvokerUrl(configurators, providerUrl);
        }

        @Override
        protected void notifyOverrides() {
            overrideListeners.values().forEach(listener -> ((OverrideListener) listener).doOverrideIfNecessary());
        }
    }

    /**
     * ?????????????????? Exporter?????????Protocol export ?????? Exporter?????????????????????
     * exporter proxy, establish the corresponding relationship between the returned exporter and the exporter
     * exported by the protocol, and can modify the relationship at the time of override.
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T> {

        private final ExecutorService executor = newSingleThreadExecutor(new NamedThreadFactory("Exporter-Unexport", true));

        private final Invoker<T> originInvoker;
        private Exporter<T> exporter;
        private URL subscribeUrl;
        private URL registerUrl;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker) {
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }

        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        @Override
        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }

        public void setExporter(Exporter<T> exporter) {
            this.exporter = exporter;
        }

        @Override
        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);

            Registry registry = RegistryProtocol.this.getRegistry(originInvoker);
            try {
                registry.unregister(registerUrl);
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
            try {
                NotifyListener listener = RegistryProtocol.this.overrideListeners.remove(subscribeUrl);
                registry.unsubscribe(subscribeUrl, listener);
                ExtensionLoader.getExtensionLoader(GovernanceRuleRepository.class).getDefaultExtension()
                        .removeListener(subscribeUrl.getServiceKey() + CONFIGURATORS_SUFFIX,
                                serviceConfigurationListeners.get(subscribeUrl.getServiceKey()));
            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }

            executor.submit(() -> {
                try {
                    int timeout = ConfigurationUtils.getServerShutdownTimeout();
                    if (timeout > 0) {
                        logger.info("Waiting " + timeout + "ms for registry to notify all consumers before unexport. " +
                                "Usually, this is called when you use dubbo API");
                        Thread.sleep(timeout);
                    }
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            });
        }

        public void setSubscribeUrl(URL subscribeUrl) {
            this.subscribeUrl = subscribeUrl;
        }

        public void setRegisterUrl(URL registerUrl) {
            this.registerUrl = registerUrl;
        }

        public URL getRegisterUrl() {
            return registerUrl;
        }
    }

    // for unit test
    private static RegistryProtocol INSTANCE;

    // for unit test
    public RegistryProtocol() {
        INSTANCE = this;
    }

    // for unit test
    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }
}
