/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.deploy.impl.cli;

import org.vertx.java.core.Handler;
import org.vertx.java.core.SimpleHandler;
import org.vertx.java.core.impl.DefaultVertx;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.deploy.impl.Args;
import org.vertx.java.deploy.impl.VerticleManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.util.Enumeration;
import java.util.Scanner;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class Starter {

  private static final Logger log = LoggerFactory.getLogger(Starter.class);

  private static final String CP_SEPARATOR = System.getProperty("path.separator");

  public static void main(String[] args) {
    new Starter(args);
  }

  private VertxInternal vertx = new DefaultVertx();
  private VerticleManager mgr;

  private Starter(String[] sargs) {
    final String version;
    
    // read version from Maven pom
    Properties prop = new Properties();
    InputStream is = getClass().getResourceAsStream("/META-INF/maven/com.autosportlabs/vertx-platform/pom.properties");
    try {
      prop.load(is);
      version = "vert.x-" + prop.getProperty("version");
    } catch (Exception e) {
      throw new RuntimeException("unable to read version from pom.properties", e);
    } finally {
      try {
        is.close();
      } catch (Exception e){
        /*ignore*/
      }
    }
    
    if (sargs.length < 1) {
      displaySyntax();
    } else {
      String command = sargs[0].toLowerCase();
      Args args = new Args(sargs);
      if ("version".equals(command)) {
        log.info(version);
      } else {
        if (sargs.length < 2) {
          displaySyntax();
        } else {
          String operand = sargs[1];
          switch (command) {
            case "version":
              log.info(version);
              break;
            case "run":
              runVerticle(false, operand, args);
              break;
            case "runmod":
              runVerticle(true, operand, args);
              break;
            case "install":
              installModule(operand, args);
              break;
            case "uninstall":
              uninstallModule(operand);
              break;
            default:
              displaySyntax();
          }
        }
      }
    }
  }

  private void installModule(String modName, Args args) {
    String repo = args.map.get("-repo");
    new VerticleManager(vertx, repo).installMod(modName);
  }

  private void uninstallModule(String modName) {
    new VerticleManager(vertx).uninstallMod(modName);
  }

  private void runVerticle(boolean module, String main, Args args) {
    boolean clustered = args.map.get("-cluster") != null;
    if (clustered) {
      log.info("Starting clustering...");
      int clusterPort = args.getInt("-cluster-port");
      if (clusterPort == -1) {
        clusterPort = 25500;
      }
      String clusterHost = args.map.get("-cluster-host");
      if (clusterHost == null) {
        clusterHost = getDefaultAddress();
        if (clusterHost == null) {
          log.error("Unable to find a default network interface for clustering. Please specify one using -cluster-host");
          return;
        } else {
          log.info("No cluster-host specified so using address " + clusterHost);
        }
      }
      vertx = new DefaultVertx(clusterPort, clusterHost);
    }
    String repo = args.map.get("-repo");
    mgr = new VerticleManager(vertx, repo);

    boolean worker = args.map.get("-worker") != null;

    String cp = args.map.get("-cp");
    if (cp == null) {
      cp = ".";
    }

    // Convert to URL[]

    String[] parts;

    if (cp.contains(CP_SEPARATOR)) {
      parts = cp.split(CP_SEPARATOR);
    } else {
      parts = new String[] { cp };
    }
    int index = 0;
    final URL[] urls = new URL[parts.length];
    for (String part: parts) {
      try {
        URL url = new File(part).toURI().toURL();
        urls[index++] = url;
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Invalid path " + part + " in cp " + cp) ;
      }
    }

    String sinstances = args.map.get("-instances");
    int instances;
    if (sinstances != null) {
      try {
        instances = Integer.parseInt(sinstances);

        if (instances != -1 && instances < 1) {
          log.error("Invalid number of instances");
          displaySyntax();
          return;
        }
      } catch (NumberFormatException e) {
        displaySyntax();
        return;
      }
    } else {
      instances = 1;
    }

    String configFile = args.map.get("-conf");
    JsonObject conf;

    if (configFile != null) {
      try (Scanner scanner = new Scanner(new File(configFile)).useDelimiter("\\A")){
        String sconf = scanner.next();
        try {
          conf = new JsonObject(sconf);
        } catch (DecodeException e) {
          log.error("Configuration file does not contain a valid JSON object");
          return;
        }
      } catch (FileNotFoundException e) {
        log.error("Config file " + configFile + " does not exist");
        return;
      }
    } else {
      conf = null;
    }

    Handler<String> doneHandler = new Handler<String>() {
      public void handle(String id) {
        if (id == null) {
          // Failed to deploy
          mgr.unblock();
        }
      }
    };
    if (module) {
      mgr.deployMod(main, conf, instances, null, doneHandler);
    } else {
      String includes = args.map.get("-includes");
      mgr.deployVerticle(worker, main, conf, urls, instances, null, includes, doneHandler);
    }

    addShutdownHook();
    mgr.block();
  }


  private void addShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        final CountDownLatch latch = new CountDownLatch(1);
        mgr.undeployAll(new SimpleHandler() {
          public void handle() {
            latch.countDown();
          }
        });
        while (true) {
          try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
              log.error("Timed out waiting to undeploy");
            }
            break;
          } catch (InterruptedException e) {
            //OK - can get spurious interupts
          }
        }
      }
    });
  }

  /*
  Get default interface to use since the user hasn't specified one
   */
  private String getDefaultAddress() {
    Enumeration<NetworkInterface> nets;
    try {
      nets = NetworkInterface.getNetworkInterfaces();
    } catch (SocketException e) {
      return null;
    }
    NetworkInterface netinf;
    while (nets.hasMoreElements()) {
      netinf = nets.nextElement();

      Enumeration<InetAddress> addresses = netinf.getInetAddresses();

      while (addresses.hasMoreElements()) {
        InetAddress address = addresses.nextElement();
        if (!address.isAnyLocalAddress() && !address.isMulticastAddress()
            && !(address instanceof Inet6Address)) {
          return address.getHostAddress();
        }
      }
    }
    return null;
  }

  private void displaySyntax() {

    String usage =

"    vertx run <main> [-options]                                                \n" +
"        runs a verticle called <main> in its own instance of vert.x.           \n" +
"        <main> can be a JavaScript script, a Ruby script, A Groovy script,     \n" +
"        a Java class, a Java source file, or a Python Script.\n\n" +
"    valid options are:\n" +
"        -conf <config_file>    Specifies configuration that should be provided \n" +
"                               to the verticle. <config_file> should reference \n" +
"                               a text file containing a valid JSON object      \n" +
"                               which represents the configuration.             \n" +
"        -cp <path>             specifies the path on which to search for       \n" +
"                               <main> and any referenced resources.            \n" +
"                               Defaults to '.' (current directory).            \n" +
"        -instances <instances> specifies how many instances of the verticle    \n" +
"                               will be deployed. Defaults to 1                 \n" +
"        -repo <repo_host>      specifies the repository to use to install      \n" +
"                               any modules.                                    \n" +
"                               Default is vertx.io/vertx-mods                  \n" +
"        -worker                if specified then the verticle is a worker      \n" +
"                               verticle.                                       \n" +
"        -includes <mod_list>   optional comma separated list of modules        \n" +
"                               which will be added to the classpath of         \n" +
"                               the verticle.                                   \n" +
"        -cluster               if specified then the vert.x instance will form \n" +
"                               a cluster with any other vert.x instances on    \n" +
"                               the network.                                    \n" +
"        -cluster-port          port to use for cluster communication.          \n" +
"                               Default is 25500.                               \n" +
"        -cluster-host          host to bind to for cluster communication.      \n" +
"                               If this is not specified vert.x will attempt    \n" +
"                               to choose one from the available interfaces.  \n\n" +

"    vertx runmod <modname> [-options]                                          \n" +
"        runs a module called <modname> in its own instance of vert.x.          \n" +
"        If the module is not already installed, Vert.x will attempt to install \n" +
"        it from the repository before running it.                            \n\n" +
"    valid options are:                                                         \n" +
"        -conf <config_file>    Specifies configuration that should be provided \n" +
"                               to the module. <config_file> should reference   \n" +
"                               a text file containing a valid JSON object      \n" +
"                               which represents the configuration.             \n" +
"        -instances <instances> specifies how many instances of the verticle    \n" +
"                               will be deployed. Defaults to 1                 \n" +
"        -repo <repo_host>      specifies the repository to use to get the      \n" +
"                               module from if it is not already installed.     \n" +
"                               Default is vertx.io/vertx-mods                  \n" +
"        -cluster               if specified then the vert.x instance will form \n" +
"                               a cluster with any other vert.x instances on    \n" +
"                               the network.                                    \n" +
"        -cluster-port          port to use for cluster communication.          \n" +
"                               Default is 25500.                               \n" +
"        -cluster-host          host to bind to for cluster communication.      \n" +
"                               If this is not specified vert.x will attempt    \n" +
"                               to choose one from the available interfaces.  \n\n" +

"    vertx install <modname> [-options]                                         \n" +
"        attempts to install a module from a remote repository.                 \n" +
"        Module will be installed into a local 'mods' directory unless the      \n" +
"        environment variable VERTX_MODS specifies a different location.      \n\n" +
"    valid options are:\n" +
"        -repo <repo_host>      specifies the repository to use to get the      \n" +
"                               module from if it is not already installed.     \n" +
"                               Default is vertx.io/vertx-mods                \n\n" +

"    vertx uninstall <modname>                                                  \n" +
"        attempts to uninstall a module from a remote repository.               \n" +
"        Module will be uninstalled from the local 'mods' directory unless the  \n" +
"        environment variable VERTX_MODS specifies a different location.      \n\n" +

"    vertx version                                                              \n" +
"        displays the version";

     log.info(usage);
  }

}
