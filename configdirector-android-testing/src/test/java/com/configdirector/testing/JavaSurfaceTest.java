package com.configdirector.testing;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import org.junit.Test;

/**
 * Guards the shape of the API as Java sees it, the way the SDK's own surface test does: an
 * internal member, a Kotlin lambda parameter, or a suspend function each compile for a Kotlin
 * caller and arrive in Java as something no one can call.
 */
public class JavaSurfaceTest {

  private static final Class<?>[] PUBLIC_API = {
    ConfigDirectorTesting.class, TestClient.class, StandardErrorLogger.class,
  };

  @Test
  public void keepsInternalMembersOutOfTheJavaSurface() {
    List<String> mangled = new ArrayList<>();
    for (Class<?> type : PUBLIC_API) {
      for (Method method : declaredPublicMethods(type)) {
        if (method.getName().contains("$")) {
          mangled.add(type.getSimpleName() + "." + method.getName());
        }
      }
    }

    assertThat(mangled).isEmpty();
  }

  @Test
  public void keepsKotlinLambdasOutOfTheJavaSurface() {
    List<String> lambdaTaking = new ArrayList<>();
    for (Class<?> type : PUBLIC_API) {
      for (Method method : declaredPublicMethods(type)) {
        for (Class<?> parameter : method.getParameterTypes()) {
          if (Function1.class.isAssignableFrom(parameter)) {
            lambdaTaking.add(type.getSimpleName() + "." + method.getName());
          }
        }
      }
    }

    assertThat(lambdaTaking).isEmpty();
  }

  @Test
  public void keepsSuspendFunctionsOutOfTheJavaSurface() {
    List<String> continuationTaking = new ArrayList<>();
    for (Class<?> type : PUBLIC_API) {
      for (Method method : declaredPublicMethods(type)) {
        for (Class<?> parameter : method.getParameterTypes()) {
          if (Continuation.class.isAssignableFrom(parameter)) {
            continuationTaking.add(type.getSimpleName() + "." + method.getName());
          }
        }
      }
    }

    assertThat(continuationTaking).isEmpty();
  }

  private static List<Method> declaredPublicMethods(Class<?> type) {
    List<Method> methods = new ArrayList<>();
    for (Method method : type.getMethods()) {
      if (!method.isSynthetic() && method.getDeclaringClass().equals(type)) {
        methods.add(method);
      }
    }
    return methods;
  }
}
