/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.commons.spring;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * @author Mike Liu
 */
public class ApplicationContextUtils implements ApplicationContextAware {
    private static ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        ApplicationContextUtils.ctx = ctx;
    }

    public static ApplicationContext getContext() { return ctx; }

    public static <T> Object getBean(String beanName, Class<T> beanClass) throws BeansException {
        if (StringUtils.isBlank(beanName)) { throw new IllegalArgumentException("beanName cannot be blank!"); }
        if (beanClass == null) { throw new IllegalArgumentException("beanClass cannot be null!"); }
        return ctx.getBean(beanName, beanClass);
    }
}
