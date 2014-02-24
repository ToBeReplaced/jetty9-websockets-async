(ns org.tobereplaced.jetty9-websockets-async-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :refer [chan alts!! timeout close! go]]
            [com.stuartsierra.component :refer [start stop Lifecycle]]
            [org.tobereplaced.jetty9-websockets-async
             :refer [servlet connect!]])
  (:import [java.net URI]
           [javax.servlet Servlet]
           [org.eclipse.jetty.websocket.api Session]
           [org.eclipse.jetty.server Server]
           [org.eclipse.jetty.servlet ServletContextHandler ServletHolder]
           [org.eclipse.jetty.websocket.client WebSocketClient]))

(defmacro ^:private with-components
  "Evaluates body in a try expression with names bound to the started
  versions of the components passed in and a finally clause that stops
  the components in reverse order."
  [bindings & body]
  {:pre [(vector? bindings)
         (even? (count bindings))
         (every? symbol? (take-nth 2 bindings))]}
  (if (= (count bindings) 0)
    `(do ~@body)
    `(let [~(bindings 0) (start ~(bindings 1))]
       (try
         (with-components ~(subvec bindings 2) ~@body)
         (finally (stop ~(bindings 0)))))))

(defrecord WSServer [^Server server]
  Lifecycle
  (start [this] (.start server) this)
  (stop [this] (.stop server) this))

(defn timeout-alts!!
  "Does alts!! on port and a timeout with the set number of
  milliseconds, defaulting to 1000.  Returns the first value from the
  vector returned by alts!!."
  ([port] (timeout-alts!! port 5000))
  ([port ms] (first (alts!! [port (timeout ms)]))))

(defrecord WSClient
    [^WebSocketClient client connection-map uri read-channel write-channel]
  Lifecycle
  (start [this]
    (.start client)
    (assoc this
      :connection-map (timeout-alts!! (connect! client uri
                                                read-channel write-channel))))
  (stop [this] (.stop client) this))

(defn- ws-server
  "Returns a WSServer."
  [communication-channel read-channel-fn write-channel-fn & more]
  (let [holder (ServletHolder. ^Servlet (apply servlet
                                               communication-channel
                                               read-channel-fn
                                               write-channel-fn
                                               more))
        handler (doto (ServletContextHandler.) (.addServlet holder "/*"))
        server (doto (Server. 8080) (.setHandler handler))]
    (->WSServer server)))

(defn- ws-client
  "Returns a WSClient."
  [read-channel write-channel]
  (map->WSClient {:client (WebSocketClient.)
                  :uri (URI. "ws://localhost:8080")
                  :read-channel read-channel
                  :write-channel write-channel}))

(defmacro with-client-server
  "Runs body in a context with comm bound to a communication-channel,
  client to the connection-map of a WSClient, and Server to a WSServer."
  [comm client server & body]
  `(let [ch-fn# #(chan 10) ~comm (ch-fn#)]
     (with-components [~server (ws-server ~comm ch-fn# ch-fn#)
                       client# (ws-client (ch-fn#) (ch-fn#))]
       (let [~client (:connection-map client#)]
         ~@body))))

(def ^:private comm-keys #{:go-loop :session :write-channel :read-channel})

(deftest connection-test
  (with-client-server comm client server
    (is (every? #(contains? client %) comm-keys)
        "client should have return connection map")
    (is (let [m (timeout-alts!! comm)]
          (every? #(contains? m %) (conj comm-keys :preconnect-result)))
        "server comm should receive connection map with preconnect-result")
    (is (nil? (timeout-alts!! comm))
        "server comm should not have more than one map on it")))

(deftest client->server-test
  (with-client-server comm client server
    (is (= "ping"
           (let [{:keys [write-channel]} client
                 {:keys [read-channel]} (timeout-alts!! comm)]
             (timeout-alts!! [write-channel "ping"])
             (timeout-alts!! read-channel)))
        "client should be able to write to server")))

(deftest server->client-test
  (with-client-server comm client server
    (is (= "ping"
           (let [{:keys [write-channel]} (timeout-alts!! comm)
                 {:keys [read-channel]} client]
             (timeout-alts!! [write-channel "ping"])
             (timeout-alts!! read-channel)))
        "server should be able to write to client")))

(deftest server-close-test
  (with-client-server comm client server
    (is (false? (do
                  (close! (:write-channel (timeout-alts!! comm)))
                  (Thread/sleep 500)
                  (.isOpen ^Session (:session client))))
        "server should be able to close the underlying socket connection")))

(deftest client-close-test
  (with-client-server comm client server
    (is (false? (do
                  (close! (:write-channel client))
                  (Thread/sleep 500)
                  (.isOpen ^Session (:session (timeout-alts!! comm)))))
        "client should be able to close the underlying socket connection")))

(deftest preconnect-test
  (let [ch-fn #(chan 10) comm (ch-fn)]
    (with-components [server (ws-server comm ch-fn ch-fn (constantly false))
                      client (ws-client (ch-fn) (ch-fn))]
      (is (nil? (timeout-alts!! comm))
          "server should see no connection made if preconnect is falsey")
      (is (nil? (:connection-map client))
          "client should see no connection made if preconnect is falsey"))))
