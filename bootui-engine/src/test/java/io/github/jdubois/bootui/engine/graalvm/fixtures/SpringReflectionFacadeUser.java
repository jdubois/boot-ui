package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Method;
import org.springframework.beans.BeanUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/** Triggers GRAAL-REFLECT-001 through Spring's reflection facades. */
public class SpringReflectionFacadeUser {

    public Object invoke(Object target, String name) {
        Method method = ReflectionUtils.findMethod(target.getClass(), name);
        return ReflectionUtils.invokeMethod(method, target);
    }

    public Class<?> load(String className) throws ClassNotFoundException {
        return ClassUtils.forName(className, getClass().getClassLoader());
    }

    public void copy(Object source, Object target) {
        BeanUtils.copyProperties(source, target);
    }
}
