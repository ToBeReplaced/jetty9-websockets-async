(ns org.tobereplaced.jetty9-websockets-async-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :refer [chan alts!! timeout close! go]]
            [com.stuartsierra.component :refer [start stop Lifecycle]]
            [org.tobereplaced.jetty9-websockets-async
             :refer [servlet connect!]])
  (:import [java.net URI]
           [javax.servlet Servlet]
           [org.eclipse.jetty.websocket.api Session UpgradeException]
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

(defn- timeout-alts!!
  "Does alts!! on port and a timeout with the set number of
  milliseconds, defaulting to 1000.  Returns the first value from the
  vector returned by alts!!."
  ([port] (timeout-alts!! port 1000))
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
  [communication-channel read-channel-fn write-channel-fn
   & more]
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

(defmacro ^:private with-client-server
  "Runs body in a context with comm bound to a communication-channel,
  client to the connection-map of a WSClient, and server bound to the
  first result on the communication channel."
  [comm client server & body]
  `(let [ch-fn# #(chan 10) ~comm (ch-fn#)]
     (with-components [server# (ws-server ~comm ch-fn# ch-fn#)
                       client# (ws-client (ch-fn#) (ch-fn#))]
       (let [~client (:connection-map client#)
             ~server (timeout-alts!! ~comm)]
         ~@body))))

(def ^:private comm-keys
  "The keys as part of every running connection map."
  #{:session :read-channel :write-channel :process-channel})

(deftest connection-test
  (with-client-server comm client server
    (is (every? #(contains? client %) comm-keys)
        "client should have return connection map")
    (is (every? #(contains? server %) (conj comm-keys :preconnect-result))
        "server comm should receive connection map with preconnect-result")
    (is (nil? (timeout-alts!! comm))
        "server comm should not have more than one map on it")))

(deftest client->server-test
  (with-client-server _ client server
    (is (= "ping"
           (do
             (timeout-alts!! [(:write-channel client) "ping"])
             (timeout-alts!! (:read-channel server))))
        "client should be able to write to server")))

(deftest server->client-test
  (with-client-server _ client server
    (is (= "ping"
           (do
             (timeout-alts!! [(:write-channel server) "ping"])
             (timeout-alts!! (:read-channel client))))
        "server should be able to write to client")))

(deftest invalid-write-message-test
  (with-client-server _ __ server
    (let [ch (:process-channel server)]
      (is (= [nil ch]
             (do
               (timeout-alts!! [(:write-channel server) :not-a-string])
               (alts!! [ch (timeout 1000)])))
          "a non-string write crashes the process silently"))))

(deftest client-close-test
  (with-client-server _ client server
    (is (= [1000 nil]
           (do
             (close! (:write-channel client))
             (timeout-alts!! (:process-channel server))))
        "closing the client write channel should close the WebSocket cleanly")))

(deftest server-close-test
  (with-client-server _ client server
    (is (= [1000 nil]
           (do
             (close! (:write-channel server))
             (timeout-alts!! (:process-channel client))))
        "closing the server write channel should close the WebSocket cleanly")))

(deftest running-error-test
  (is (= (repeat 2 [1009
                    "Text message size [65537] exceeds maximum size [65536]"])
         (with-client-server _ client server
           (do
             (timeout-alts!! [(:write-channel client)
                              (apply str (repeat 65537 "x"))])
             [(timeout-alts!! (:process-channel server))
              (timeout-alts!! (:process-channel client))])))
      "violating the WebSocketPolicy should result in an error for both"))

(deftest preconnect-test
  (let [ch-fn #(chan 10) comm (ch-fn)]
    (with-components [server (ws-server comm ch-fn ch-fn
                                        (constantly false))
                      client (ws-client (ch-fn) (ch-fn))]
      (is (nil? (timeout-alts!! comm))
          "server should see no connection made if preconnect is falsey")
      (is (instance? UpgradeException (:connection-map client))
          "client should see an UpgradeException if preconnect is falsey"))))
