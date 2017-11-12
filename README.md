# recycle

A tiny (200LOC) Clojure library designed to manage application state
heavily inspired by
[stuartsierra/component](https://github.com/stuartsierra/component).

## Why?

Recycle tries to encourage an algebra-interpreter style when designing
our programs. The defined application services perform the role of
interpreters and the parts of the application consuming those services
communicate with then using algebras.

### Differences from Component

The main differences from component are:
- Application interacts with services via messages
- Services are started and stopped concurrently
- Services don't deal with changing their state depending on whether
  they are started or not
- Composing services into systems and subsystems is encouraged
- It can be combined with [mount](https://github.com/tolitius/mount)
  to not require whole app buy in

## Usage

``` clojure
(require '[recycle.core :as r])
```

### Services

Services are the main and only entity of the library.

#### Creating services

Function: `service`.

A service is created from a specification which is just a map of keywords to values. The whole list of accepted values is described in [Options when creating a service](#options-when-creating-a-service).

``` clojure
user> (require '[recycle.core :as r])
nil

user> (def my-service (r/service {}))
#'user/my-service
```

#### Starting/stopping services

Functions: `start`, `stop`.

A service can be started/stopped. When starting a service you need to
provide a config value (can be anything), this value is supplied to
the `:start` handler of a service which must return the value
representing the instance of the service (the default handler does
nothing and returns `nil` as the value for the instance).

``` clojure
user> (require '[recycle.core :as r])
nil

user> (def my-service (r/service {}))
#'user/my-service

user> (r/start my-service :some-config)
nil

user> (r/started? my-service)
true

user> (r/stop my-service)
nil

user> (r/started? my-service)
false

user> (r/stopped? my-service)
true
```

#### Sending messages to services

Functions: `ask`, `?`.

Services can receive messages, but only if they are running and they
were created with a `:receive` implementation:

``` clojure
user> (require '[recycle.core :as r])
nil

user> (def my-service (r/service {}))
#'user/my-service

user> (r/ask my-service "World")
ExceptionInfo called a service that isn't running  clojure.core/ex-info (core.clj:4617)

user> (r/start my-service {})
nil

user> (r/ask my-service "World")
ExceptionInfo service do not implement :receive  clojure.core/ex-info (core.clj:4617)

user> (def my-service (r/service {:start   (fn [config]
                                             (partial format (:template config)))
                                  :receive (fn [this arg]
                                             (this arg))}))
#'user/my-service

user> (r/start my-service {:template "Hello, %s!"})
nil

user> (r/ask my-service "World")
"Hello, World!"
```

#### Composing services

Services can be trivially composed by just wrapping them in other
services, but recycle provides a simple combinator `service-map` to
combine several services into one that starts/stops all services
concurrenctly and routes messages to those using the key they have in
the map. The best feature of using `service-map` is that the result
itself is another service, so all the functions that work with a
normal service can be used with combined/nested services.

``` clojure
user> (def hello-service (r/service ))
#'user/hello-service

user> (def adder-service (r/service {:start   (fn [config]
                                                +)
                                     :receive (fn [this & args]
                                                (apply this args))}))
#'user/adder-service

user> (def my-service (r/service-map {:hello {:start   (fn [config]
                                                         (partial format (:template config)))
                                              :receive (fn [this arg]
                                                         (this arg))}
                                      :adder {:start   (fn [config] +)
                                              :receive (fn [this & args]
                                                         (apply this args))}}
#'user/my-service

user> (r/start my-service {:template "Hello, %s!"})
nil

user> (r/ask my-service :hello "World")
"Hello, World!"

user> (r/ask my-service :adder 1 2 3)
6
```

### Options when creating a service

The map passed to `service` may contain any of the following keys in
order to perform more interesting functionalities:

- `:key` Keyword that identifies the service, by default one is generated.
- `:map-config` Function that modifies the config arg that is supplied to the :start function when starting the service.
- `:start` Function that receives a config arg, starts the service, and returns its instance. By default a function that ignores the config arg and returns nil is used.
- `:stop` Function that receives the instance of the service and stops it. By default a function that ignores the instance arg and returns nil is used.
- `:receive` Function that receives the instance of the service, a variable number of args identifying the message sent to the service, and that dispatches the action corresponding to that message.
- `:timeout` Maximum number of milliseconds to wait for a response from the service. It can be overriden also with the special variable `*timeout*`. By default is 1 minute.
- `:buf-or-n` core.async buffer or size of buffer to use for the service communication channel. By default is 1024.

### Starting services in certain order

DOC TODO

### Start and stop parts of the application

DOC TODO

### Start the application without certain services

DOC TODO

### Swapping service instances

DOC TODO

## License

Copyright © 2017, Anler Hernández Peral

Distributed under the BSD 2-Clause License.
