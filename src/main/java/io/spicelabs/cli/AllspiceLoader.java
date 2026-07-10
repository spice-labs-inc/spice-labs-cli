// SPDX-License-Identifier: Apache-2.0
/* Copyright 2025 Spice Labs, Inc. & Contributors */

package io.spicelabs.cli;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and loads the allspice fat JAR from the filesystem at runtime.
 *
 * <p>The allspice JAR is present only in the enterprise Docker image at
 * {@code /opt/allspice/allspice.jar}. It is <strong>not</strong> a Maven
 * dependency of the CLI — the CLI is OSS, allspice is proprietary. This loader
 * creates an isolated {@link URLClassLoader} so allspice's Scala 3 stdlib and
 * its transitive dependencies do not pollute the CLI's own classpath.
 *
 * <p>If the JAR is absent (OSS image), {@link #isAvailable()} returns {@code false}
 * and the {@code spice registry} / {@code spice survey static} commands are not
 * registered.
 *
 * <p>The loader reflectively instantiates {@code io.spicelabs.allspice.cli.AllspiceBuilder}
 * and invokes its methods. AllspiceBuilder is designed to be Java-safe (plain
 * types, throws {@code AllspiceException} on failure), so reflection is minimal:
 * one {@code newInstance} call and per-method {@code Method} lookups cached for
 * reuse.
 */
public final class AllspiceLoader {

  private static final Logger log = LoggerFactory.getLogger(AllspiceLoader.class);

  /** Default location of the allspice fat JAR in the enterprise image. */
  static final String DEFAULT_JAR_PATH = "/opt/allspice/allspice.jar";

  /** Override via env var for non-standard layouts (e.g. local dev). */
  private static final String ENV_JAR_PATH = "ALLSPICE_JAR";

  private static final String BUILDER_CLASS = "io.spicelabs.allspice.cli.AllspiceBuilder";
  private static final String EXCEPTION_CLASS = "io.spicelabs.allspice.cli.AllspiceException";

  private static AllspiceLoader instance;
  private static String testJarPath;

  private final URLClassLoader classLoader;
  private final Object builder;
  private final Class<?> builderClass;

  private AllspiceLoader(URLClassLoader classLoader, Object builder, Class<?> builderClass) {
    this.classLoader = classLoader;
    this.builder = builder;
    this.builderClass = builderClass;
  }

  /**
   * Returns the singleton loader, creating it on first call by probing for the
   * allspice JAR. Returns {@code null} if the JAR is not present (OSS image).
   */
  public static synchronized AllspiceLoader getInstance() {
    if (instance != null) return instance;
    if (!isAvailable()) return null;

    String jarPath = resolveJarPath();
    log.info("Loading allspice from {}", jarPath);

    try {
      URL[] urls = { new File(jarPath).toURI().toURL() };
      // Parent = the CLI's classloader. This lets allspice see the JDK and
      // any shared deps (ginger-j is already on the CLI classpath), while
      // keeping allspice's Scala stdlib isolated.
      URLClassLoader cl = new URLClassLoader(urls, AllspiceLoader.class.getClassLoader());
      Class<?> builderCls = cl.loadClass(BUILDER_CLASS);
      Object bld = builderCls.getDeclaredConstructor().newInstance();
      instance = new AllspiceLoader(cl, bld, builderCls);
      return instance;
    } catch (Exception e) {
      log.error("Failed to load allspice from {}: {}", jarPath, e.getMessage(), e);
      return null;
    }
  }

  /**
   * Probes whether the allspice JAR exists on the filesystem.
   */
  public static boolean isAvailable() {
    return new File(resolveJarPath()).isFile();
  }

  /**
   * Resolves the JAR path: test override > env var > default.
   */
  static String resolveJarPath() {
    if (testJarPath != null) return testJarPath;
    String env = System.getenv(ENV_JAR_PATH);
    if (env != null && !env.isBlank()) return env;
    return DEFAULT_JAR_PATH;
  }

  /** Test hook: override the JAR path (bypasses env var + default). */
  static void setTestJarPath(String path) { testJarPath = path; }

  /** Test hook: clear the override and cached singleton. */
  static void reset() {
    testJarPath = null;
    instance = null;
  }

  /**
   * The AllspiceBuilder instance (reflectively created).
   */
  Object getBuilder() { return builder; }

  /**
   * The AllspiceBuilder class (for Method lookup).
   */
  Class<?> getBuilderClass() { return builderClass; }

  /**
   * The classloader that owns allspice's classes.
   */
  URLClassLoader getClassLoader() { return classLoader; }

  // ── Reflective method handles ─────────────────────────────────────────────

  private Method withConfig;
  private Method withDiscovery;
  private Method withOutput;
  private Method withJson;
  private Method withVerbose;
  private Method discover;
  private Method run;
  private Method status;
  private Method list;
  private Method retry;

  Method withConfigMethod() throws NoSuchMethodException {
    if (withConfig == null)
      withConfig = builderClass.getMethod("withConfig", Path.class);
    return withConfig;
  }

  Method withDiscoveryMethod() throws NoSuchMethodException {
    if (withDiscovery == null)
      withDiscovery = builderClass.getMethod("withDiscovery", Path.class);
    return withDiscovery;
  }

  Method withOutputMethod() throws NoSuchMethodException {
    if (withOutput == null)
      withOutput = builderClass.getMethod("withOutput", Path.class);
    return withOutput;
  }

  Method withJsonMethod() throws NoSuchMethodException {
    if (withJson == null)
      withJson = builderClass.getMethod("withJson", boolean.class);
    return withJson;
  }

  Method withVerboseMethod() throws NoSuchMethodException {
    if (withVerbose == null)
      withVerbose = builderClass.getMethod("withVerbose", boolean.class);
    return withVerbose;
  }

  Method discoverMethod() throws NoSuchMethodException {
    if (discover == null)
      discover = builderClass.getMethod("discover");
    return discover;
  }

  Method runMethod() throws NoSuchMethodException {
    if (run == null)
      run = builderClass.getMethod("run");
    return run;
  }

  Method statusMethod() throws NoSuchMethodException {
    if (status == null)
      status = builderClass.getMethod("status");
    return status;
  }

  Method listMethod() throws NoSuchMethodException {
    if (list == null)
      list = builderClass.getMethod("list");
    return list;
  }

  Method retryMethod() throws NoSuchMethodException {
    if (retry == null)
      retry = builderClass.getMethod("retry");
    return retry;
  }

  /**
   * Checks whether a caught Throwable is an allspice AllspiceException.
   */
  boolean isAllspiceException(Throwable t) {
    return t.getClass().getName().equals(EXCEPTION_CLASS);
  }
}
