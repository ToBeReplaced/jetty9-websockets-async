(ns org.tobereplaced.jetty9-websockets-async
  "A WebSocketServlet for Jetty 9 that offloads WebSocket
  communication to core.async channels."
  (:refer-clojure :exclude [if-some])
  (:require [clojure.core.async :refer [go go-loop close! >!! chan alt!]]
            [clojure.string :refer [join]])
  (:import [java.net URI]
           [javax.servlet.http HttpServletRequest]
           [org.eclipse.jetty.websocket.api WebSocketAdapter Session]
           [org.eclipse.jetty.websocket.client WebSocketClient]
           [org.eclipse.jetty.websocket.servlet
            WebSocketCreator WebSocketServletFactory WebSocketServlet]))

(defn- request-map
  "Returns a ring request map from the PostUpgradedHttpServletRequest
  object.  We can't use the function from ring-servlet here because
  some methods like .getContentType are not implemented on the
  PostUpgradedHttpServletRequest."
  [^HttpServletRequest request]
  {:server-port (.getServerPort request)
   :server-name (.getServerName request)
   :remote-addr (.getRemoteAddr request)
   :uri (.getRequestURI request)
   :query-string (.getQueryString request)
   :scheme (keyword (.getScheme request))
   :request-method (keyword (.toLowerCase (.getMethod request)))
   :headers (reduce (fn [acc ^String name]
                      (assoc acc
                        (.toLowerCase name)
                        (->> (.getHeaders request name)
                             (enumeration-seq)
                             (join ","))))
                    {} (enumeration-seq (.getHeaderNames request)))})

(defmacro ^:private send-or-close!
  "Sends the message over the WebSocket and recurs if non-nil, closes
  the other channels and the WebSocket session with status code 1000
  (CLOSE_NORMAL) otherwise.  Returns the result."
  [message session remote read-channel result]
  `(if (nil? ~message)
     (do
       (.close ~session)
       (close! ~read-channel)
       ~result)
     (do
       ;; Errors inside of the sendString call are passed to
       ;; onWebSocketError so you don't need to capture them here
       (.sendString ~remote ~message)
       (recur ~result))))

(defn- write-loop
  "Returns a channel containing the result of a loop that sends
  messages over the WebSocket when items are placed on the
  write-channel.

  If there is an error "
  [^Session session read-channel write-channel result-channel]
  (let [remote (.getRemote session)]
    (go-loop [result nil]
      (if result
        (send-or-close! (<! write-channel) session remote
                        read-channel result)
        (alt!
          result-channel ([v] (close! write-channel) (recur v))
          write-channel ([message]
                           (send-or-close! message session remote
                                           read-channel result))
          :priority true)))))

(defn- async-adapter-factory
  "Returns a function that accepts a result from async-preconnect and
  returns a corresponding WebSocketAdapter."
  [connection-channel]
  (fn [{:keys [read-channel write-channel] :as connection-map}]
    (let [ever-connected? (atom false) result-channel (chan 1)]
      (proxy [WebSocketAdapter] []
        (onWebSocketConnect [^Session session]
          (let [^WebSocketAdapter this this]
            (proxy-super onWebSocketConnect session))
          (reset! ever-connected? true)
          (>!! connection-channel
               (assoc connection-map
                 :session session
                 :process-channel (write-loop session read-channel
                                              write-channel result-channel))))
        (onWebSocketText [message] (>!! read-channel message))
        (onWebSocketError [throwable]
          ;; We must handle the case where this is called before
          ;; onWebSocketConnect.  This occurs when there is a failed
          ;; handshake on the client side, for example.
          (if @ever-connected?
            (>!! result-channel throwable)
            (>!! connection-channel throwable)))
        (onWebSocketClose [status-code reason]
          (let [^WebSocketAdapter this this]
            (proxy-super onWebSocketClose status-code reason))
          (>!! result-channel [status-code reason]))))))

(defn- async-preconnect
  "Returns a function that accepts a ring request map and returns a
  map containing a read-channel, write-channel, and preconnect-result
  when the preconnect-result is truthy."
  [read-channel-fn write-channel-fn preconnect]
  (fn [request]
    (when-let [result (preconnect request)]
      {:read-channel (read-channel-fn)
       :write-channel (write-channel-fn)
       :preconnect-result result})))

(defn- web-socket-creator
  "Returns a WebSocketCreator from a function that creates a
  WebSocketListner from a single argument.  The preconnect function
  will be called with the ring request map from the servlet upgrade
  request and its response will be handed to the adapter factory.  If
  the preconnect function returns a non-truthy value, the adapter
  factory will not be called."
  [adapter-factory preconnect]
  (reify WebSocketCreator
    (createWebSocket
      [this request _]
      (when-let [preconnect-result (-> request
                                       .getHttpServletRequest
                                       request-map
                                       preconnect)]
        (adapter-factory preconnect-result)))))

(defn- web-socket-servlet
  "Returns a WebSocketServlet that uses a WebSocketCreator to create
  new WebSockets."
  [creator]
  (proxy [WebSocketServlet] []
    (configure [^WebSocketServletFactory factory]
      (.setCreator factory creator))))

(defn servlet
  "Returns a WebSocketServlet to communicate over WebSockets through
  channels.

  When an incoming WebSocket connection is attempted, the
  corresponding ring request map will be passed to the preconnect
  function (default: identity).  If the preconnect function returns a
  truthy value, a connection is established and a connection map is
  placed on the connection channel.  In the event that the connection
  attempt fails for whatever reason, an exception is placed on the
  connection channel instead.

  The connection map contains a :process-channel for the underlying
  process, a :session for the underlying Session object, a
  :read-channel, :write-channel, and :preconnect-result.

  The :read-channel and :write-channel may be used to read and write
  messages over the WebSocket.  The :preconnect-result may contain
  anything you would like.  One usage could be to use the preconnect
  function to authorize the connection via a session-cookie and return
  the session-cookie as the result.

  The :process-channel will return nil when the write-channel is
  closed, a vector containing a status code and message when the other
  side closes the connection, or an exception if an error occurs.

  The underlying process only handles strings, so you must place
  strings on the channel.  If you do not, the process will crash
  silently."
  (^org.eclipse.jetty.websocket.servlet.WebSocketServlet
   [connection-channel read-channel-fn write-channel-fn]
   (servlet connection-channel read-channel-fn write-channel-fn identity))

  (^org.eclipse.jetty.websocket.servlet.WebSocketServlet
   [connection-channel read-channel-fn write-channel-fn preconnect]
   (-> connection-channel
       async-adapter-factory
       (web-socket-creator (async-preconnect read-channel-fn
                                             write-channel-fn
                                             preconnect))
       web-socket-servlet)))

(defn connect!
  "Connects a WebSocket client to uri.  Returns a channel that will
  receive a connection map if the connection is made successfully or
  an exception.

  The connection map contains a :process-channel for the underlying
  process, a :session for the underlying Session object, a
  :read-channel, and a :write-channel.

  The :read-channel and :write-channel may be used to read and write
  messages over the WebSocket.

  The :process-channel will return nil when the write-channel is
  closed, a vector containing a status code and message when the other
  side closes the connection, or an exception if an error occurs.

  The underlying process only handles strings, so you must place
  strings on the channel.  If you do not, the process will crash
  silently."
  [^WebSocketClient client ^URI uri read-channel write-channel]
  {:pre [(.isRunning client)]}
  (let [communication-channel (chan 1)
        adapter (async-adapter-factory communication-channel)
        session (.connect client
                          (adapter {:read-channel read-channel
                                    :write-channel write-channel})
                          uri)]
    (go
      ;; This exception is placed onto the communication-channel by
      ;; onWebSocketError
      (try @session (catch Exception _))
      (<! communication-channel))))
