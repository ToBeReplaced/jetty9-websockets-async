jetty9-websockets-async
==========

A [WebSocketServlet] for Jetty 9 that offloads WebSocket communication
to `core.async` channels.

This is heavily inspired by [jetty7-websockets-async].

## Supported Clojure Versions ##

jetty9-websockets-async is tested on Clojure 1.5.1 only.  It may work
on other Clojure versions.

## Maturity ##

This is alpha quality software.

## Installation ##

jetty9-websockets-async is available as a Maven artifact from
[Clojars]:

```clojure
[org.tobereplaced/jetty9-websockets-async "0.2.1"]
```

jetty9-websockets-async follows [Semantic Versioning].  Please note
that this means the public API for this library is not yet considered
stable.

## Documentation ##

Please read the [Codox API Documentation], as it contains all of the
information you would like to know.

## Usage ##

I generally don't believe in wrapping libraries because doing so often
makes them less flexible.  In this particular case, I can't possibly
know all of the things you want to do with your web server.
Consequently, instead of making a `configurator` for
`ring-jetty-adapter`, I have exposed a function you can use to create
a [WebSocketServlet] or a [WebSocketClient] that offloads
communication onto `core.async` channels.  What you do with it is up
to you.

If you're looking for example code embedding this servlet in an
application, take a look at the example [jetty-chatroom].  Also, the
complete [unit tests] may be of interest.

## Support ##

Please post any comments, concerns, or issues to the Github issues
page or find me on `#clojure`.  I welcome any and all feedback.

## Changelog ##

### v0.2.1 ###

- Add `<!` to require statement for newer versions of `core.async`.

### v0.2.0 ###

- Add type hint for return of `servlet`.
- `:go-loop` has been renamed `:process-channel`.
- Add exception and closing information to the process result.
- API is backward compatible only if you did not use the `:go-loop`
  key.

### v0.1.0 ###

- Initial Release

## License ##

Copyright © 2014 ToBeReplaced

Distributed under the Eclipse Public License, the same as Clojure.
The license can be found at LICENSE in the root of this distribution.

[WebSocketServlet]: http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/websocket/servlet/WebSocketServlet.html
[WebSocketClient]: http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/websocket/servlet/client/WebSocketClient.html
[Codox API Documentation]: http://ToBeReplaced.github.com/jetty9-websockets-async
[jetty-chatroom]: https://github.com/ToBeReplaced/jetty9-websockets-async/blob/master/examples/jetty_chatroom.clj
[unit tests]: https://github.com/ToBeReplaced/jetty9-websockets-async/blob/master/test/org/tobereplaced/jetty9_websockets_async_test.clj
[jetty7-websockets-async]: https://github.com/lynaghk/jetty7-websockets-async
[Clojars]: http://clojars.org/org.tobereplaced/jetty9-websockets-async
[Semantic Versioning]: http://semver.org
