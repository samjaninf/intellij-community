package com.intellij.driver.client.impl;

import org.jetbrains.annotations.NotNull;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JmxCallHandler implements InvocationHandler {
  private static final long CALL_TIMEOUT_SECONDS =
    Long.getLong("driver.jmx.call.timeout.seconds", 180);

  private final JmxHost hostInfo;
  private final ObjectName mbeanName;
  private JMXConnector currentConnector;

  public JmxCallHandler(JmxHost hostInfo, String objectName) {
    this.hostInfo = hostInfo;

    try {
      this.mbeanName = new ObjectName(objectName);
    }
    catch (MalformedObjectNameException e) {
      throw new RuntimeException("Incorrect JMX object name: " + objectName, e);
    }
  }

  @Override
  public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if ("close".equals(method.getName())) {
      if (this.currentConnector != null) {
        try {
          this.currentConnector.close();
        }
        finally {
          this.currentConnector = null;
        }
      }
      return null;
    }

    if (this.currentConnector == null) {
      ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
        this.currentConnector = getConnector();
      }
      catch (IOException e) {
        this.currentConnector = null;
        throw new JmxCallException("Unable to connect to JMX host: " + getServiceTextURL(), e);
      }
      finally {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
      }
    }

    try {
      MBeanServerConnection mbsc = this.currentConnector.getMBeanServerConnection();
      MBeanServerInvocationHandler wrappedHandler = new MBeanServerInvocationHandler(mbsc, mbeanName);

      if (CALL_TIMEOUT_SECONDS <= 0) {
        return wrappedHandler.invoke(proxy, method, args);
      }

      CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
        try {
          return wrappedHandler.invoke(proxy, method, args);
        }
        catch (Throwable t) {
          throw new RuntimeException(t);
        }
      });
      return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    catch (TimeoutException e) {
      resetConnector();
      throw new JmxCallException("JMX call timed out after " + CALL_TIMEOUT_SECONDS + "s: " + method.getName(), e);
    }
    catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re && re.getCause() instanceof IOException ioe) {
        resetConnector();
        throw new JmxCallException("Unable to perform JMX call: " + method + "(" + (args != null ? Arrays.asList(args) : "null") + ")", ioe);
      }
      resetConnector();
      throw new JmxCallException("Unable to perform JMX call: " + method + "(" + (args != null ? Arrays.asList(args) : "null") + ")",
                                  cause != null ? cause : e);
    }
    catch (IOException e) {
      resetConnector();
      throw new JmxCallException("Unable to perform JMX call: " + method + "(" + (args != null ? Arrays.asList(args) : "null") + ")", e);
    }
  }

  private void resetConnector() {
    try {
      if (this.currentConnector != null) {
        this.currentConnector.close();
      }
    }
    catch (IOException ignored) {
    }
    finally {
      this.currentConnector = null;
    }
  }

  public JMXConnector getConnector() throws IOException {
    JMXServiceURL url;
    var textUrl = getServiceTextURL();
    try {
      url = new JMXServiceURL(textUrl);
    }
    catch (MalformedURLException e) {
      throw new RuntimeException("Incorrect service URL: " + textUrl, e);
    }

    Map<String, Object> properties = new HashMap<>();
    if (hostInfo.getUser() != null) {
      properties.put(JMXConnector.CREDENTIALS, new String[]{hostInfo.getUser(), hostInfo.getPassword()});
    }

    return JMXConnectorFactory.connect(url, properties);
  }

  private @NotNull String getServiceTextURL() {
    return "service:jmx:rmi:///jndi/rmi://" + hostInfo.getAddress() + "/jmxrmi";
  }

  public static <T> T jmx(Class<T> clazz) {
    return jmx(clazz, new JmxHost(null, null, "localhost:7777"));
  }

  @SuppressWarnings("unchecked")
  public static <T> T jmx(Class<T> clazz, JmxHost hostInfo) {
    JmxName jmxName = clazz.getAnnotation(JmxName.class);
    if (jmxName == null) {
      throw new RuntimeException("There is no @JmxName annotation for " + clazz);
    }

    if (jmxName.value().isEmpty()) {
      throw new RuntimeException("JmxName.value is empty for " + clazz);
    }

    return (T)Proxy.newProxyInstance(JmxCallHandler.class.getClassLoader(), new Class[]{clazz, AutoCloseable.class},
                                     new JmxCallHandler(hostInfo, jmxName.value()));
  }
}