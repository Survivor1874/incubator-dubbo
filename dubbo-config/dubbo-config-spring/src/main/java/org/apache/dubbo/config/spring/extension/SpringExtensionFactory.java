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
package org.apache.dubbo.config.spring.extension;

import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.spring.util.BeanFactoryUtils;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.Set;

/**
 * SpringExtensionFactory
 */
public class SpringExtensionFactory implements ExtensionFactory {
    private static final Logger logger = LoggerFactory.getLogger(SpringExtensionFactory.class);

    private static final Set<ApplicationContext> contexts = new ConcurrentHashSet<ApplicationContext>();
    private static final ApplicationListener shutdownHookListener = new ShutdownHookListener();

    /**
     * 优雅停机
     * 这段代码寥寥数行，却是经过了深思熟虑之后的产物，期间迭代了 3 个大版本，真是不容易。
     * 这段代码很好地解决了第 4 节提出的两个问题
     * 担心两个钩子并发执行有问题？那就在可以注册 Spring 钩子的时候取消掉 JVM 的钩子。
     * 担心当前 Spring 容器没有注册 Spring 钩子？那就显示调用 registerShutdownHook 进行注册。
     *
     * @param context
     */
    public static void addApplicationContext(ApplicationContext context) {
        contexts.add(context);
        if (context instanceof ConfigurableApplicationContext) {
            ((ConfigurableApplicationContext) context).registerShutdownHook();
            DubboShutdownHook.getDubboShutdownHook().unregister();
        }
        BeanFactoryUtils.addApplicationListener(context, shutdownHookListener);
    }

    public static void removeApplicationContext(ApplicationContext context) {
        contexts.remove(context);
    }

    public static Set<ApplicationContext> getContexts() {
        return contexts;
    }

    // currently for test purpose
    public static void clearContexts() {
        contexts.clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getExtension(Class<T> type, String name) {

        //SPI should be get from SpiExtensionFactory
        if (type.isInterface() && type.isAnnotationPresent(SPI.class)) {
            return null;
        }

        for (ApplicationContext context : contexts) {
            if (context.containsBean(name)) {
                Object bean = context.getBean(name);
                if (type.isInstance(bean)) {
                    return (T) bean;
                }
            }
        }

        logger.warn("No spring extension (bean) named:" + name + ", try to find an extension (bean) of type " + type.getName());

        if (Object.class == type) {
            return null;
        }

        for (ApplicationContext context : contexts) {
            try {
                return context.getBean(type);
            } catch (NoUniqueBeanDefinitionException multiBeanExe) {
                logger.warn("Find more than 1 spring extensions (beans) of type " + type.getName() + ", will stop auto injection. Please make sure you have specified the concrete parameter type and there's only one extension of that type.");
            } catch (NoSuchBeanDefinitionException noBeanExe) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Error when get spring extension(bean) for type:" + type.getName(), noBeanExe);
                }
            }
        }

        logger.warn("No spring extension (bean) named:" + name + ", type:" + type.getName() + " found, stop get bean.");

        return null;
    }

    private static class ShutdownHookListener implements ApplicationListener {
        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof ContextClosedEvent) {
                DubboShutdownHook shutdownHook = DubboShutdownHook.getDubboShutdownHook();
                shutdownHook.doDestroy();
            }
        }
    }
}
