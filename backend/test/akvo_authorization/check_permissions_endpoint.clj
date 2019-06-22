(ns akvo-authorization.check-permissions-endpoint
  (:require [clojure.test :refer :all]
            [akvo-authorization.authz :as authz]
            [testit.core :as it :refer [=in=> fact =>]]))

(deftest filters
  (let [aliases (delay {"cool alias" "one"})
        a-survey {:instance_id "one" :survey_id "1" :some-random-key "anything"}
        other-survey {:instance_id "two" :survey_id "1"}
        allow-survey {:flow-instance "one" :flow-id 1 :other-key "anything else"}]

    (are [result allowed queried] (= result (authz/filter-surveys allowed queried aliases))

      [] [] []
      [] [] [a-survey]
      [] [allow-survey] []
      [a-survey] [allow-survey] [a-survey]
      [a-survey] [allow-survey] [a-survey other-survey]
      [] [allow-survey] [other-survey]
      [{:instance_id "cool alias" :survey_id "1"}] [allow-survey] [{:instance_id "cool alias" :survey_id "1"}])))