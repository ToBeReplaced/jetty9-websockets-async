(ns jetty-chatroom
  (:require [clojure.core.async
             :refer [chan go-loop close! >!! <!! map< mix admix mult tap]]
            [clojure.edn :as edn]
            [com.stuartsierra.component :refer [Lifecycle] :as component]
            [org.tobereplaced.jetty9-websockets-async
             :refer [servlet connect!]])
  (:import [java.net URI]
           [java.util UUID]
           [org.eclipse.jetty.server Server]
           [org.eclipse.jetty.servlet ServletContextHandler ServletHolder]
           [org.eclipse.jetty.websocket.client WebSocketClient]))

(defn- start!
  "Start the server.  Connections will be assigned random UUIDs."
  [^Server server ws-channel]
  (let [holder (-> ws-channel
                   (servlet chan chan (fn [_] (UUID/randomUUID)))
                   ServletHolder.)
        handler (doto (ServletContextHandler.) (.addServlet holder "/*"))]
    (doto server (.setHandler handler) .start)))

(defn- listen
  "Listens for new WebSocket connections and adds their communication
  lines to the comm-link."
  [ws-channel comm-link]
  (let [{:keys [clients mix mult]} comm-link]
    (go-loop []
      (when-let [{uuid :preconnect-result
                  :keys [read-channel write-channel]
                  :as connection-map} (<! ws-channel)]
        ;; Add the new connection map to our set of clients.
        (swap! clients conj connection-map)
        ;; Add to our mix the message with the server-assigned uuid.
        (admix mix (map< #(pr-str (vector uuid %)) read-channel))
        ;; Set the write-channel to receive broadcast messages
        (tap mult write-channel)
        (recur)))))

(defrecord ChatServer [^Server server comm-link ws-channel ws-listener]
  Lifecycle
  (start [this]
    (let [ws-channel (chan)
          mix-channel (chan)
          ;; We are going to broadcast all messages received.
          comm-link {:clients (atom #{})
                     :mix-channel mix-channel
                     :mix (mix mix-channel)
                     :mult (mult mix-channel)}]
      (start! server ws-channel)
      (assoc this
        :comm-link comm-link
        :ws-channel ws-channel
        :ws-listener (listen ws-channel comm-link))))
  (stop [this]
    ;; This should be idempotent.
    (if ws-channel
      (do
        ;; Stop new connections.
        (.stop server)
        (close! ws-channel)
        (<!! ws-listener)
        ;; Stop existing connections.
        (doseq [{:keys [write-channel process-channel]} @(:clients comm-link)]
          (close! write-channel)
          (<!! process-channel))
        ;; Remove "live" data.
        (merge this (zipmap [:comm-link :ws-channel :ws-listener]
                            (repeat nil))))
      this)))

(defn chat-server
  "Returns a ChatServer.  The ChatServer listens for WebSocket
  connections and allows each user to communicate with all other
  users.  Messages will be sent as vectors of [uuid msg]."
  []
  (map->ChatServer {:server (Server. 8080)}))

(defrecord ChatClient
    [^WebSocketClient client connection-map read-channel write-channel]
  Lifecycle
  (start [this]
    (.start client)
    ;; Block until we connect or are refused.
    (assoc this
      :connection-map (<!! (connect! client (URI. "ws://localhost:8080")
                                     read-channel write-channel))))
  (stop [this]
    ;; This should be idempotent.
    (if connection-map
      (do
        (.stop client)
        (assoc this :connection-map nil))
      this)))

(defn chat-client
  "Returns a ChatClient.  The ChatClient connects to the ChatServer
  and participates in the chatroom."
  []
  (map->ChatClient {:client (WebSocketClient.)
                    :read-channel (chan 1)
                    :write-channel (chan 1)}))

;; Chatroom in action...
(let [server (component/start (chat-server))
      client-a (component/start (chat-client))
      client-b (component/start (chat-client))
      read-from-client #(edn/read-string (<!! (:read-channel %)))
      write-to-client  #(>!! (:write-channel %1) %2)]
  (try
    (write-to-client client-a "Hello")
    (let [[a-uuid msg] (read-from-client client-a)]
      (assert (= msg "Hello"))
      (assert (= [a-uuid msg] (read-from-client client-b)))
      (write-to-client client-b "Hi There")
      (let [[b-uuid msg] (read-from-client client-a)]
        (assert (= msg "Hi There"))
        (assert (= [b-uuid msg] (read-from-client client-b)))
        (write-to-client client-a "Are you there?")
        (when (= [a-uuid "Are you there?"] (read-from-client client-b))
          (write-to-client client-b "Yes I am!"))
        (assert (= [[a-uuid "Are you there?"] [b-uuid "Yes I am!"]]
                   [(read-from-client client-a) (read-from-client client-a)]))))
    (println "Chatroom action successful!")
    (finally
      (component/stop client-a)
      (component/stop client-b)
      (component/stop server))))
