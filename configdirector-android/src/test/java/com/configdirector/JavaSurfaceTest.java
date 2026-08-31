package com.configdirector;

import static com.google.common.truth.Truth.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import org.junit.Test;

/**
 * Guards the shape of the API as Java sees it. Kotlin has several ways to write a member that
 * compiles for a Kotlin caller and arrives in Java as something no one can call, and none of them
 * are visible from a Kotlin test.
 */
public class JavaSurfaceTest {

  private static final Class<?>[] PUBLIC_API = {
    AndroidLogger.class,
    CompletionCallback.class,
    ConfigDirectorClient.class,
    ClientOptions.class,
    ClientOptions.Builder.class,
    ClientEvent.class,
    ClientEvent.ConfigsUpdated.class,
    ClientEvent.ContextUpdated.class,
    ClientEvent.Ready.class,
    ClientEventListener.class,
    ConfigDirectorContext.class,
    ConfigDirectorContext.Builder.class,
    ConfigDirectorException.class,
    ConfigDirectorLogger.class,
    ConfigEvaluation.class,
    ConfigListener.class,
    ConfigDirectorValidationException.class,
    ConnectReason.class,
    ConnectionMode.class,
    ConnectionOptions.class,
    ConnectionOptions.Builder.class,
    EvaluationListener.class,
    EvaluationReason.class,
    LogLevel.class,
    Subscription.class,
    Metadata.class,
  };

  // An internal Kotlin member is public bytecode under a mangled name, so it shows up in Java
  // autocomplete as getId$configdirector_android().
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

  // The Kotlin DSL overloads are the reason for @JvmSynthetic: an overload taking a Function1
  // alongside the builder one would make the Java call ambiguous, and there is no lambda Java
  // could pass it.
  @Test
  public void keepsTheKotlinDslOutOfTheJavaSurface() {
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

  // A suspend function arrives in Java as a method taking a Continuation, which Java has no way
  // to supply. Every one of them is hidden with @JvmSynthetic and paired with a callback overload.
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

  // The Kotlin conveniences -- the flows, and reading a config as the type of its default value --
  // are built on the API above rather than beside it, and Java has no use for any of them.
  @Test
  public void keepsKotlinOnlyExtensionsOutOfTheJavaSurface() throws ClassNotFoundException {
    Class<?> extensions = Class.forName("com.configdirector.KotlinExtensionsKt");

    List<String> visible = new ArrayList<>();
    for (Method method : declaredPublicMethods(extensions)) {
      visible.add(method.getName());
    }

    assertThat(visible).isEmpty();
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
