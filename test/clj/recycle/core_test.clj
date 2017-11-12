(ns recycle.core-test
  (:require [clojure.test :refer :all]
            [recycle.core :as r]))

(deftest service-test
  (testing "A service has a key"
    (let [s (r/service {})]
      (is (keyword? (r/key s))))

    (let [s (r/service {:key :my-service})]
      (is (= :my-service (r/key s)))))

  (testing "A service can be started and stopped."
    (let [s (r/service {})]
      (r/start s :config)
      (is (r/started? s))

      (r/stop s)
      (is (r/stopped? s))))

  (testing "A message sent to a service is only accepted if it has been started."
    (let [s (r/service {:receive #(inc %2)})]
      (is (thrown-with-msg? Exception
                            #"called a service that isn't running"
                            (r/? s 0)))

      (r/start s :config)
      (is (= 1
             (r/? s 0)))))

  (testing "Starting/stopping a service are idempotent actions."
    (let [spy (volatile! 0)
          s   (r/service {:start (fn [_] (vswap! spy inc))
                          :stop  (fn [_] (vswap! spy inc))})]
      (r/start s :config)
      (r/start s :config)
      (r/start s :config)
      (is (= 1 @spy))

      (r/stop s)
      (r/stop s)
      (r/stop s)
      (is (= 2 @spy))))

  (testing "The service receives the config when starting."
    (let [spy    (volatile! nil)
          config :config
          s      (r/service {:start (fn [config] (vreset! spy config))})]
      (r/start s config)
      (is (= config @spy))))

  (testing "The service receives the config returned by :map-config when starting."
    (let [spy            (volatile! nil)
          config         :config
          another-config :another-config
          s              (r/service {:map-config (constantly another-config)
                                     :start      (fn [config] (vreset! spy config))})]
      (r/start s config)
      (is (= another-config @spy)))))

(deftest service-map-test
  (testing "A service-map has a key"
    (let [s (r/service-map {})]
      (is (keyword? (r/key s))))

    (let [s (r/service-map {:key :my-key})]
      (is (= :my-key
             (r/key s)))))

  (testing "A service-map can be started and stopped."
    (let [s (r/service-map {})]
      (r/start s :config)
      (is (r/started? s))

      (r/stop s)
      (is (r/stopped? s))))

  (testing "A message sent to a service-map is only accepted if it is
  running and the provided service key corresponds to a service in the
  map"
    (let [s (r/service-map {:a-service {:receive #(inc %2)}})]
      (is (thrown-with-msg? Exception
                            #"called a service that isn't running"
                            (r/? s 0)))

      (r/start s :config)
      (is (thrown-with-msg? Exception
                            #"message sent to service not found in map"
                            (r/? s 0)))

      (is (= 1
             (r/? s :a-service 0)))

      (r/stop s)
      (is (thrown-with-msg? Exception
                            #"called a service that isn't running"
                            (r/? s 0))))))

(deftest timeout-test 
  (testing "Waiting for a task of a service times out if the service
  do not respond in time."
    (let [s (r/service {:timeout 100
                        :start   (fn [_] (Thread/sleep 1000))})]
      (is (thrown-with-msg? Exception
                            #"timed out"
                            (r/start s :config)))))

  (testing "Waiting for a task of a service times out if the service
  do not respond in time. (using *timeout* this time)"
    (let [s (with-redefs [r/*timeout* 100]
              (r/service {:timeout 5000
                          :start   (fn [_] (Thread/sleep 1000))}))]
      (is (thrown-with-msg? Exception
                            #"timed out"
                            (r/start s :config))))))
