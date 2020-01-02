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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.Environment;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.configcenter.DynamicConfigurationFactory;
import org.apache.dubbo.metadata.integration.MetadataReportService;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.support.MockInvoker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.config.ConfigurationUtils.parseProperties;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * AbstractDefaultConfig
 *
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long serialVersionUID = -1559314110797223229L;

    /**
     * 接口本地实现类的名称
     * <p>
     * Local impl class name for the service interface
     */
    protected String local;

    /**
     * Local stub class name for the service interface
     */
    protected String stub;

    /**
     * Service monitor
     */
    protected MonitorConfig monitor;

    /**
     * Strategies for generating dynamic agents，there are two strategies can be choosed: jdk and javassist
     */
    protected String proxy;

    /**
     * Cluster type
     */
    protected String cluster;

    /**
     * The {@link Filter} when the provider side exposed a service or the customer side references a remote service used,
     * if there are more than one, you can use commas to separate them
     */
    protected String filter;

    /**
     * The Listener when the provider side exposes a service or the customer side references a remote service used
     * if there are more than one, you can use commas to separate them
     */
    protected String listener;

    /**
     * The owner of the service providers
     */
    protected String owner;

    /**
     * Connection limits, 0 means shared connection, otherwise it defines the connections delegated to the current service
     */
    protected Integer connections;

    /**
     * The layer of service providers
     */
    protected String layer;

    /**
     * The application info
     */
    protected ApplicationConfig application;

    /**
     * The module info
     */
    protected ModuleConfig module;

    /**
     * Registry centers
     */
    protected List<RegistryConfig> registries;

    protected String registryIds;

    // connection events
    protected String onconnect;

    /**
     * Disconnection events
     */
    protected String ondisconnect;
    protected MetadataReportConfig metadataReportConfig;

    protected ConfigCenterConfig configCenter;

    // callback limits
    private Integer callbacks;
    // the scope for referring/exporting a service, if it's local, it means searching in current JVM only.
    private String scope;

    /**
     * Check whether the registry config is exists, and then conversion it to {@link RegistryConfig}
     * 1、从当前 ReferenceConfig中加载 registries信息，并设置为 RegistryConfig
     * 2、如果上一步中，没有发现任何registries信息，就会尝试从 ** 外部化配置** 中获取
     * 3、依次判断各个注册中心是否有用，没有用 即 !registryConfig.isValid() 则会抛出异常
     * 4、尝试从注册中心中读取配置，即将注册中心作为配置中心，并重新刷新整个配置读取。将注册中心变为默认的配置中心，在 useRegistryForConfigIfNecessary 有所体现
     */
    protected void checkRegistry() {

        // 从当前 ReferenceConfig 中加载注册中心配置
        loadRegistriesFromBackwardConfig();

        // 读取外部化配置的
        convertRegistryIdsToRegistries();

        // 判断是否有用
        for (RegistryConfig registryConfig : registries) {
            if (!registryConfig.isValid()) {
                throw new IllegalStateException("No registry config found or it's not a valid config! " + "The registry config is: " + registryConfig);
            }
        }

        // 将读取到的配置中心，进一步获取
        useRegistryForConfigIfNecessary();
    }

    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // for backward compatibility
        createApplicationIfAbsent();

        if (!application.isValid()) {
            throw new IllegalStateException("No application config found or it's not a valid config! " + "Please add <dubbo:application name=\"...\" /> to your spring config.");
        }

        ApplicationModel.setApplication(application.getName());

        // backward compatibility
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    protected void checkMonitor() {
        createMonitorIfAbsent();
        if (!monitor.isValid()) {
            logger.info("There's no valid monitor config found, if you want to open monitor statistics for Dubbo, " +
                    "please make sure your monitor is configured properly.");
        }
    }

    private void createMonitorIfAbsent() {
        if (this.monitor != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        setMonitor(
                configManager
                        .getMonitor()
                        .orElseGet(() -> {
                            MonitorConfig monitorConfig = new MonitorConfig();
                            monitorConfig.refresh();
                            return monitorConfig;
                        })
        );
    }

    protected void checkMetadataReport() {
        // TODO get from ConfigManager first, only create if absent.
        if (metadataReportConfig == null) {
            setMetadataReportConfig(new MetadataReportConfig());
        }
        metadataReportConfig.refresh();
        if (!metadataReportConfig.isValid()) {
            logger.warn("There's no valid metadata config found, if you are using the simplified mode of registry url, " +
                    "please make sure you have a metadata address configured properly.");
        }
    }


    void startConfigCenter() {
        if (configCenter == null) {
            ConfigManager.getInstance().getConfigCenter().ifPresent(cc -> this.configCenter = cc);
        }

        /*
         *  configCenter 的初始化 单独执行
         *  当配置中心不为空时候，就会去执行 prepareEnvironment，这个方法主要就是读取配置中心的配置。
         */
        if (this.configCenter != null) {
            // TODO there may have duplicate refresh
            this.configCenter.refresh();

            // 前期检查工作
            prepareEnvironment();
        }
        ConfigManager.getInstance().refreshAll();
    }

    /**
     * 通过SPI机制不同类型配置中心配置
     * 读取配置数据到Environment的 externalConfigurationMap 和 appExternalConfigurationMap
     */
    private void prepareEnvironment() {
        if (configCenter.isValid()) {
            if (!configCenter.checkOrUpdateInited()) {
                return;
            }

            // 加载 配置中心
            DynamicConfiguration dynamicConfiguration = getDynamicConfiguration(configCenter.toUrl());

            // 从配置中心获取 configFile 配置
            String configContent = dynamicConfiguration.getConfig(configCenter.getConfigFile(), configCenter.getGroup());

            String appGroup = application != null ? application.getName() : null;
            String appConfigContent = null;

            // 从配置中心获取 appConfigFile 配置
            if (StringUtils.isNotEmpty(appGroup)) {

                // 如果ConfigCenter没有appConfigFile，则会使用 configFile 替代 appConfigFile。
                // 最终，从配置文件中获取到的，是 String类型，一行一个数据，最后用 Properties 再将 其读出，执行
                // updateExternalConfigurationMap或者 updateAppExternalConfigurationMap 进行更新。
                appConfigContent = dynamicConfiguration.getConfig(
                        StringUtils.isNotEmpty(configCenter.getAppConfigFile())
                                ? configCenter.getAppConfigFile()
                                : configCenter.getConfigFile(),
                        appGroup);
            }
            try {

                // 将获取到的String 类型的 url类型 数据转化为 key value
                Environment.getInstance().setConfigCenterFirst(configCenter.isHighestPriority());
                Environment.getInstance().updateExternalConfigurationMap(parseProperties(configContent));
                Environment.getInstance().updateAppExternalConfigurationMap(parseProperties(appConfigContent));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to parse configurations from Config Center.", e);
            }
        }
    }

    private DynamicConfiguration getDynamicConfiguration(URL url) {

        // SPI 方式，加载不同的 ConfigurationFactory
        // 目前dubbo（2.7.2）支持以下集中配置中心：
        // Apollo：是携程框架部门研发的开源配置管理中心 。Apollo GitHub 介绍
        // Consul：是Spring Cloud的一款分布式配置中心。Consul GitHub 介绍
        // etcd：分布式可靠的键值对存储。etcd GitHub介绍
        // Nacos：阿里巴巴 开源的一款分布式注册中心。 Nacos GitHub 介绍
        // Zookeeper： Apache 一款开源的 配置中心。Zookeeper GitHub介绍

        // NopDynamicConfiguration 只是一个空架子，只是实现 DynamicConfiguration 但是没有实现任何方法。
        // 以前是作为默认注册中心，即没有声明配置中心时候以它作为默认配置中心。
        DynamicConfigurationFactory factories = ExtensionLoader
                .getExtensionLoader(DynamicConfigurationFactory.class)
                .getExtension(url.getProtocol());

        // 获取 动态 配置
        DynamicConfiguration configuration = factories.getDynamicConfiguration(url);
        Environment.getInstance().setDynamicConfiguration(configuration);
        return configuration;
    }

    /**
     * Load the registry and conversion it to {@link URL}, the priority order is: system property > dubbo registry config
     *
     * @param provider whether it is the provider side
     * @return
     */
    protected List<URL> loadRegistries(boolean provider) {
        // check && override if necessary
        List<URL> registryList = new ArrayList<URL>();

        if (CollectionUtils.isNotEmpty(registries)) {
            for (RegistryConfig config : registries) {

                // zookeeper 地址（如果是 zookeeper 注册中心）
                String address = config.getAddress();

                if (StringUtils.isEmpty(address)) {
                    address = Constants.ANYHOST_VALUE;
                }

                if (!RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {

                    Map<String, String> map = new HashMap<String, String>();

                    // {application=demo-consumer, qos.port=33333}
                    appendParameters(map, application);

                    // {path=org.apache.dubbo.registry.RegistryService, protocol=zookeeper, application=demo-consumer, release=, qos.port=33333, dubbo=2.0.2, pid=72938, timestamp=1573634695089}
                    appendParameters(map, config);
                    map.put(Constants.PATH_KEY, RegistryService.class.getName());
                    appendRuntimeParameters(map);
                    if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                        map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                    }
                    List<URL> urls = UrlUtils.parseURLs(address, map);

                    for (URL url : urls) {
                        url = URLBuilder.from(url)
                                .addParameter(Constants.REGISTRY_KEY, url.getProtocol())
                                .setProtocol(Constants.REGISTRY_PROTOCOL)
                                .build();
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (!provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        return registryList;
    }

    /**
     * Load the monitor config from the system properties and conversation it to {@link URL}
     *
     * @param registryURL
     * @return
     */
    protected URL loadMonitor(URL registryURL) {
        checkMonitor();
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        appendRuntimeParameters(map);

        //set ip
        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isEmpty(hostToRegistry)) {
            hostToRegistry = NetUtils.getLocalHost();
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);
        appendParameters(map, monitor);
        appendParameters(map, application);
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {
            if (!map.containsKey(Constants.PROTOCOL_KEY)) {
                if (getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, Constants.DUBBO_PROTOCOL);
                }
            }
            return UrlUtils.parseURL(address, map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            return URLBuilder.from(registryURL)
                    .setProtocol(Constants.DUBBO_PROTOCOL)
                    .addParameter(Constants.PROTOCOL_KEY, Constants.REGISTRY_PROTOCOL)
                    .addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map))
                    .build();
        }
        return null;
    }

    static void appendRuntimeParameters(Map<String, String> map) {
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.RELEASE_KEY, Version.getVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
    }

    private URL loadMetadataReporterURL() {
        String address = metadataReportConfig.getAddress();
        if (StringUtils.isEmpty(address)) {
            return null;
        }
        Map<String, String> map = new HashMap<String, String>();
        appendParameters(map, metadataReportConfig);
        return UrlUtils.parseURL(address, map);
    }

    protected MetadataReportService getMetadataReportService() {

        if (metadataReportConfig == null || !metadataReportConfig.isValid()) {
            return null;
        }
        return MetadataReportService.instance(this::loadMetadataReporterURL);
    }

    /**
     * Check whether the remote service interface and the methods meet with Dubbo's requirements.it mainly check, if the
     * methods configured in the configuration file are included in the interface of remote service
     *
     * @param interfaceClass the interface of remote service
     * @param methods        the methods configured
     */
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // interface cannot be null
        Assert.notNull(interfaceClass, new IllegalStateException("interface not allow null!"));

        // to verify interfaceClass is an interface
        if (!interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // check if methods exist in the remote service interface
        if (CollectionUtils.isNotEmpty(methods)) {
            for (MethodConfig methodBean : methods) {
                methodBean.setService(interfaceClass.getName());
                methodBean.setServiceId(this.getId());
                methodBean.refresh();
                String methodName = methodBean.getName();
                if (StringUtils.isEmpty(methodName)) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: " + "<dubbo:service interface=\"" + interfaceClass.getName() + "\" ... >" + "<dubbo:method name=\"\" ... /></<dubbo:reference>");
                }

                boolean hasMethod = Arrays.stream(interfaceClass.getMethods()).anyMatch(method -> method.getName().equals(methodName));
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName() + " not found method " + methodName);
                }
            }
        }
    }

    /**
     * Legitimacy check and setup of local simulated operations. The operations can be a string with Simple operation or
     * a classname whose {@link Class} implements a particular function
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface that will be referenced
     */
    void checkMock(Class<?> interfaceClass) {
        if (ConfigUtils.isEmpty(mock)) {
            return;
        }

        String normalizedMock = MockInvoker.normalizeMock(mock);
        if (normalizedMock.startsWith(Constants.RETURN_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                //Check whether the mock value is legal, if it is illegal, throw exception
                MockInvoker.parseMockValue(normalizedMock);
            } catch (Exception e) {
                throw new IllegalStateException("Illegal mock return in <dubbo:service/reference ... " +
                        "mock=\"" + mock + "\" />");
            }
        } else if (normalizedMock.startsWith(Constants.THROW_PREFIX)) {
            normalizedMock = normalizedMock.substring(Constants.THROW_PREFIX.length()).trim();
            if (ConfigUtils.isNotEmpty(normalizedMock)) {
                try {
                    //Check whether the mock value is legal
                    MockInvoker.getThrowable(normalizedMock);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock throw in <dubbo:service/reference ... " +
                            "mock=\"" + mock + "\" />");
                }
            }
        } else {
            //Check whether the mock class is a implementation of the interfaceClass, and if it has a default constructor
            MockInvoker.getMockObject(normalizedMock, interfaceClass);
        }
    }

    /**
     * Legitimacy check of stub, note that: the local will deprecated, and replace with <code>stub</code>
     *
     * @param interfaceClass for provider side, it is the {@link Class} of the service that will be exported; for consumer
     *                       side, it is the {@link Class} of the remote service interface
     */
    void checkStubAndLocal(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            Class<?> localClass = ConfigUtils.isDefault(local)
                    ? ReflectUtils.forName(interfaceClass.getName() + "Local")
                    : ReflectUtils.forName(local);
            verify(interfaceClass, localClass);
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            Class<?> localClass = ConfigUtils.isDefault(stub) ?
                    ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            verify(interfaceClass, localClass);
        }
    }

    private void verify(Class<?> interfaceClass, Class<?> localClass) {
        if (!interfaceClass.isAssignableFrom(localClass)) {
            throw new IllegalStateException("The local implementation class " + localClass.getName() +
                    " not implement interface " + interfaceClass.getName());
        }

        try {
            //Check if the localClass a constructor with parameter who's type is interfaceClass
            ReflectUtils.findConstructor(localClass, interfaceClass);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() +
                    "(" + interfaceClass.getName() + ")\" in local implementation class " + localClass.getName());
        }
    }

    private void convertRegistryIdsToRegistries() {
        if (StringUtils.isEmpty(registryIds) && CollectionUtils.isEmpty(registries)) {
            Set<String> configedRegistries = new HashSet<>();

            configedRegistries.addAll(getSubProperties(Environment.getInstance().getExternalConfigurationMap(), Constants.REGISTRIES_SUFFIX));
            configedRegistries.addAll(getSubProperties(Environment.getInstance().getAppExternalConfigurationMap(), Constants.REGISTRIES_SUFFIX));

            registryIds = String.join(",", configedRegistries);
        }

        if (StringUtils.isEmpty(registryIds)) {
            if (CollectionUtils.isEmpty(registries)) {
                setRegistries(
                        ConfigManager.getInstance().getDefaultRegistries()
                                .filter(CollectionUtils::isNotEmpty)
                                .orElseGet(() -> {
                                    RegistryConfig registryConfig = new RegistryConfig();
                                    registryConfig.refresh();
                                    return Arrays.asList(registryConfig);
                                })
                );
            }
        } else {
            String[] ids = Constants.COMMA_SPLIT_PATTERN.split(registryIds);
            List<RegistryConfig> tmpRegistries = CollectionUtils.isNotEmpty(registries) ? registries : new ArrayList<>();
            Arrays.stream(ids).forEach(id -> {
                if (tmpRegistries.stream().noneMatch(reg -> reg.getId().equals(id))) {
                    tmpRegistries.add(ConfigManager.getInstance().getRegistry(id).orElseGet(() -> {
                        RegistryConfig registryConfig = new RegistryConfig();
                        registryConfig.setId(id);
                        registryConfig.refresh();
                        return registryConfig;
                    }));
                }
            });

            if (tmpRegistries.size() > ids.length) {
                throw new IllegalStateException("Too much registries found, the registries assigned to this service " +
                        "are :" + registryIds + ", but got " + tmpRegistries.size() + " registries!");
            }

            setRegistries(tmpRegistries);
        }

    }

    private void loadRegistriesFromBackwardConfig() {
        // for backward compatibility
        // -Ddubbo.registry.address is now deprecated.
        // registries 配置不存在
        if (registries == null || registries.isEmpty()) {

            // jvm 参数获取注册中心地址
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                List<RegistryConfig> tmpRegistries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registryConfig.refresh();
                    tmpRegistries.add(registryConfig);
                }
                setRegistries(tmpRegistries);
            }
        }
    }

    /**
     * For compatibility purpose, use registry as the default config center if the registry protocol is zookeeper and
     * there's no config center specified explicitly.
     */
    private void useRegistryForConfigIfNecessary() {
        registries.stream().filter(RegistryConfig::isZookeeperProtocol).findFirst().ifPresent(rc -> {
            // we use the loading status of DynamicConfiguration to decide whether ConfigCenter has been initiated.
            Environment.getInstance().getDynamicConfiguration().orElseGet(() -> {

                // 获取单例的 配置管理器
                ConfigManager configManager = ConfigManager.getInstance();

                // 创建一个配置中心
                ConfigCenterConfig cc = configManager.getConfigCenter().orElse(new ConfigCenterConfig());

                // 设置协议
                cc.setProtocol(rc.getProtocol());

                // 设置地址
                cc.setAddress(rc.getAddress());

                // 设置优先级 将优先级设为false，这样就不会让这个配置中心去覆盖本地的配置。
                cc.setHighestPriority(false);

                // 设置配置中心
                setConfigCenter(cc);

                // 从配置中心刷新所有配置
                startConfigCenter();
                return null;
            });
        });
    }

    /**
     * @return local
     * @deprecated Replace to <code>getStub()</code>
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(Boolean)</code>
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(local.toString());
        }
    }

    /**
     * @param local
     * @deprecated Replace to <code>setStub(String)</code>
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    public String getStub() {
        return stub;
    }

    public void setStub(Boolean stub) {
        if (stub == null) {
            setStub((String) null);
        } else {
            setStub(stub.toString());
        }
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return listener;
    }

    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        ConfigManager.getInstance().setApplication(application);
        this.application = application;
    }

    private void createApplicationIfAbsent() {
        if (this.application != null) {
            return;
        }
        ConfigManager configManager = ConfigManager.getInstance();
        setApplication(
                configManager
                        .getApplication()
                        .orElseGet(() -> {
                            ApplicationConfig applicationConfig = new ApplicationConfig();
                            applicationConfig.refresh();
                            return applicationConfig;
                        })
        );
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        ConfigManager.getInstance().setModule(module);
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return CollectionUtils.isEmpty(registries) ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        setRegistries(registries);
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({"unchecked"})
    public void setRegistries(List<? extends RegistryConfig> registries) {
        ConfigManager.getInstance().addRegistries((List<RegistryConfig>) registries);
        this.registries = (List<RegistryConfig>) registries;
    }

    @Parameter(excluded = true)
    public String getRegistryIds() {
        return registryIds;
    }

    public void setRegistryIds(String registryIds) {
        this.registryIds = registryIds;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(String monitor) {
        setMonitor(new MonitorConfig(monitor));
    }

    public void setMonitor(MonitorConfig monitor) {
        ConfigManager.getInstance().setMonitor(monitor);
        this.monitor = monitor;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        checkMultiName("owner", owner);
        this.owner = owner;
    }

    public ConfigCenterConfig getConfigCenter() {
        return configCenter;
    }

    public void setConfigCenter(ConfigCenterConfig configCenter) {
        ConfigManager.getInstance().setConfigCenter(configCenter);
        this.configCenter = configCenter;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public MetadataReportConfig getMetadataReportConfig() {
        return metadataReportConfig;
    }

    public void setMetadataReportConfig(MetadataReportConfig metadataReportConfig) {
        this.metadataReportConfig = metadataReportConfig;
    }

}
