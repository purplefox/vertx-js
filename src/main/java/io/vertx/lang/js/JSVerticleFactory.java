/*
 * Copyright 2014 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.lang.js;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.impl.IsolatingClassLoader;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.VerticleFactory;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.io.FilenameUtils;
import org.mozilla.javascript.ContextFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.jar.JarEntry;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class JSVerticleFactory implements VerticleFactory {

  static {
    ClasspathFileResolver.init();
  }
  
  private static final Logger log = LoggerFactory.getLogger(JSVerticleFactory.class);

  /**
   * By default we will add an empty `process` global with an `env` property which contains the environment
   * variables - this allows Vert.x to work well with libraries such as React which expect to run on Node.js
   * and expect to have this global set, and which fail when it is not set.
   * To disable this then provide a system property with this name and set to any value.
   */
  public static final String DISABLE_NODEJS_PROCESS_ENV_PROP_NAME = "vertx.disableNodeJSProcessENV";
  public static final String ENABLE_NODEJS_VERTICLES_PROP_NAME = "vertx.enableNodeJSVerticles";

  private static final boolean ADD_NODEJS_PROCESS_ENV = System.getProperty(DISABLE_NODEJS_PROCESS_ENV_PROP_NAME) == null;
  private static final boolean ENABLE_NODEJS_VERTICLES = System.getProperty(ENABLE_NODEJS_VERTICLES_PROP_NAME) != null && checkNodeJSDependencies();

  private static final String DISABLING_NODEJS_VERTICLES = "Disabling resolution of node.js verticles";
	private static final String APIGEE_TRIREME_MISSING = "io.apigee.trireme:trireme-core, io.apigee.trireme:trireme-kernel, io.apigee.trireme:trireme-node10src, io.apigee.trireme:trireme-node12src, io.apigee.trireme:trireme-crypto, io.apigee.trireme:trireme-util v0.8.6 required on the classpath";
	private static final String COMMONS_IO_MISSING = "commons-io:commons-io v2.4 required on the classpath";
	private static final String RHINO_MISSING = "org.mozilla:rhino v1.7.7 required on the classpath";
	
  private static final String JVM_NPM = "vertx-js/util/jvm-npm.js";

  private Vertx vertx;
  private ScriptEngine engine;
  private ScriptObjectMirror futureJSClass;

  @Override
  public void init(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public String prefix() {
    return "js";
  }

  @Override
  public boolean blockingCreate() {
    return true;
  }

  @Override
  public Verticle createVerticle(String verticleName, ClassLoader classLoader) throws Exception {
    init();
    if (ENABLE_NODEJS_VERTICLES && IsolatingClassLoader.class.isAssignableFrom(classLoader.getClass()) && isNodeJS(classLoader))
    	return new NodeJSVerticle(VerticleFactory.removePrefix(verticleName), classLoader);
    return new JSVerticle(VerticleFactory.removePrefix(verticleName));
  }

  public class JSVerticle extends AbstractVerticle {

    private static final String VERTX_START_FUNCTION = "vertxStart";
    private static final String VERTX_START_ASYNC_FUNCTION = "vertxStartAsync";
    private static final String VERTX_STOP_FUNCTION = "vertxStop";
    private static final String VERTX_STOP_ASYNC_FUNCTION = "vertxStopAsync";

    protected final String verticleName;

    private JSVerticle(String verticleName) {
      this.verticleName = verticleName;
    }

    private ScriptObjectMirror exports;

    private boolean functionExists(String functionName) {
      Object som = exports.getMember(functionName);
      return som != null && !som.toString().equals("undefined");
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {

      /*
      NOTE:
      When we deploy a verticle we use require.noCache as each verticle instance must have the module evaluated.
      Also we run verticles in JS strict mode (with "use strict") -this means they cannot declare globals
      and other restrictions. We do this for isolation.
      However when doing a normal 'require' from inside a verticle we do not use strict mode as many JavaScript
      modules are written poorly and would fail to run otherwise.
       */
      exports = (ScriptObjectMirror) engine.eval("require.noCache('" + verticleName + "', null, true);");
      if (functionExists(VERTX_START_FUNCTION)) {
        exports.callMember(VERTX_START_FUNCTION);
        startFuture.complete();
      } else if (functionExists(VERTX_START_ASYNC_FUNCTION)) {
        Object wrappedFuture = futureJSClass.newObject(startFuture);
        exports.callMember(VERTX_START_ASYNC_FUNCTION, wrappedFuture);
      } else {
        startFuture.complete();
      }
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
      if (functionExists(VERTX_STOP_FUNCTION)) {
        exports.callMember(VERTX_STOP_FUNCTION);
        stopFuture.complete();
      } else if (functionExists(VERTX_STOP_ASYNC_FUNCTION)) {
        Object wrappedFuture = futureJSClass.newObject(stopFuture);
        exports.callMember(VERTX_STOP_ASYNC_FUNCTION, wrappedFuture);
      } else {
        stopFuture.complete();
      }
    }
  }
  
  public class NodeJSVerticle extends JSVerticle {
  	
  	private final NodeEnvironment env;	
  	private final NodeScript script;

    private NodeJSVerticle(String verticleName, ClassLoader loader) throws Exception {
    	super(verticleName);
    	env = new NodeEnvironment();
      String normalizedVerticleName = FilenameUtils.normalize(verticleName);
    	log.info("Starting NodeJSVerticle " + verticleName);
      String jar = ClasspathFileResolver.getJarPath(loader.getResource(normalizedVerticleName));
      Path path = new File(jar.substring(0, jar.lastIndexOf('.'))).toPath();
      StringBuffer buf = new StringBuffer();
      Files.readAllLines(path.resolve(normalizedVerticleName)).forEach(line -> buf.append(line));
      log.info("Resolving " + path.resolve(normalizedVerticleName).toString());
      script = env.createScript(verticleName, path.resolve(normalizedVerticleName).toFile(), null);
    }
    
    @Override
    public void start(Future<Void> startFuture) throws Exception {
      ScriptFuture status = script.execute();
      // Wait for the script to complete
      status.setListener(new ScriptStatusListener() {
				@Override
        public void onComplete(NodeScript script, ScriptStatus status) {
					log.info("Execution status for " + verticleName + ": " + status.getExitCode());
					if (status.hasCause()) log.info("Cause:" + status.getCause().toString());
        }
      });
      startFuture.complete();
    }
    
    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
    	script.close();
    	stopFuture.complete();
    }
  }

  private synchronized void init() {
    if (engine == null) {
      ScriptEngineManager mgr = new ScriptEngineManager();
      engine = mgr.getEngineByName("nashorn");
      if (engine == null) {
        throw new IllegalStateException("Cannot find Nashorn JavaScript engine - maybe you are not running with Java 8 or later?");
      }

      URL url = getClass().getClassLoader().getResource(JVM_NPM);
      if (url == null) {
        throw new IllegalStateException("Cannot find " + JVM_NPM + " on classpath");
      }
      try (Scanner scanner = new Scanner(url.openStream(), "UTF-8").useDelimiter("\\A")) {
        String jvmNpm = scanner.next();
        String jvmNpmPath = ClasspathFileResolver.resolveFilename(JVM_NPM);
        jvmNpm += "\n//# sourceURL=" + jvmNpmPath;
        engine.eval(jvmNpm);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }

      try {
        futureJSClass = (ScriptObjectMirror) engine.eval("require('vertx-js/future');");
        // Put the globals in
        engine.put("__jvertx", vertx);
        String globs =
          "var Vertx = require('vertx-js/vertx'); var vertx = new Vertx(__jvertx);" +
          "var console = require('vertx-js/util/console');" +
          "var setTimeout = function(callback,delay) { return vertx.setTimer(delay, callback); };" +
          "var clearTimeout = function(id) { vertx.cancelTimer(id); };" +
          "var setInterval = function(callback, delay) { return vertx.setPeriodic(delay, callback); };" +
          "var clearInterval = clearTimeout;" +
          "var parent = this;" +
          "var global = this;";
        if (ADD_NODEJS_PROCESS_ENV) {
          globs += "var process = {}; process.env=java.lang.System.getenv();";
        }
        engine.eval(globs);
      } catch (ScriptException e) {
        throw new IllegalStateException("Failed to eval: " + e.getMessage(), e);
      }
    }
  }
  
  private boolean isNodeJS(ClassLoader loader) {
    URL url = loader.getResource("package.json");
  	if (url == null) return false;
    else if (hasNodeModules(url) || hasEngines(loader)) {
    	try {
    		ClasspathFileResolver.unzip(url);
    	} catch (IOException ex) {
    		return false;
    	}
    	return true;
  	} else return false;
}
  
  private boolean hasNodeModules(URL url) {
  	JarEntry modules = null;
  	try {
  		modules = ClasspathFileResolver.getJarEntry(url, "node_modules");
  	} catch (IOException ex) {
  		log.warn(ex.toString());
  		return false;
  	}
  	return modules != null && (modules.isDirectory() || modules.getSize() == 0);
  }
  
  private boolean hasEngines(ClassLoader loader) {
    StringBuilder buf = new StringBuilder();
  	BufferedReader br = new BufferedReader(new InputStreamReader(loader.getResourceAsStream("package.json")));
  	br.lines().forEach(line -> buf.append(line));
  	JsonObject json = new JsonObject(buf.toString());
  	return json.containsKey("engines") && json.getJsonObject("engines").containsKey("node");
  }
  
  private static boolean checkNodeJSDependencies() {
  	try {
  		Class.forName("io.apigee.trireme.core.NodeEnvironment");
  		Class.forName("io.apigee.trireme.core.NodeScript");
  		Class.forName("io.apigee.trireme.core.ScriptFuture");
  		Class.forName("io.apigee.trireme.core.ScriptStatus");
  		Class.forName("io.apigee.trireme.core.ScriptStatusListener");  		
  	} catch (ClassNotFoundException ex) {
  		log.warn(APIGEE_TRIREME_MISSING);
  		log.warn(DISABLING_NODEJS_VERTICLES);
  		return false;
  	}
  	try {
  		Class.forName("org.apache.commons.io.FilenameUtils");
  	} catch (ClassNotFoundException ex) {
  		log.warn(COMMONS_IO_MISSING);
  		log.warn(DISABLING_NODEJS_VERTICLES);  
  		return false;
  	}
    String version = new ContextFactory().enterContext().getImplementationVersion();
    if (! version.startsWith("Rhino 1.7.7 ")) {
    	log.warn(RHINO_MISSING);
    	log.warn(DISABLING_NODEJS_VERTICLES);
    	return false;
  	}
    return true;
  }
}
