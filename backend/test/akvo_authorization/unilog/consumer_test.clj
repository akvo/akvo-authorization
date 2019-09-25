(ns akvo-authorization.unilog.consumer-test
  (:require
    [akvo-authorization.unilog.consumer :as consumer]
    [clojure.test :refer :all]))

(deftest should-log-event-delay-time
  (are [x e] (x (consumer/event-delay e))
    not {:payload {}}                                      ;; bogus
    not {:payload {:context {:timestamp 0}}}               ;; system
    not {:payload {:context {:timestamp Long/MAX_VALUE}}}  ;; future
    some? {:payload {:context {:timestamp 1}}}              ;; past
    ))
