(ns org.tobereplaced.jetty9-websockets-async
  "A WebSocketServlet for Jetty 9 that offloads WebSocket
  communication to core.async channels."
  (:require [clojure.core.async :refer [go go-loop close! >!! chan]]
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

(defn- async-adapter-factory
  "Returns a function that accepts a result from async-preconnect and
  returns a corresponding WebSocketAdapter."
  [connection-channel]
  (fn [{:keys [read-channel write-channel] :as connection-map}]
    (proxy [WebSocketAdapter] []
      (onWebSocketConnect [^Session session]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketConnect session))
        (>!! connection-channel
             (assoc connection-map
               :session session
               :go-loop (go-loop []
                          (let [message (<! write-channel)]
                            (if (nil? message)
                              (do
                                (close! read-channel)
                                (.close session))
                              (do
                                (.sendString (.getRemote session) message)
                                (recur))))))))
      (onWebSocketText [message] (>!! read-channel message))
      (onWebSocketClose [status-code reason]
        (let [^WebSocketAdapter this this]
          (proxy-super onWebSocketClose status-code reason))
        (close! read-channel)
        (close! write-channel)))))

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
  placed on the connection channel.

  The connection map contains a :go-loop for the underlying process, a
  :session for the underlying Session object, a :read-channel,
  :write-channel, and :preconnect-result.

  The :read-channel and :write-channel may be used to read and write
  messages over the WebSocket.  The :preconnect-result may contain
  anything you would like.  One usage could be to use the preconnect
  function to authorize the connection via a session-cookie and return
  the session-cookie as the result."
  ([connection-channel read-channel-fn write-channel-fn]
     (servlet connection-channel read-channel-fn write-channel-fn identity))
  ([connection-channel read-channel-fn write-channel-fn preconnect]
     (-> connection-channel
         async-adapter-factory
         (web-socket-creator (async-preconnect read-channel-fn
                                               write-channel-fn
                                               preconnect))
         web-socket-servlet)))

(defn connect!
  "Connects a WebSocket client to uri.  Returns a channel that will
  receive a connection map if the connection is made successfully,
  closed otherwise.

  The connection map contains a :go-loop for the underlying process, a
  :session for the underlying Session object, a :read-channel,
  and a :write-channel.

  The :read-channel and :write-channel may be used to read and write
  messages over the WebSocket."
  [^WebSocketClient client ^URI uri read-channel write-channel]
  {:pre [(.isRunning client)]}
  (let [communication-channel (chan 3)
        adapter (async-adapter-factory communication-channel)
        session (.connect client
                          (adapter {:read-channel read-channel
                                    :write-channel write-channel})
                          uri)]
    (go
      (try
        @session
        (<! communication-channel)
        ;; This is hacky since the consumer really shouldn't close the
        ;; producer, but this lets us reuse our WebSocketAdapter.
        (finally (close! communication-channel))))))
