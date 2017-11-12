(ns recycle.core
  (:refer-clojure :exclude [send key])
  (:require [clojure.core.async :as a :refer [>! >!! <! <!! go go-loop thread timeout]]
            [clojure.tools.logging :as log]
            [try.core :as try]
            [recycle.util :as util]))

(defn- start-service
  [service config]
  (try/try> ((:start service) ((:map-config service) config))))

(defn- stop-service
  [service instance]
  (try/try> ((:stop service) instance)))

(defn- receive-service
  [service instance args]
  (try/try> (apply (:receive service) instance args)))

(def ^:dynamic *timeout*
  "Maximum number of milliseconds to wait for a response from a
  service. This variable by default is nil, meaning the value used
  when creating the service is used."
  nil)

(defn service
  "Create a service using the spec map as a blueprint.
  The spec map may contain any of the following keys (all of the are
  optional):

  :key  - keyword that identifies the service

  :map-config - function that modifies the config arg that is supplied
  to the :start function when starting the service.

  :start - function that receives a config arg, starts the service,
  and returns its instance. By default a function that ignores the
  config arg and returns nil is used.

  :stop - function that receives the instance of the service and stops
  it. By default a function that ignores the instance arg and returns
  nil is used.

  :receive - function that receives the instance of the service, a
  variable number of args identifying the message sent to the service,
  and that dispatches the action corresponding to that message.

  :timeout - maximum number of milliseconds to wait for a response
  from the service. It can be overriden also with the special variable
  *timeout*. By default is 1 minute.

  :buf-or-n - core.async buffer or size of buffer to use for the
  service communication channel. By default is 1024."
  [spec]
  (let [spec         (or spec {})
        key          (or (:key spec)
                         (keyword (gensym "service-")))
        service      {:start      (or (:start spec) util/noop)
                      :stop       (or (:stop spec) util/noop)
                      :receive    (or (:receive spec)
                                      (util/throwing (ex-info "service do not implement :receive"
                                                              {:service key})))
                      :map-config (or (:map-config spec) identity)}
        timeout      (or *timeout* (:timeout spec) 60000)
        buf-or-n     (or (:buf-or-n spec) 1024)
        in           (a/chan buf-or-n)
        state        (volatile! [:stopped nil])
        instance     #(second @state)
        status       #(first @state)
        set-started! #(vreset! state [:started %])
        set-stopped! #(vreset! state [:stopped %])
        stopped?     #(= :stopped (status))
        started?     #(= :started (status))]
    (go-loop [msg (<! in)]
      (try
        (let [[out cmd args] msg
              result         (condp = cmd
                               ::start   (if (stopped?)
                                           (try/map #(set-started! %)
                                                    (start-service service args))
                                           (try/succeed (instance)))
                               ::stop    (if (started?)
                                           (try/map #(set-stopped! %)
                                                    (stop-service service (instance)))
                                           (try/succeed (instance)))
                               ::receive (if (started?)
                                           (receive-service service (instance) args)
                                           (try/fail (ex-info "called a service that isn't running" {:key  key
                                                                                                     :args args}))))]
          (go (>! out result)))
        (catch Exception e
          (log/warnf "unrecognized message received by service %s %s" key msg)))
      (recur (<! in)))
    (letfn [(send-message [cmd & [args]]
              (go (let [out          (a/chan)
                        msg          [out cmd args]
                        [put-res _]  (a/alts! [(go (>! in msg))
                                               (a/timeout timeout)])
                        [take-res _] (when put-res
                                       (a/alts! [(go (<! out))
                                                 (a/timeout timeout)]))]
                    (a/close! out)
                    (cond
                      (nil? put-res)
                      (try/fail (ex-info "put message to service timed out" {:service key
                                                                             :message msg}))

                      (nil? take-res)
                      (try/fail (ex-info "take message result from service timed out" {:service key
                                                                                       :message msg}))

                      :else
                      take-res))))]
      {::key      key
       ::start    (fn [config]
                    (send-message ::start config))
       ::started? started?
       ::stop     (fn []
                    (send-message ::stop))
       ::stopped? stopped?
       ::receive  (fn [args]
                    (send-message ::receive args))})))

(defn key
  "Returns the key that identifies service."
  [service]
  (::key service))

(defn start
  "Starts service using config as the configuration. If service cannot
  start throws an ex-info. Returns nil."
  [service config]
  @(<!! ((::start service) config))
  nil)

(defn stop
  "Stops service. If service cannot stop throws an ex-info. Returns
  nil."
  [service]
  @(<!! ((::stop service))))

(defn started?
  "Check if service is started. Returns true if it is, false if it's not."
  [service]
  ((::started? service)))

(defn stopped?
  "Check if service is stopped. Returns true if it is, false if it's not."
  [service]
  ((::stopped? service)))

(defn ask
  "Send args to service and return the result the service has for that invocation."
  [service & args]
  @(<!! ((::receive service) args)))

(defn ?
  "Alias for ask. Send args to service and return the result the service
  has for that invocation."
  [service & args]
  (apply ask service args))

(defn service-map
  "Create a service using the spec map as a blueprint.
  The spec map is a map of keywords identifying services to service-specs.
  Returns a service that will:
  - start (in any order) each service in the map when started

  - stop (in any order) each service in the map when stopped

  - route messages to each service when asked, the first arg in ask
  identifies the service key in the map and the rest are used to ask
  the service"
  [spec]
  (let [spec                 (or spec {})
        key                  (or (:key spec)
                                 (keyword (gensym "service-map-")))
        map-config           (or (:map-config spec)
                                 identity)
        start-system-service (fn [spec config]
                               (let [s (service spec)]
                                 (start s (map-config config))
                                 s))
        concurrently (fn [f]
                       (<!!
                         (a/into
                           {}
                           (a/merge
                             (map (fn [[key service]]
                                    (go [key (f service)])) spec)))))]
    (service {:key key
              :start (fn [config]
                       (concurrently #(start-system-service % config)))
              :stop (fn [instance]
                      (concurrently #(stop %)))
              :receive (fn [instance service-key & args]
                         (let [service (instance service-key)]
                           (if (nil? service)
                             (throw (ex-info "message sent to service not found in map"
                                             {:key     key
                                              :service service-key}))
                             (apply ask service args))))})))
