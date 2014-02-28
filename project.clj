(defproject org.tobereplaced/jetty9-websockets-async "0.2.0"
  :description "A WebSocketServlet for Jetty 9 that offloads WebSocket
  communication to core.async channels."
  :url "https://github.com/ToBeReplaced/jetty9-websockets-async"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.eclipse.jetty.websocket/websocket-server
                  "9.1.2.v20140210"]]
  :global-vars {*warn-on-reflection* true}
  :pedantic? :abort
  :profiles {:dev {:dependencies [[com.stuartsierra/component "0.2.1"]]
                   :jvm-opts ["-Dorg.eclipse.jetty.LEVEL=OFF"]}})
